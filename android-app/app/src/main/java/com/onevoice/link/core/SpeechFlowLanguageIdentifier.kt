package com.onevoice.link.core

import android.util.Log
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.exp

/** Result of acoustic language identification for one completed VAD segment. */
internal data class LanguageDecision(
    val direction: TranslationDirection?,
    val language: String,
    val confidence: Float,
    val margin: Float,
    val inferenceMs: Long,
) {
    val accepted: Boolean get() = direction != null
}

/**
 * SpeechFlow spoken-language identification on CPU/TFLite.
 *
 * Retained as the lightweight LID side of the DUAL_ASR/LID A/B switch. The
 * production default is dual-ASR confidence routing, so this interpreter is
 * loaded only by an APK built with `-ProutingMode=LID`.
 *
 * Three properties of this graph differ from the AmberNet one and are enforced
 * here rather than assumed:
 *
 *  * `output_1` carries raw logits, all of them negative. They are NOT
 *    log-probabilities: exp() over the vector sums to ~0.27, and treating them
 *    as such yields "confidence" above 1.0. Softmax is required, and matches
 *    upstream `predict_pb` to within rounding.
 *  * features are time-major `[1,1000,80]` and normalized over the whole
 *    utterance — see [OneVoiceNative.nativeExtractSpeechFlowFeatures].
 *  * "other" does not absorb junk on its own. Measured on the export, gaussian
 *    noise ranks `italian` at p=0.72, so the confidence/margin gate is what
 *    actually fails closed, exactly as it did for AmberNet.
 *
 * Two of the 83 graph nodes fall outside the TFLite builtin set, so the
 * select-TF-ops runtime must be on the classpath or [load] throws.
 */
internal class SpeechFlowLanguageIdentifier private constructor(
    private val interpreter: Interpreter,
    private val labels: List<String>,
    private val enIndex: Int,
    private val viIndex: Int,
    private val featureBuffer: ByteBuffer,
    private val frames: Int,
) : Closeable {
    companion object {
        private const val TAG = "OneVoiceLid"
        private const val FEATURE_BINS = 80
        private const val FRAMES = 1000
        private const val LABEL_COUNT = 14
        private const val EXPECTED_EN_INDEX = 1
        private const val EXPECTED_VI_INDEX = 12
        private const val MIN_SAMPLES = 8_000    // 500 ms; shorter turns are unreliable.
        private const val MAX_SAMPLES = 160_000  // 10 s, matching the frontend's cap.
        private const val MIN_CONFIDENCE = 0.60f
        private const val MIN_MARGIN = 0.20f

        fun load(sharedRoot: File): SpeechFlowLanguageIdentifier {
            val model = File(sharedRoot, "speechflow_langid.tflite")
            val labelsFile = File(sharedRoot, "speechflow_labels.json")
            require(model.isFile && model.length() > 0L) {
                "Missing SpeechFlow model: ${model.absolutePath}"
            }
            require(labelsFile.isFile && labelsFile.length() > 0L) {
                "Missing SpeechFlow labels: ${labelsFile.absolutePath}"
            }

            val labelsJson = JSONObject(labelsFile.readText())
            val labelsArray = labelsJson.getJSONArray("labels")
            val labels = (0 until labelsArray.length()).map(labelsArray::getString)
            val enIndex = labelsJson.getInt("enIndex")
            val viIndex = labelsJson.getInt("viIndex")
            // Upstream vocab.txt is alphabetical; a reordering there would route
            // Vietnamese speech into the English branch without any other symptom.
            require(
                labels.size == LABEL_COUNT &&
                    enIndex == EXPECTED_EN_INDEX && labels[enIndex] == "english" &&
                    viIndex == EXPECTED_VI_INDEX && labels[viIndex] == "vietnamese",
            ) { "Unexpected SpeechFlow label contract: $labels" }

            val mapped = FileChannel.open(model.toPath()).use { channel ->
                channel.map(FileChannel.MapMode.READ_ONLY, 0L, channel.size())
            }
            var interpreter: Interpreter? = null
            try {
                val ready = Interpreter(mapped, Interpreter.Options().apply {
                    // LID is the router, not the workload; one thread keeps it
                    // from competing with the HTP-feeding worker.
                    numThreads = 1
                })
                interpreter = ready
                validateContract(ready)
                val featureBuffer = ByteBuffer
                    .allocateDirect(FRAMES * FEATURE_BINS * Float.SIZE_BYTES)
                    .order(ByteOrder.nativeOrder())
                return SpeechFlowLanguageIdentifier(
                    interpreter = ready,
                    labels = labels,
                    enIndex = enIndex,
                    viIndex = viIndex,
                    featureBuffer = featureBuffer,
                    frames = FRAMES,
                )
            } catch (failure: Throwable) {
                runCatching { interpreter?.close() }
                throw failure
            }
        }

        private fun validateContract(interpreter: Interpreter) {
            require(interpreter.inputTensorCount == 2 && interpreter.outputTensorCount == 1) {
                "Unexpected SpeechFlow tensor counts: " +
                    "${interpreter.inputTensorCount} in / ${interpreter.outputTensorCount} out"
            }
            val shapes = (0 until 2).map { interpreter.getInputTensor(it).shape() }
            require(shapes.any { it.contentEquals(intArrayOf(1, FRAMES, FEATURE_BINS)) }) {
                "SpeechFlow has no [1,$FRAMES,$FEATURE_BINS] input: " +
                    shapes.joinToString { it.contentToString() }
            }
            require(shapes.any { it.contentEquals(intArrayOf(1)) }) {
                "SpeechFlow has no [1] length input: " +
                    shapes.joinToString { it.contentToString() }
            }
            val output = interpreter.getOutputTensor(0).shape()
            require(output.contentEquals(intArrayOf(1, LABEL_COUNT))) {
                "Unexpected SpeechFlow output shape: ${output.contentToString()}"
            }
        }
    }

    /**
     * TFLite matches multi-input signatures by position, and the converter is
     * free to order them independently of the Python signature, so resolve the
     * indices from the declared shapes instead of hardcoding 0/1.
     */
    private val featureInputIndex =
        (0 until 2).first {
            interpreter.getInputTensor(it).shape()
                .contentEquals(intArrayOf(1, frames, FEATURE_BINS))
        }
    private val lengthInputIndex = 1 - featureInputIndex

    private val frameCount = IntArray(1)
    private val lengthInput = IntArray(1)
    private val logits = Array(1) { FloatArray(LABEL_COUNT) }

    fun classify(samples: FloatArray): LanguageDecision {
        if (samples.size < MIN_SAMPLES) {
            return LanguageDecision(null, "too-short", 0f, 0f, 0L)
        }
        require(samples.size <= MAX_SAMPLES) { "SpeechFlow accepts at most 10 seconds" }

        val frontendError = OneVoiceNative.nativeExtractSpeechFlowFeatures(
            samples, featureBuffer, frameCount,
        )
        check(frontendError == null) { frontendError ?: "SpeechFlow frontend failed" }
        lengthInput[0] = frameCount[0].coerceIn(1, frames)

        val startedNs = System.nanoTime()
        featureBuffer.rewind()
        val inputs = arrayOfNulls<Any>(2)
        inputs[featureInputIndex] = featureBuffer
        inputs[lengthInputIndex] = lengthInput
        interpreter.runForMultipleInputsOutputs(inputs, mapOf(0 to logits))
        val inferenceMs = (System.nanoTime() - startedNs) / 1_000_000L

        val scores = logits[0]
        require(scores.all(Float::isFinite)) { "SpeechFlow returned non-finite scores" }

        var top = 0
        var second = -1
        for (index in 1 until scores.size) {
            if (scores[index] > scores[top]) {
                second = top
                top = index
            } else if (second < 0 || scores[index] > scores[second]) {
                second = index
            }
        }
        check(second >= 0) { "SpeechFlow returned no ranking" }

        // output_1 is unnormalized logits — softmax in the max-shifted form.
        // Verified against upstream predict_pb: softmax reproduces its reported
        // probability, while a bare exp() does not and can exceed 1.0.
        val maxLogit = scores[top]
        var denominator = 0.0
        for (score in scores) denominator += exp((score - maxLogit).toDouble())
        val topProbability = (1.0 / denominator).toFloat()
        val secondProbability =
            (exp((scores[second] - maxLogit).toDouble()) / denominator).toFloat()
        val margin = topProbability - secondProbability

        val direction = when (top) {
            viIndex -> TranslationDirection.VI_TO_EN
            enIndex -> TranslationDirection.EN_TO_VI
            else -> null
        }?.takeIf { topProbability >= MIN_CONFIDENCE && margin >= MIN_MARGIN }

        if (direction == null) {
            Log.d(TAG, "rejected top=${labels[top]} runnerUp=${labels[second]}")
        }
        return LanguageDecision(
            direction = direction,
            language = labels[top],
            confidence = topProbability,
            margin = margin,
            inferenceMs = inferenceMs,
        )
    }

    override fun close() {
        interpreter.close()
    }
}
