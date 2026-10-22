package com.onevoice.link.core

import android.content.res.AssetManager
import android.util.Log
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.Closeable
import kotlin.math.sqrt

private const val TAG = "OneVoiceVad"

/** Which per-frame speech detector bounds live utterances; an APK-level A/B knob. */
internal enum class VadMode {
    SILERO,
    ENERGY,
    ;

    companion object {
        fun parse(value: String): VadMode =
            entries.firstOrNull { it.name == value }
                ?: throw IllegalArgumentException("vadMode must be SILERO or ENERGY (got '$value')")
    }
}

/**
 * Per-frame speech decision for the capture loop. The loop keeps ownership of
 * pre-roll, hangover and the 10 s ceiling; a detector only labels frames.
 */
internal interface SpeechActivityDetector : Closeable {
    val name: String

    /** [frame] holds [count] new 16 kHz PCM16 samples in capture order. */
    fun isSpeech(frame: ShortArray, count: Int): Boolean

    /** Detector-native score of the latest decision (RMS or probability), for tuning logs. */
    val lastScore: Float

    /** Forget recurrent state between turns so one turn cannot bias the next. */
    fun reset() {}

    override fun close() {}
}

/** Original RMS gate; any loud fan or machine is "speech" to it. */
internal class EnergySpeechDetector(private val rmsThreshold: Double) : SpeechActivityDetector {
    override val name = "energy"

    override var lastScore = 0f
        private set

    override fun isSpeech(frame: ShortArray, count: Int): Boolean {
        if (count <= 0) return false
        var sumSquares = 0.0
        for (index in 0 until count) sumSquares += frame[index].toDouble() * frame[index]
        val rms = sqrt(sumSquares / count) / 32_768.0
        lastScore = rms.toFloat()
        return rms > rmsThreshold
    }
}

/**
 * Re-blocks 20 ms capture frames into the fixed windows a neural VAD needs and
 * applies onset/offset hysteresis to its probability. Kept free of Android and
 * sherpa types so the windowing and hysteresis are unit-testable.
 */
internal class WindowedSpeechGate(
    private val windowSamples: Int,
    private val onsetProbability: Float,
    private val offsetProbability: Float,
    private val probability: (FloatArray) -> Float,
) {
    init {
        require(windowSamples > 0) { "windowSamples must be positive" }
        require(offsetProbability <= onsetProbability) { "offset must not exceed onset" }
    }

    private val window = FloatArray(windowSamples)
    private var filled = 0
    private var speaking = false

    /** Probability of the most recent complete window, for diagnostics. */
    var lastProbability = 0f
        private set

    fun accept(frame: ShortArray, count: Int): Boolean {
        for (index in 0 until count) {
            window[filled++] = frame[index] / 32_768f
            if (filled == windowSamples) {
                lastProbability = probability(window)
                speaking = if (speaking) {
                    lastProbability >= offsetProbability
                } else {
                    lastProbability >= onsetProbability
                }
                filled = 0
            }
        }
        return speaking
    }

    fun reset() {
        filled = 0
        speaking = false
        lastProbability = 0f
    }
}

/**
 * Silero VAD through the sherpa-onnx runtime already shipped for Kokoro, so it
 * adds a 0.6 MB asset and no dependency. It separates speech from stationary
 * fan/machine noise that the RMS gate cannot; it does not tell the two users
 * apart from a bystander who is talking.
 */
internal class SileroSpeechDetector private constructor(
    private val vad: Vad,
) : SpeechActivityDetector {
    override val name = "silero"

    private val gate = WindowedSpeechGate(
        windowSamples = WINDOW_SAMPLES,
        onsetProbability = ONSET_PROBABILITY,
        offsetProbability = OFFSET_PROBABILITY,
        probability = vad::compute,
    )

    override fun isSpeech(frame: ShortArray, count: Int): Boolean = gate.accept(frame, count)

    override val lastScore: Float get() = gate.lastProbability

    override fun reset() {
        gate.reset()
        vad.reset()
    }

    override fun close() = vad.release()

    companion object {
        const val ASSET_PATH = "vad/silero_vad.onnx"
        const val WINDOW_SAMPLES = 512 // 32 ms: the window Silero is trained on at 16 kHz
        const val ONSET_PROBABILITY = 0.5f
        // Lower release threshold keeps soft word endings inside the turn.
        const val OFFSET_PROBABILITY = 0.35f

        fun load(assets: AssetManager): SileroSpeechDetector {
            val vad = Vad(
                assetManager = assets,
                config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = ASSET_PATH,
                        threshold = ONSET_PROBABILITY,
                        windowSize = WINDOW_SAMPLES,
                    ),
                    sampleRate = 16_000,
                    numThreads = 1,
                ),
            )
            return SileroSpeechDetector(vad)
        }
    }
}

/** Builds the configured detector, degrading to the RMS gate if Silero cannot load. */
internal fun createSpeechDetector(
    mode: VadMode,
    assets: AssetManager,
    rmsThreshold: Double,
): SpeechActivityDetector = when (mode) {
    VadMode.ENERGY -> EnergySpeechDetector(rmsThreshold)
    VadMode.SILERO -> runCatching { SileroSpeechDetector.load(assets) }
        .onFailure { Log.e(TAG, "Silero VAD failed to load; using the RMS gate", it) }
        .getOrElse { EnergySpeechDetector(rmsThreshold) }
}
