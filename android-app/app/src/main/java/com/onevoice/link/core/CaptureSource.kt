package com.onevoice.link.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One open capture of one turn: the microphone and its route, or a replay
 * file. The capture loop and both segmenters see only this, so a replayed
 * recording runs the exact production capture/VAD/coordinator path.
 */
internal interface TurnCapture {
    val route: AudioRouteKind
    val communicationAddress: String?
    val communicationName: String
    val setupMs: Long
    /** Post-playback echo guard for this input, in samples. */
    val guardSamples: Int

    /** Blocking read of up to `frame.size` samples; -1 once the input is exhausted. */
    fun read(frame: ShortArray): Int

    /** Release the input (and an app-owned SCO route). Idempotent. */
    fun close()

    val scoCloseMs: Long
    val scoClosedNs: Long

    /** Largest observed backlog between captured and read frames (live only). */
    val maxReadLagMs: Long get() = 0L
    /** Reads at which that backlog reached the record buffer size: likely overruns. */
    val overrunSuspected: Int get() = 0
}

/** Opens one [TurnCapture] per turn. */
internal interface CaptureProvider {
    /** `microphone`, `replay_paced` or `replay_fast`; logged with every turn. */
    val inputMode: String
    /** Replay rows go to the tagged bench log, never the live session log. */
    val replayTag: String?
    /** A file producer may wait for inference; a microphone never does. */
    val blockingSubmit: Boolean
    /** True once a replay has no more input; a microphone never runs out. */
    val exhausted: Boolean

    /** Null when the input is exhausted. */
    fun open(turnId: Long): TurnCapture?
}

/**
 * Feeds a 16 kHz mono recording through the live capture path.
 *
 * Paced mode follows the wall clock: audio arrives every frame period, and
 * audio that "plays" while no turn is capturing (inference, playback) is
 * skipped, exactly like speech said while the real microphone is closed. That
 * makes a paced LEGACY replay lose what B0 loses live. Fast mode never skips,
 * for deterministic coverage checks; its latencies are meaningless.
 *
 * The file is followed by [tailSilenceSamples] of zeros so the last turn ends
 * through the normal end-of-turn silence rather than a truncated read.
 */
internal class ReplayCaptureProvider(
    private val pcm: ShortArray,
    private val paced: Boolean,
    override val replayTag: String?,
    private val route: AudioRouteKind,
    private val tailSilenceSamples: Int,
    private val clockNs: () -> Long = System::nanoTime,
    private val sleepNs: (Long) -> Unit = { ns -> if (ns > 0) Thread.sleep(ns / 1_000_000L, (ns % 1_000_000L).toInt()) },
) : CaptureProvider {
    override val inputMode: String = if (paced) "replay_paced" else "replay_fast"
    override val blockingSubmit: Boolean = !paced

    private val total = pcm.size + tailSilenceSamples
    private var position = 0
    private var startedNs = 0L

    /** Samples skipped while no turn was capturing (paced only). */
    var skippedWhileClosed: Long = 0L
        private set
    var turnsOpened: Int = 0
        private set

    override var exhausted: Boolean = false
        private set

    val totalSamples: Int get() = total

    override fun open(turnId: Long): TurnCapture? {
        if (paced) {
            val now = clockNs()
            if (startedNs == 0L) {
                startedNs = now
            } else {
                val wall = ((now - startedNs) * SAMPLE_RATE / 1_000_000_000L).coerceAtMost(total.toLong()).toInt()
                if (wall > position) {
                    skippedWhileClosed += wall - position
                    position = wall
                }
            }
        }
        if (position >= total) {
            exhausted = true
            return null
        }
        turnsOpened++
        return ReplayCapture()
    }

    private inner class ReplayCapture : TurnCapture {
        override val route: AudioRouteKind = this@ReplayCaptureProvider.route
        override val communicationAddress: String? = null
        override val communicationName: String = "replay"
        override val setupMs: Long = 0L
        override val guardSamples: Int = 0
        override var scoCloseMs: Long = 0L
            private set
        override var scoClosedNs: Long = 0L
            private set

        override fun read(frame: ShortArray): Int {
            if (position >= total) {
                exhausted = true
                return -1
            }
            val count = minOf(frame.size, total - position)
            for (index in 0 until count) {
                val at = position + index
                frame[index] = if (at < pcm.size) pcm[at] else 0
            }
            position += count
            if (paced) {
                val dueNs = startedNs + position.toLong() * 1_000_000_000L / SAMPLE_RATE
                sleepNs(dueNs - clockNs())
            }
            return count
        }

        override fun close() {
            if (scoClosedNs == 0L) scoClosedNs = clockNs()
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
    }
}

/**
 * Replay input as PCM16: a RIFF/WAVE file (16-bit PCM, mono, 16 kHz) or the
 * bench fixtures' raw little-endian float32 (`.f32`). Anything else is
 * rejected instead of resampled, so a replay always states its input exactly.
 */
internal fun readReplayPcm(file: File): ShortArray {
    val bytes = file.readBytes()
    if (bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
        String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE"
    ) {
        return parseWavPcm16(bytes)
    }
    require(file.name.endsWith(".f32")) { "replay input must be a 16 kHz mono PCM16 .wav or a .f32 fixture" }
    val floats = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
    return ShortArray(floats.remaining()) { (floats.get(it).coerceIn(-1f, 1f) * 32_767f).toInt().toShort() }
}

internal fun parseWavPcm16(bytes: ByteArray): ShortArray {
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    var offset = 12
    var format: Int? = null
    var channels = 0
    var rate = 0
    var bits = 0
    while (offset + 8 <= bytes.size) {
        val id = String(bytes, offset, 4, Charsets.US_ASCII)
        val size = buffer.getInt(offset + 4)
        val body = offset + 8
        require(size >= 0 && body + size <= bytes.size || id == "data") { "corrupt WAV chunk $id" }
        when (id) {
            "fmt " -> {
                format = buffer.getShort(body).toInt() and 0xffff
                channels = buffer.getShort(body + 2).toInt()
                rate = buffer.getInt(body + 4)
                bits = buffer.getShort(body + 14).toInt()
            }
            "data" -> {
                require(format == 1 && channels == 1 && rate == 16_000 && bits == 16) {
                    "replay WAV must be PCM16 mono 16 kHz (format=$format channels=$channels rate=$rate bits=$bits)"
                }
                val length = minOf(size, bytes.size - body) / 2
                return ShortArray(length) { buffer.getShort(body + it * 2) }
            }
        }
        offset = body + size + (size and 1)
    }
    throw IllegalArgumentException("WAV file has no data chunk")
}
