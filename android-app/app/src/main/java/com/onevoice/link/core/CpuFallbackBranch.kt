package com.onevoice.link.core

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.onevoice.link.hf.MarianTranslator
import java.io.Closeable
import java.io.File

/** Lazily loaded whole-text CPU recovery for one direction. */
internal class CpuFallbackBranch private constructor(
    private val recognizer: OfflineRecognizer,
    private val translator: MarianTranslator,
) : Closeable {
    companion object {
        private const val SAMPLE_RATE = 16_000

        fun load(directionRoot: File): CpuFallbackBranch {
            val fallbackRoot = File(directionRoot, "fallback")
            val asrRoot = File(fallbackRoot, "asr")
            val mtRoot = File(fallbackRoot, "mt")
            val model = File(asrRoot, "model.onnx")
            val tokens = File(asrRoot, "tokens.txt")
            val required = listOf(
                model,
                tokens,
                File(mtRoot, "encoder_model.onnx"),
                File(mtRoot, "decoder_model.onnx"),
                File(mtRoot, "source.spm"),
                File(mtRoot, "vocab.json"),
                File(mtRoot, "mt_config.json"),
            )
            val missing = required.filterNot { it.isFile && it.length() > 0L }
            require(missing.isEmpty()) {
                "CPU recovery assets are missing: ${missing.joinToString { it.absolutePath }}"
            }

            var recognizer: OfflineRecognizer? = null
            var translator: MarianTranslator? = null
            try {
                recognizer = OfflineRecognizer(
                    config = OfflineRecognizerConfig(
                        featConfig = FeatureConfig(
                            sampleRate = SAMPLE_RATE,
                            featureDim = 80,
                        ),
                        modelConfig = OfflineModelConfig(
                            nemo = OfflineNemoEncDecCtcModelConfig(
                                model = model.absolutePath,
                            ),
                            tokens = tokens.absolutePath,
                            numThreads = 2,
                            modelType = "nemo_ctc",
                        ),
                    ),
                )
                translator = MarianTranslator.load(mtRoot)
                return CpuFallbackBranch(checkNotNull(recognizer), checkNotNull(translator))
            } catch (failure: Throwable) {
                runCatching { translator?.close() }
                runCatching { recognizer?.release() }
                throw failure
            }
        }
    }

    fun recognize(samples: FloatArray): String = recognizeTimed(samples).transcript

    /**
     * Transcript plus sherpa's per-token timestamps, so long-turn recovery can
     * stitch overlapping windows the same way the HTP path does. There is no
     * acoustic confidence here: recovery never feeds the language router.
     */
    fun recognizeTimed(samples: FloatArray): AsrHypothesis {
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            val pieces = result.tokens
            val times = result.timestamps
            val tokens = if (pieces.size == times.size) {
                pieces.indices.map { index ->
                    AsrToken(pieces[index], (times[index] * SAMPLE_RATE).toInt())
                }
            } else {
                emptyList()
            }
            AsrHypothesis(
                transcript = result.text.trim(),
                rawLogProb = Double.NaN,
                tokenCount = pieces.size,
                normalizedLogProb = Double.NaN,
                tokens = tokens,
            )
        } finally {
            stream.release()
        }
    }

    /** Sentence by sentence, matching the HTP path so recovery output is comparable. */
    fun translate(text: String): String = translateBySentence(text, translator::translate)

    override fun close() {
        translator.close()
        recognizer.release()
    }
}
