package com.onevoice.link.core

import kotlin.math.abs
import kotlin.math.sqrt

/** Why an ASR window ended. Distinct from why the turn ended. */
internal enum class ChunkCut(val logValue: String) {
    /** Cut inside a non-speech pause; the next window starts exactly here. */
    PAUSE("pause"),
    /** No reliable pause before the window ceiling; the next window overlaps this one. */
    FORCED("forced"),
    /** Last window of the turn. */
    TURN_END("turn_end"),
}

/** Why a turn ended; never reused to describe an internal window boundary. */
internal enum class TurnEndReason(val logValue: String) {
    /** End-of-turn silence after accepted speech. */
    SPEAKER_DONE("speaker_done"),
    /** The turn reached the configured length cap; later speech was not captured. */
    TURN_CAP("turn_cap"),
    /** A replay file ended, or capture was finished explicitly. */
    INPUT_END("input_end"),
    /** The first window was rejected by the language router (fail closed). */
    ROUTING_REJECTED("routing_rejected"),
    /** Stop/cancel while the turn was open. */
    CANCELLED("cancelled"),
    /** Capture or inference failed. */
    FAILED("failed"),
}

/**
 * One ASR window of a turn, as sample indices into the turn's [TurnArchive].
 *
 * Ranges are half-open. `[start, end)` is what the model sees; `[coreStart,
 * coreEnd)` is the part this window owns. Cores of consecutive windows tile the
 * accepted turn without gaps: after a pause cut the next window reads at least
 * [LongTurnConfig.pauseContextSamples] of left context it does not own, and
 * a forced cut shares [LongTurnConfig.overlapSamples] split at its midpoint.
 * With [LongTurnConfig.fillLeftContext] the left context is widened to fill
 * the model window when the window is emitted. Indices are Int
 * because a turn is capped ([LongTurnConfig.maxTurnSamples] <= 300 s).
 */
internal data class AudioChunk(
    val index: Int,
    val start: Int,
    val end: Int,
    val coreStart: Int,
    val coreEnd: Int,
    val cut: ChunkCut,
    /** Cut of the previous window; FORCED means this one opens with overlap. */
    val previousCut: ChunkCut?,
) {
    val length: Int get() = end - start
}

internal sealed interface SegmenterEvent {
    data class Chunk(val chunk: AudioChunk) : SegmenterEvent

    /** Exactly one per accepted turn, after its last chunk. */
    data class TurnEnded(
        val reason: TurnEndReason,
        /** Samples written to the archive, trailing silence included. */
        val archivedSamples: Int,
        /** End of the last frame the detector called speech. */
        val lastSpeechSample: Int,
        /** Samples of a read that crossed the turn cap and were not archived. */
        val droppedAtCap: Int,
    ) : SegmenterEvent
}

/**
 * Bounded PCM16 record of one turn. One writer (the capture loop) appends;
 * readers copy only ranges a published [AudioChunk] covers, which the writer
 * never rewrites. The capture loop does not clear it until every reader of the
 * previous turn has finished, so no range is recycled under a reader.
 */
internal class TurnArchive(capacitySamples: Int) {
    private val pcm = ShortArray(capacitySamples)

    var size: Int = 0
        private set

    val capacity: Int get() = pcm.size
    val bytes: Long get() = pcm.size.toLong() * Short.SIZE_BYTES

    fun clear() {
        size = 0
    }

    /** Append up to [count] samples; returns how many fit. */
    fun append(frame: ShortArray, offset: Int, count: Int): Int {
        val accepted = minOf(count, pcm.size - size)
        if (accepted > 0) System.arraycopy(frame, offset, pcm, size, accepted)
        size += accepted
        return accepted
    }

    /** Float copy of `[start, end)` for one inference call. */
    fun window(start: Int, end: Int): FloatArray {
        require(start in 0 until end && end <= size) { "window [$start,$end) outside archive of $size" }
        return FloatArray(end - start) { pcm[start + it] / 32_768f }
    }

    /** RMS and peak of `[start, end)`, normalized to full scale. */
    fun level(start: Int, end: Int): Pair<Float, Float> {
        var sumSquares = 0.0
        var peak = 0f
        for (index in start until end) {
            val sample = pcm[index] / 32_768f
            sumSquares += sample.toDouble() * sample
            peak = maxOf(peak, abs(sample))
        }
        val count = (end - start).coerceAtLeast(1)
        return sqrt(sumSquares / count).toFloat() to peak
    }

    internal fun sample(index: Int): Short = pcm[index]
}

/**
 * Turn-level capture policy for [LongTurnMode.ADAPTIVE]: echo guard, pre-roll,
 * onset and end-of-turn silence as in [UtteranceSegmenter], but the 10 s model
 * ceiling only cuts an ASR window; the microphone stays open.
 *
 * The detector is reset once per turn (at construction), never per window, and
 * the onset/minimum-speech/RMS checks decide only whether the turn is accepted:
 * a quiet stretch in the middle of an accepted turn is never discarded. Every
 * length is counted in samples actually received, so partial reads cannot skew
 * the silence clock. It owns no AudioRecord and is unit-testable.
 */
internal class TurnSegmenter(
    private val detector: SpeechActivityDetector,
    private val archive: TurnArchive,
    private val config: LongTurnConfig,
    guardSamples: Int = 0,
    private val log: (String) -> Unit = {},
    preRollSamples: Int = NpuEngine.PRE_ROLL_SAMPLES,
    private val minSpeechSamples: Int = NpuEngine.MIN_SPEECH_FRAMES * NpuEngine.FRAME_SAMPLES,
    private val minRms: Float = NpuEngine.SEGMENT_MIN_RMS,
    private val minPeak: Float = NpuEngine.SEGMENT_MIN_PEAK,
) {
    private var guardRemaining = guardSamples
    private val preRoll = ShortArray(preRollSamples)
    private var preRollSize = 0
    private var preRollWrite = 0

    private var active = false
    private var ended = false
    private var speechSamples = 0
    private var trailingSilenceSamples = 0
    private var lastSpeechEnd = 0
    /** Archive index where the current non-speech run began; -1 while in speech. */
    private var pauseRunStart = -1
    /** Pre-roll samples before the onset frame; they do not count against the turn cap. */
    private var preRollOffset = 0

    private var chunkIndex = 0
    private var chunkStart = 0
    private var coreStart = 0
    private var previousCut: ChunkCut? = null
    /** Longest non-speech run in the current window, for a forced cut that can still avoid a word. */
    private var bestPauseLength = 0
    private var bestPauseMid = -1

    val guarding: Boolean get() = guardRemaining > 0

    /** True once speech opened a turn and until it is ended or rejected. */
    val turnOpen: Boolean get() = active && !ended

    var lastFrameSpeech = false
        private set

    init {
        require(archive.capacity >= preRollSamples + config.maxWindowSamples) {
            "archive cannot hold one window"
        }
        detector.reset()
    }

    /** Consume [count] samples of [frame]; returns the events they complete (often none). */
    fun accept(frame: ShortArray, count: Int): List<SegmenterEvent> {
        if (count <= 0 || ended) return emptyList()
        if (guardRemaining > 0) {
            // Same policy as UtteranceSegmenter: guarded audio warms the
            // detector and fills pre-roll, but cannot open a turn.
            guardRemaining -= count
            detector.observe(frame, count)
            rememberPreRoll(frame, count)
            lastFrameSpeech = false
            return emptyList()
        }
        val speech = detector.isSpeech(frame, count)
        lastFrameSpeech = speech
        if (!active) {
            rememberPreRoll(frame, count)
            if (speech) beginFromPreRoll(count)
            return emptyList()
        }

        val appended = archive.append(frame, 0, count)
        val dropped = count - appended
        if (speech) {
            speechSamples += appended
            trailingSilenceSamples = 0
            lastSpeechEnd = archive.size
            pauseRunStart = -1
        } else {
            if (pauseRunStart < 0) pauseRunStart = archive.size - appended
            trailingSilenceSamples += appended
            notePause()
        }

        if (trailingSilenceSamples >= config.endSilenceSamples) {
            if (chunkIndex == 0 && speechSamples < minSpeechSamples) {
                // A blip that never reached the minimum: not a turn.
                rejectCandidate()
                return emptyList()
            }
            return finishTurn(TurnEndReason.SPEAKER_DONE, dropped)
        }

        val events = ArrayList<SegmenterEvent>(3)
        // The window ceiling is enforced before the cap so no window, the
        // final one included, ever exceeds the model contract. A null cut
        // means the first window failed the floor and the candidate is gone.
        while (archive.size - chunkStart >= config.maxWindowSamples) {
            events += forcedCut() ?: return emptyList()
        }
        if (events.isEmpty() && config.splitPolicy == SplitPolicy.ADAPTIVE && pauseCutDue()) {
            events += emitCut(pauseRunStart + config.microPauseSamples / 2, ChunkCut.PAUSE)
                ?: return emptyList()
        }
        if (archive.size - preRollOffset >= config.maxTurnSamples || dropped > 0) {
            events += finishTurn(TurnEndReason.TURN_CAP, dropped)
        }
        return events
    }

    /**
     * End the open turn now (replay end of file, explicit finish). Returns the
     * final chunk and TurnEnded, or nothing when no turn is open.
     */
    fun finish(reason: TurnEndReason = TurnEndReason.INPUT_END): List<SegmenterEvent> {
        if (!active || ended) return emptyList()
        return finishTurn(reason, 0)
    }

    /** Terminal event for a turn closed from outside (routing rejection, cancel). */
    fun abort(reason: TurnEndReason): SegmenterEvent.TurnEnded? {
        if (!active || ended) return null
        ended = true
        return SegmenterEvent.TurnEnded(reason, archive.size, lastSpeechEnd, 0)
    }

    private fun notePause() {
        val runLength = archive.size - pauseRunStart
        // A forced cut may use a short gap only well inside the owned audio,
        // so the next window still advances by more than the overlap.
        val earliest = coreStart + config.softSplitSamples / 2
        if (pauseRunStart >= maxOf(earliest, coreStart) && runLength > bestPauseLength) {
            bestPauseLength = runLength
            bestPauseMid = pauseRunStart + runLength / 2
        }
    }

    /**
     * After the soft minimum of owned audio (left context excluded), the first
     * pause of at least the micro-pause length cuts the window.
     */
    private fun pauseCutDue(): Boolean =
        pauseRunStart >= 0 && pauseRunStart >= coreStart &&
            archive.size - coreStart >= config.softSplitSamples &&
            archive.size - pauseRunStart >= config.microPauseSamples

    private fun forcedCut(): SegmenterEvent.Chunk? {
        val ceiling = chunkStart + config.maxWindowSamples
        val weakPause = bestPauseMid.takeIf {
            config.splitPolicy == SplitPolicy.ADAPTIVE &&
                bestPauseLength >= MIN_WEAK_PAUSE_SAMPLES &&
                it in (chunkStart + config.overlapSamples + 1) until ceiling
        }
        // Align the cut to the 80 ms grid relative to this window's start: the
        // next window then starts on the same CTC/RNN-T frame grid, so both
        // windows time a shared word on identical frames.
        val raw = weakPause ?: ceiling
        val grid = LongTurnConfig.OVERLAP_GRID_SAMPLES
        val cut = chunkStart + ((raw - chunkStart) / grid) * grid
        return emitCut(cut, ChunkCut.FORCED)
    }

    /**
     * Where window [chunkIndex] ending at [end] starts reading. The minimum is
     * [chunkStart]; with [LongTurnConfig.fillLeftContext] it reaches back as
     * far as the model window allows, in whole 80 ms steps from [coreStart].
     * [coreStart] is on the previous window's frame grid (the overlap half and
     * the pause context are grid multiples), so a shared word keeps identical
     * frames in both windows. The first window has no earlier audio to read.
     */
    private fun readStart(end: Int): Int {
        if (chunkIndex == 0 || !config.fillLeftContext) return chunkStart
        val grid = LongTurnConfig.OVERLAP_GRID_SAMPLES
        val room = minOf(config.maxWindowSamples - (end - coreStart), coreStart)
        return minOf(chunkStart, coreStart - (room / grid) * grid)
    }

    private fun emitCut(cut: Int, kind: ChunkCut): SegmenterEvent.Chunk? {
        val coreEnd = if (kind == ChunkCut.FORCED) cut - config.overlapSamples / 2 else cut
        val chunk = AudioChunk(
            index = chunkIndex,
            start = readStart(cut),
            end = cut,
            coreStart = coreStart,
            coreEnd = coreEnd,
            cut = kind,
            previousCut = previousCut,
        )
        if (chunkIndex == 0 && !passesFloor(chunk)) {
            rejectCandidate()
            return null
        }
        log(
            "Long-turn window ${chunk.index} cut=${kind.logValue} " +
                "samples=[${chunk.start},${chunk.end}) core=[${chunk.coreStart},${chunk.coreEnd})",
        )
        chunkIndex++
        chunkStart = if (kind == ChunkCut.FORCED) {
            cut - config.overlapSamples
        } else {
            (cut - config.pauseContextSamples).coerceAtLeast(0)
        }
        coreStart = coreEnd
        previousCut = kind
        bestPauseLength = 0
        bestPauseMid = -1
        if (pauseRunStart in 0 until coreStart) {
            // The ongoing pause began in the previous window; a pause cut in
            // the new window must wait for a pause that starts inside its core.
            pauseRunStart = coreStart
        }
        return SegmenterEvent.Chunk(chunk)
    }

    private fun finishTurn(reason: TurnEndReason, dropped: Int): List<SegmenterEvent> {
        val speechEnd = (archive.size - trailingSilenceSamples).coerceAtLeast(1)
        val events = ArrayList<SegmenterEvent>(2)
        // A final window is needed only when the turn has audio past the last
        // owned core; after a pause cut followed by silence it has none.
        val end = maxOf(speechEnd, chunkStart + 1).coerceAtMost(archive.size)
        if (chunkIndex == 0 || speechEnd > coreStart) {
            val chunk = AudioChunk(
                index = chunkIndex,
                start = readStart(end),
                end = end,
                coreStart = coreStart,
                coreEnd = end,
                cut = ChunkCut.TURN_END,
                previousCut = previousCut,
            )
            if (chunkIndex == 0 && !passesFloor(chunk)) {
                rejectCandidate()
                return emptyList()
            }
            events += SegmenterEvent.Chunk(chunk)
            chunkIndex++
        }
        ended = true
        log(
            "Long-turn ended reason=${reason.logValue} windows=$chunkIndex " +
                "archived=${archive.size} lastSpeech=$lastSpeechEnd dropped=$dropped",
        )
        events += SegmenterEvent.TurnEnded(reason, archive.size, lastSpeechEnd, dropped)
        return events
    }

    /** The legacy whole-utterance floor, applied once: it accepts or rejects the turn. */
    private fun passesFloor(chunk: AudioChunk): Boolean {
        val (rms, peak) = archive.level(chunk.start, chunk.end)
        log(
            "Captured segment samples=${chunk.length} " +
                "durationMs=${chunk.length * 1_000L / NpuEngine.SAMPLE_RATE} " +
                "rms=${"%.5f".format(rms)} peak=${"%.5f".format(peak)} cut=${chunk.cut.logValue}",
        )
        if (rms < minRms || peak < minPeak) {
            log("Discarding segment below speech floor")
            return false
        }
        return true
    }

    private fun rejectCandidate() {
        active = false
        archive.clear()
        speechSamples = 0
        trailingSilenceSamples = 0
        lastSpeechEnd = 0
        pauseRunStart = -1
        chunkIndex = 0
        chunkStart = 0
        coreStart = 0
        previousCut = null
        bestPauseLength = 0
        bestPauseMid = -1
        preRollSize = 0
        preRollWrite = 0
        preRollOffset = 0
    }

    private fun rememberPreRoll(frame: ShortArray, count: Int) {
        for (index in 0 until count) {
            preRoll[preRollWrite] = frame[index]
            preRollWrite = (preRollWrite + 1) % preRoll.size
            preRollSize = minOf(preRoll.size, preRollSize + 1)
        }
    }

    /** The onset frame is already the newest pre-roll entry, exactly as in the legacy loop. */
    private fun beginFromPreRoll(onsetSamples: Int) {
        archive.clear()
        val start = (preRollWrite - preRollSize + preRoll.size) % preRoll.size
        val ordered = ShortArray(preRollSize) { preRoll[(start + it) % preRoll.size] }
        archive.append(ordered, 0, ordered.size)
        active = true
        speechSamples = onsetSamples
        trailingSilenceSamples = 0
        lastSpeechEnd = archive.size
        pauseRunStart = -1
        preRollOffset = (archive.size - onsetSamples).coerceAtLeast(0)
    }

    private companion object {
        /** Shortest detector gap a forced cut may prefer over the bare ceiling (40 ms). */
        const val MIN_WEAK_PAUSE_SAMPLES = 640
    }
}
