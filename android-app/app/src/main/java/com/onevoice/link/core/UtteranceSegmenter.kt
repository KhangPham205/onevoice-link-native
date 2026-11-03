package com.onevoice.link.core

import kotlin.math.abs
import kotlin.math.sqrt

/** One bounded live utterance: 16 kHz mono float PCM, and whether the ceiling cut it. */
internal data class BoundedUtterance(
    val samples: FloatArray,
    val forcedBoundary: Boolean,
)

/**
 * Bounds one live utterance from 20 ms PCM16 capture frames: echo guard,
 * pre-roll, onset, hangover and the 10 s ceiling. It owns no AudioRecord, so
 * the capture policy is unit-testable; [NpuEngine] owns the device reads.
 *
 * The phone route's echo guard follows speech-core's post-playback guard: the
 * guarded audio still runs through the detector (recurrent state warm, latch
 * held closed) and into the pre-roll ring, it just cannot open a turn. Someone
 * who answers inside the guard therefore keeps the head of their utterance.
 * Discarding it, as this loop used to, cost up to 300 ms of onset; speech-core
 * hit the same bug ("stop playing music" reached STT as "playing music").
 */
internal class UtteranceSegmenter(
    private val detector: SpeechActivityDetector,
    guardSamples: Int = 0,
    private val log: (String) -> Unit = {},
    preRollSamples: Int = NpuEngine.PRE_ROLL_SAMPLES,
    private val maxSegmentSamples: Int = NpuEngine.MAX_SEGMENT_SAMPLES,
    private val minSpeechFrames: Int = NpuEngine.MIN_SPEECH_FRAMES,
    private val endSilenceFrames: Int = NpuEngine.END_SILENCE_FRAMES,
    private val minRms: Float = NpuEngine.SEGMENT_MIN_RMS,
    private val minPeak: Float = NpuEngine.SEGMENT_MIN_PEAK,
) {
    private var guardRemaining = guardSamples
    private val current = FloatArray(maxSegmentSamples)
    private val preRoll = FloatArray(preRollSamples)
    private var currentSize = 0
    private var speechFrames = 0
    private var silenceFrames = 0
    private var trailingSilenceSamples = 0
    private var preRollSize = 0
    private var preRollWrite = 0

    /** True while capture is still inside the echo guard. */
    val guarding: Boolean get() = guardRemaining > 0

    /** Detector decision for the latest live frame; false inside the guard. */
    var lastFrameSpeech = false
        private set

    init {
        // Reset before the guard, not after it: the guard exists to warm state.
        detector.reset()
    }

    /** Consume [count] samples of [frame]; returns the utterance once it is bounded. */
    fun accept(frame: ShortArray, count: Int): BoundedUtterance? {
        if (count <= 0) return null
        if (guardRemaining > 0) {
            // A frame straddling the guard edge counts as guard; the 300 ms
            // guard is exactly fifteen 20 ms reads.
            guardRemaining -= count
            detector.observe(frame, count)
            rememberPreRoll(frame, count)
            lastFrameSpeech = false
            return null
        }
        val speech = detector.isSpeech(frame, count)
        lastFrameSpeech = speech
        if (speechFrames == 0) {
            rememberPreRoll(frame, count)
            if (speech) {
                beginFromPreRoll()
                speechFrames = 1
                silenceFrames = 0
            }
            return null
        }

        val append = minOf(count, current.size - currentSize)
        for (index in 0 until append) {
            current[currentSize + index] = frame[index] / 32_768f
        }
        currentSize += append
        if (speech) {
            speechFrames++
            silenceFrames = 0
            trailingSilenceSamples = 0
        } else {
            silenceFrames++
            trailingSilenceSamples += append
        }
        val endedBySilence = speechFrames >= minSpeechFrames &&
            silenceFrames >= endSilenceFrames
        val rejectedBySilence = speechFrames < minSpeechFrames &&
            silenceFrames >= endSilenceFrames
        if (endedBySilence) {
            val candidate = completed(
                (currentSize - trailingSilenceSamples).coerceAtLeast(1),
                forcedBoundary = false,
            )
            if (candidate != null) return candidate
            resetCandidate()
        } else if (rejectedBySilence) {
            resetCandidate()
        } else if (currentSize >= maxSegmentSamples) {
            return completed(maxSegmentSamples, forcedBoundary = true)
        }
        return null
    }

    private fun resetCandidate() {
        currentSize = 0
        speechFrames = 0
        silenceFrames = 0
        trailingSilenceSamples = 0
        preRollSize = 0
        preRollWrite = 0
    }

    private fun rememberPreRoll(frame: ShortArray, count: Int) {
        for (index in 0 until count) {
            preRoll[preRollWrite] = frame[index] / 32_768f
            preRollWrite = (preRollWrite + 1) % preRoll.size
            preRollSize = minOf(preRoll.size, preRollSize + 1)
        }
    }

    private fun beginFromPreRoll() {
        val start = (preRollWrite - preRollSize + preRoll.size) % preRoll.size
        repeat(preRollSize) { index ->
            current[index] = preRoll[(start + index) % preRoll.size]
        }
        currentSize = preRollSize
    }

    private fun completed(size: Int, forcedBoundary: Boolean): BoundedUtterance? {
        if (size <= 0) return null
        val segment = current.copyOf(size)
        var sumSquares = 0.0
        var peak = 0f
        segment.forEach { sample ->
            sumSquares += sample.toDouble() * sample
            peak = maxOf(peak, abs(sample))
        }
        val rms = sqrt(sumSquares / size).toFloat()
        val crest = peak / (rms + 1e-9f)
        log(
            "Captured segment samples=$size durationMs=${size * 1_000L / NpuEngine.SAMPLE_RATE} " +
                "rms=${"%.5f".format(rms)} peak=${"%.5f".format(peak)} " +
                "crest=${"%.1f".format(crest)} forced=$forcedBoundary",
        )
        if (rms < minRms || peak < minPeak) {
            log("Discarding segment below speech floor")
            return null
        }
        return BoundedUtterance(segment, forcedBoundary)
    }
}
