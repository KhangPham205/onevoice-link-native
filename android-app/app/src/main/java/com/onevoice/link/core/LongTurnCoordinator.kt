package com.onevoice.link.core

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Marian output and whether any sentence hit the decoder length cap without EOS. */
internal data class MtOutput(val text: String, val outputTokens: Int, val truncated: Boolean)

/** First-window routing: a direction (or a fail-closed reason) and the winner's hypothesis. */
internal data class RoutedChunk(
    val direction: TranslationDirection?,
    /** The selected branch's DUAL_ASR hypothesis; null in LID mode, where the caller recognizes. */
    val hypothesis: AsrHypothesis?,
    val routing: RoutingDiagnostics,
)

/**
 * The model operations a long turn needs. [AutoHybridPipeline] implements it;
 * unit tests use fakes. ASR/MT calls come from one inference thread and TTS
 * from one TTS thread, so no implementation sees two concurrent calls on one
 * ASR/MT buffer set.
 */
internal interface LongTurnInference {
    /** Dual ASR (or LID) on the first window; never commits the turn prior. */
    fun routeChunk(samples: FloatArray): RoutedChunk
    fun recognize(samples: FloatArray, direction: TranslationDirection, path: ExecutionPath): AsrHypothesis
    fun translate(text: String, direction: TranslationDirection, path: ExecutionPath): MtOutput
    /** Largest Marian input of [text], per sentence, as the translator will see it. */
    fun sourceTokens(text: String, direction: TranslationDirection): Int
    /** True when the source-language ASR emits sentence punctuation. */
    fun hasPunctuation(direction: TranslationDirection): Boolean
    fun synthesize(text: String, direction: TranslationDirection): FloatArray
    fun isNpuTextQuarantined(direction: TranslationDirection): Boolean
    fun quarantineNpuText(direction: TranslationDirection)
}

/** One window after ASR, for telemetry. */
internal data class ChunkRecord(
    val chunk: AudioChunk,
    val path: ExecutionPath?,
    val asrMs: Long,
    /** Capture emitted the window -> inference started on it. */
    val queueAgeMs: Long,
    /** Audio captured but not yet recognized when this window finished. */
    val backlogMs: Long,
    val words: Int,
    val committedWords: Int,
    val revision: Int,
    val error: String? = null,
)

/** One translation unit after MT (and later TTS), for telemetry. */
internal class UnitRecord(
    val unit: TranslationUnit,
    val revision: Int,
    val path: ExecutionPath,
    val translation: String,
    val mtMs: Long,
    val outputTokens: Int,
    /** Times the unit was split because Marian hit its output cap. */
    val truncationSplits: Int,
) {
    @Volatile var ttsMs: Long = 0L
    @Volatile var audioMs: Long = 0L
}

/**
 * Everything one long turn owns. Created by the capture side when a turn
 * opens; the inference thread owns [work]; the TTS thread writes [audio];
 * playback reads it after capture closed.
 */
internal class LongTurn(
    val turnId: Long,
    val generation: Long,
    val archive: TurnArchive,
    val config: LongTurnConfig,
    val openedNs: Long,
) {
    val audio = PreparedAudioQueue(config.ttsQueueBudgetBytes)
    private val workerFinished = CountDownLatch(1)

    @Volatile var direction: TranslationDirection? = null
    @Volatile var routing: RoutingDiagnostics? = null
    @Volatile var executionPath: ExecutionPath? = null
    /** Set by inference when the turn must stop capturing now (fail closed). */
    @Volatile var abortRequest: TurnEndReason? = null
    @Volatile var error: String? = null
    @Volatile var partialFailure: Boolean = false
    @Volatile var cancelled: Boolean = false
    @Volatile var ended: SegmenterEvent.TurnEnded? = null

    /** Latest archive length, published by capture for backlog telemetry. */
    @Volatile var capturedSamples: Int = 0
    @Volatile var turnEndDetectedNs: Long = 0L
    @Volatile var captureClosedNs: Long = 0L
    @Volatile var firstPreparedNs: Long = 0L
    @Volatile var routeConfirmedNs: Long = 0L
    @Volatile var firstContentWriteNs: Long = 0L

    /** Bumped when a recovery invalidates every unit produced so far. */
    @Volatile var revision: Int = 0
    private val playbackLock = Any()
    private var playbackStarted = false
    private var recoveryStarted = false

    val chunks: MutableList<ChunkRecord> = Collections.synchronizedList(ArrayList())
    val units: MutableList<UnitRecord> = Collections.synchronizedList(ArrayList())
    @Volatile var transcript: String = ""
    @Volatile var translation: String = ""
    @Volatile var emptyUnits: Int = 0
    /** What was played, for the tagged bench waveform; null when over the replay cap. */
    @Volatile var replayAudio: FloatArray? = null

    internal var work: TurnWork? = null

    /** Playback calls this before taking its first item; false once a recovery began. */
    fun markPlaybackStarted(): Boolean = synchronized(playbackLock) {
        if (recoveryStarted) return false
        playbackStarted = true
        true
    }

    /** True when recovery may still replace everything; false means audio already played. */
    fun beginRecovery(): Boolean = synchronized(playbackLock) {
        if (playbackStarted) return false
        recoveryStarted = true
        revision++
        true
    }

    val hasPlaybackStarted: Boolean get() = synchronized(playbackLock) { playbackStarted }

    fun markWorkerFinished() = workerFinished.countDown()

    /** Inference has processed this turn's TurnEnded (or the turn was rejected and released). */
    fun awaitWorker(timeoutMs: Long): Boolean = workerFinished.await(timeoutMs, TimeUnit.MILLISECONDS)

    val workerDone: Boolean get() = workerFinished.count == 0L
}

/** Inference-thread state of one turn. */
internal class TurnWork(
    val assembler: TranscriptAssembler,
    var builder: TranslationUnitBuilder,
    val path: ExecutionPath,
) {
    val processedChunks = ArrayList<AudioChunk>()
    val deferredUnits = ArrayList<TranslationUnit>()
    val translations = ArrayList<String>()
}

/** UI/log hooks; called on the inference and TTS threads. */
internal interface LongTurnListener {
    fun onTranscript(turn: LongTurn, transcript: String, pending: String, translation: String?) {}
    fun onChunk(turn: LongTurn, record: ChunkRecord) {}
    fun onUnit(turn: LongTurn, record: UnitRecord) {}
    fun onUnitAudio(turn: LongTurn, record: UnitRecord) {}
    fun onTurnFailed(turn: LongTurn, message: String) {}
}

/**
 * Runs ASR -> merge -> unit -> MT for long turns on one inference thread and
 * TTS on one TTS thread, while capture keeps the microphone open.
 *
 * The first window is routed once (dual ASR or LID) and the direction is
 * locked for the turn; later windows run only the selected ASR, and the
 * winner's first-window hypothesis is reused rather than recognized again.
 * The turn prior is not touched here: the engine commits it once, after the
 * whole turn played (see [NpuEngine]).
 *
 * A failure of the selected NPU text path before any audio played quarantines
 * that path and rebuilds the whole turn from the archived PCM on the CPU
 * recovery branch; nothing produced earlier is kept. After audio played the
 * turn stops as a partial failure: what was heard cannot be taken back, and
 * splicing a recovery onto it would be unverified.
 */
internal class LongTurnCoordinator(
    private val inference: LongTurnInference,
    private val listener: LongTurnListener = object : LongTurnListener {},
    private val clockNs: () -> Long = System::nanoTime,
) {
    private class Event(val turn: LongTurn, val event: SegmenterEvent, val submittedNs: Long)

    private sealed interface TtsJob {
        val turn: LongTurn
        val revision: Int
        class Speak(
            override val turn: LongTurn,
            override val revision: Int,
            val record: UnitRecord,
            val direction: TranslationDirection,
        ) : TtsJob
        class EndOfTurn(override val turn: LongTurn, override val revision: Int) : TtsJob
    }

    private val events = LinkedBlockingQueue<Event>(MAX_EVENTS)
    private val ttsJobs = LinkedBlockingQueue<TtsJob>(MAX_TTS_JOBS)

    /** Capture side: never blocks; false means inference is hopelessly behind. */
    fun submit(turn: LongTurn, event: SegmenterEvent): Boolean =
        events.offer(Event(turn, event, clockNs()))

    /** Fast replay side: may wait, the producer is a file. */
    @Throws(InterruptedException::class)
    fun submitBlocking(turn: LongTurn, event: SegmenterEvent) =
        events.put(Event(turn, event, clockNs()))

    /** Drop queued work of every turn; the caller interrupts and joins the threads. */
    fun cancelAll() {
        events.clear()
        ttsJobs.clear()
    }

    /** Inference thread body; returns when interrupted. */
    fun runInferenceLoop() {
        try {
            while (!Thread.currentThread().isInterrupted) {
                val next = events.take()
                handle(next)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** TTS thread body; returns when interrupted. */
    fun runTtsLoop() {
        try {
            while (!Thread.currentThread().isInterrupted) {
                speak(ttsJobs.take())
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** Process one event inline; exposed for deterministic tests. */
    internal fun handleForTest(turn: LongTurn, event: SegmenterEvent) =
        handle(Event(turn, event, clockNs()))

    /** Run every queued TTS job inline; exposed for deterministic tests. */
    internal fun drainTtsForTest() {
        while (true) speak(ttsJobs.poll() ?: return)
    }

    private fun handle(next: Event) {
        val turn = next.turn
        when (val event = next.event) {
            is SegmenterEvent.Chunk -> {
                if (turn.cancelled || turn.error != null) return
                try {
                    processChunk(turn, event.chunk, next.submittedNs)
                } catch (interrupted: InterruptedException) {
                    throw interrupted
                } catch (failure: Throwable) {
                    failTurn(turn, "long-turn window ${event.chunk.index}: ${describe(failure)}")
                }
            }
            is SegmenterEvent.TurnEnded -> {
                turn.ended = event
                try {
                    if (!turn.cancelled && turn.error == null) finishTurn(turn)
                } catch (interrupted: InterruptedException) {
                    throw interrupted
                } catch (failure: Throwable) {
                    failTurn(turn, "long-turn end: ${describe(failure)}")
                } finally {
                    if (turn.error != null || turn.cancelled) turn.audio.fail(turn.error ?: "cancelled")
                    turn.markWorkerFinished()
                }
            }
        }
    }

    private fun processChunk(turn: LongTurn, chunk: AudioChunk, submittedNs: Long) {
        val startedNs = clockNs()
        val samples = turn.archive.window(chunk.start, chunk.end)
        var work = turn.work
        val hypothesis: AsrHypothesis
        if (work == null) {
            check(chunk.index == 0) { "window ${chunk.index} arrived before the turn was routed" }
            val routed = inference.routeChunk(samples)
            turn.routing = routed.routing
            val direction = routed.direction
            if (direction == null) {
                rejectTurn(turn, routed.routing)
                return
            }
            turn.direction = direction
            val path = if (inference.isNpuTextQuarantined(direction)) {
                ExecutionPath.CPU_FALLBACK
            } else {
                ExecutionPath.HYBRID_NPU
            }
            work = newWork(turn, direction, path)
            turn.work = work
            turn.executionPath = path
            // DUAL_ASR already decoded this window on the winning branch. A
            // quarantined text path restarts from PCM on CPU instead: NPU and
            // CPU output are never spliced into one turn.
            hypothesis = routed.hypothesis?.takeIf { path == ExecutionPath.HYBRID_NPU }
                ?: recognizeOrRecover(turn, samples, chunk) ?: return
        } else {
            hypothesis = recognizeOrRecover(turn, samples, chunk) ?: return
        }
        acceptWindow(turn, checkNotNull(turn.work), chunk, hypothesis, startedNs, submittedNs)
    }

    /** Selected-branch ASR; on an NPU failure, rebuilds the turn on CPU. Null when that took over. */
    private fun recognizeOrRecover(turn: LongTurn, samples: FloatArray, chunk: AudioChunk): AsrHypothesis? {
        val work = checkNotNull(turn.work)
        val direction = checkNotNull(turn.direction)
        return try {
            inference.recognize(samples, direction, work.path)
        } catch (failure: Exception) {
            if (work.path != ExecutionPath.HYBRID_NPU) throw failure
            recoverOnCpu(turn, "ASR window ${chunk.index}: ${describe(failure)}", pendingChunk = chunk)
            null
        }
    }

    private fun acceptWindow(
        turn: LongTurn,
        work: TurnWork,
        chunk: AudioChunk,
        hypothesis: AsrHypothesis,
        startedNs: Long,
        submittedNs: Long,
    ) {
        val words = windowWords(hypothesis, chunk)
        val fresh = work.assembler.accept(chunk, words)
        work.processedChunks += chunk
        val units = work.builder.onCommitted(fresh, chunk.cut)
        val record = ChunkRecord(
            chunk = chunk,
            path = work.path,
            asrMs = (clockNs() - startedNs) / 1_000_000L,
            queueAgeMs = ((startedNs - submittedNs) / 1_000_000L).coerceAtLeast(0L),
            backlogMs = LongTurnConfig.toMs((turn.capturedSamples - chunk.end).coerceAtLeast(0)),
            words = words.size,
            committedWords = fresh.size,
            revision = turn.revision,
        )
        turn.chunks += record
        listener.onChunk(turn, record)
        publish(turn, work)
        handleUnits(turn, work, units)
    }

    private fun handleUnits(turn: LongTurn, work: TurnWork, units: List<TranslationUnit>) {
        if (units.isEmpty()) return
        if (!turn.config.backgroundTranslation && turn.ended == null) {
            work.deferredUnits += units
            return
        }
        for (unit in units) {
            if (!translateAndQueue(turn, work, unit)) return
        }
    }

    private fun finishTurn(turn: LongTurn) {
        // A second pass runs only when an MT failure during the first one
        // rebuilt the turn on CPU: the new work then needs its own flush.
        repeat(2) {
            val work = turn.work
            if (work == null) {
                // No window was routed: every one failed the floor or none arrived.
                failTurn(turn, "Long turn ended before any window was recognized.")
                return
            }
            val revision = turn.revision
            val flushedWords = work.assembler.flush()
            val units = ArrayList<TranslationUnit>(work.deferredUnits)
            work.deferredUnits.clear()
            units += work.builder.onCommitted(flushedWords, ChunkCut.TURN_END)
            units += work.builder.flush()
            publish(turn, work)
            if (work.assembler.committedWords.isEmpty()) {
                failTurn(turn, "ASR produced an empty transcript.")
                return
            }
            for (unit in units) {
                if (!translateAndQueue(turn, work, unit)) break
            }
            if (turn.error != null || turn.cancelled) return
            if (turn.revision != revision) return@repeat
            if (work.translations.none(String::isNotBlank)) {
                failTurn(turn, "Translation produced an empty result.")
                return
            }
            if (!ttsJobs.offer(TtsJob.EndOfTurn(turn, turn.revision))) {
                failTurn(turn, "TTS job queue is full")
            }
            return
        }
        failTurn(turn, "Long turn could not settle after CPU recovery.")
    }

    /** MT one unit (splitting it if Marian's output cap cut it) and queue its TTS. */
    private fun translateAndQueue(turn: LongTurn, work: TurnWork, unit: TranslationUnit): Boolean {
        val direction = checkNotNull(turn.direction)
        val revision = turn.revision
        val startedNs = clockNs()
        val result = try {
            translateWithinCap(unit.text, direction, work.path, depth = 0)
        } catch (failure: Exception) {
            if (work.path == ExecutionPath.HYBRID_NPU && failure !is MarianInputTooLongException) {
                recoverOnCpu(turn, "MT unit ${unit.index}: ${describe(failure)}", pendingChunk = null)
            } else {
                failTurn(turn, "MT unit ${unit.index}: ${describe(failure)}")
            }
            return false
        }
        if (turn.revision != revision || turn.work !== work) return false
        val record = UnitRecord(
            unit = unit,
            revision = revision,
            path = work.path,
            translation = result.output.text,
            mtMs = (clockNs() - startedNs) / 1_000_000L,
            outputTokens = result.output.outputTokens,
            truncationSplits = result.splits,
        )
        turn.units += record
        listener.onUnit(turn, record)
        if (result.output.truncated) {
            failTurn(turn, "MT unit ${unit.index} output hit the decoder cap without EOS")
            return false
        }
        if (record.translation.isBlank()) {
            // Recorded, not hidden: the source words of this unit have no speech.
            turn.emptyUnits++
            return true
        }
        work.translations += record.translation
        publish(turn, work)
        if (!ttsJobs.offer(TtsJob.Speak(turn, revision, record, direction))) {
            failTurn(turn, "TTS job queue is full")
            return false
        }
        return true
    }

    private class Translated(val output: MtOutput, val splits: Int)

    private fun translateWithinCap(
        text: String,
        direction: TranslationDirection,
        path: ExecutionPath,
        depth: Int,
    ): Translated {
        val output = inference.translate(text, direction, path)
        val words = text.split(' ').filter(String::isNotBlank)
        if (!output.truncated || words.size < 2 || depth >= MAX_TRUNCATION_SPLITS) {
            return Translated(output, 0)
        }
        // Marian stopped at its 128-token cap without EOS: retry each half
        // rather than play a translation that silently lost its end.
        val middle = words.size / 2
        val left = translateWithinCap(words.subList(0, middle).joinToString(" "), direction, path, depth + 1)
        val right = translateWithinCap(words.subList(middle, words.size).joinToString(" "), direction, path, depth + 1)
        return Translated(
            MtOutput(
                text = listOf(left.output.text, right.output.text).filter(String::isNotBlank).joinToString(" "),
                outputTokens = left.output.outputTokens + right.output.outputTokens,
                truncated = left.output.truncated || right.output.truncated,
            ),
            splits = 1 + left.splits + right.splits,
        )
    }

    /**
     * Quarantine the NPU text path and rebuild every window recognized so far
     * on CPU, from the archived PCM. Windows that arrive later go to CPU too.
     */
    private fun recoverOnCpu(turn: LongTurn, cause: String, pendingChunk: AudioChunk?) {
        val direction = checkNotNull(turn.direction)
        val previous = checkNotNull(turn.work)
        inference.quarantineNpuText(direction)
        if (!turn.beginRecovery()) {
            turn.partialFailure = true
            failTurn(turn, "partial_failure after playback began: $cause")
            return
        }
        listener.onTurnFailed(turn, "NPU text path failed, recovering on CPU: $cause")
        val chunksToReplay = previous.processedChunks + listOfNotNull(pendingChunk)
        val work = newWork(turn, direction, ExecutionPath.CPU_FALLBACK)
        turn.work = work
        turn.executionPath = ExecutionPath.CPU_FALLBACK
        turn.audio.clearForRevision()
        for (chunk in chunksToReplay) {
            val startedNs = clockNs()
            val samples = turn.archive.window(chunk.start, chunk.end)
            val hypothesis = inference.recognize(samples, direction, ExecutionPath.CPU_FALLBACK)
            acceptWindow(turn, work, chunk, hypothesis, startedNs, startedNs)
            if (turn.error != null) return
        }
    }

    private fun newWork(turn: LongTurn, direction: TranslationDirection, path: ExecutionPath) = TurnWork(
        assembler = TranscriptAssembler(turn.config.mergeToleranceSamples),
        builder = TranslationUnitBuilder(
            maxSourceTokens = turn.config.maxUnitSourceTokens,
            hasPunctuation = inference.hasPunctuation(direction),
            tokenCount = { text -> inference.sourceTokens(text, direction) },
        ),
        path = path,
    )

    private fun rejectTurn(turn: LongTurn, routing: RoutingDiagnostics) {
        val message = "Routing rejected the utterance (${routing.reason.logValue})" +
            (routing.error?.let { ": $it" } ?: "")
        turn.error = message
        turn.abortRequest = TurnEndReason.ROUTING_REJECTED
        turn.audio.fail(message)
        listener.onTurnFailed(turn, message)
    }

    private fun failTurn(turn: LongTurn, message: String) {
        if (turn.error == null) turn.error = message
        if (turn.ended == null && turn.abortRequest == null) turn.abortRequest = TurnEndReason.FAILED
        turn.audio.fail(message)
        listener.onTurnFailed(turn, message)
    }

    private fun speak(job: TtsJob) {
        val turn = job.turn
        if (turn.cancelled || job.revision != turn.revision) return
        when (job) {
            is TtsJob.EndOfTurn -> turn.audio.finish()
            is TtsJob.Speak -> {
                val startedNs = clockNs()
                val mono = try {
                    inference.synthesize(job.record.translation, job.direction)
                } catch (failure: Exception) {
                    // A TTS or audio fault is not an ASR/MT fault: no CPU text recovery.
                    turn.partialFailure = turn.hasPlaybackStarted
                    failTurn(turn, "TTS unit ${job.record.unit.index}: ${describe(failure)}")
                    return
                }
                job.record.ttsMs = (clockNs() - startedNs) / 1_000_000L
                job.record.audioMs = mono.size * 1_000L / NpuEngine.TTS_SAMPLE_RATE
                listener.onUnitAudio(turn, job.record)
                if (mono.isEmpty()) {
                    turn.emptyUnits++
                    return
                }
                if (job.revision != turn.revision) return
                val readyNs = clockNs()
                val accepted = turn.audio.put(
                    PreparedAudio(job.record.unit.index, mono, NpuEngine.TTS_SAMPLE_RATE, readyNs, job.revision),
                )
                if (accepted && turn.firstPreparedNs == 0L) turn.firstPreparedNs = readyNs
            }
        }
    }

    private fun windowWords(hypothesis: AsrHypothesis, chunk: AudioChunk): List<AsrWord> {
        if (hypothesis.tokens.isNotEmpty()) return wordsFromTokens(hypothesis.tokens, chunk.start)
        // No alignment (e.g. a CPU result without timestamps): spread the words
        // over the window. Pause-cut windows concatenate correctly; overlap
        // merging degrades to the midpoint rule on these estimates.
        val words = hypothesis.transcript.split(' ').filter(String::isNotBlank)
        if (words.isEmpty()) return emptyList()
        val step = chunk.length / words.size
        return words.mapIndexed { index, text -> AsrWord(text, chunk.start + index * step) }
    }

    private fun publish(turn: LongTurn, work: TurnWork) {
        val transcript = transcriptOf(work.assembler.committedWords)
        val pending = transcriptOf(work.assembler.pendingWords)
        val translation = work.translations.joinToString(" ")
        turn.transcript = listOf(transcript, pending).filter(String::isNotEmpty).joinToString(" ")
        turn.translation = translation
        listener.onTranscript(turn, transcript, pending, translation.ifEmpty { null })
    }

    private fun describe(failure: Throwable): String = failure.message ?: failure.javaClass.simpleName

    private companion object {
        /** Windows plus the terminal event of one turn; 120 s of 7 s windows is ~20. */
        const val MAX_EVENTS = 128
        const val MAX_TTS_JOBS = 512
        const val MAX_TRUNCATION_SPLITS = 3
    }
}
