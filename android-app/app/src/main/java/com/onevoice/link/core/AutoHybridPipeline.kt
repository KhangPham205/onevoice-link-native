package com.onevoice.link.core

import android.util.Log
import java.io.Closeable
import java.io.File
import java.util.EnumMap

internal data class HybridTextResult(
    val transcript: String,
    val translation: String,
    val executionPath: ExecutionPath,
    val asrMs: Long,
    val mtMs: Long,
)

/**
 * Per-utterance auto router with both NPU ASR/MT directions resident.
 *
 * There is still exactly one microphone owner in [NpuEngine]. LID makes a
 * decision only after VAD closes an utterance; the selected branch then runs
 * its QNN RNN-T encoder and Marian contexts, followed by whole-utterance CPU
 * Kokoro. No partial result is ever spliced across directions or backends.
 */
internal class AutoHybridPipeline private constructor(
    private val modelRoot: File,
    private val languageIdentifier: SpeechFlowLanguageIdentifier,
    private val viToEn: NpuPipeline,
    private val enToVi: NpuPipeline,
) : Closeable {
    companion object {
        private const val TAG = "OneVoiceAutoHybrid"

        fun load(modelRoot: File, manifest: NpuManifest): AutoHybridPipeline {
            val viSpec = requireNotNull(
                manifest.directions.firstOrNull { it.id == "vi-en" },
            ) { "Manifest has no vi-en direction" }
            val enSpec = requireNotNull(
                manifest.directions.firstOrNull { it.id == "en-vi" },
            ) { "Manifest has no en-vi direction" }
            val viRoot = File(modelRoot, "vi-en")
            val enRoot = File(modelRoot, "en-vi")
            val sharedRoot = File(modelRoot, "shared")

            var lid: SpeechFlowLanguageIdentifier? = null
            var vi: NpuPipeline? = null
            var en: NpuPipeline? = null
            try {
                lid = SpeechFlowLanguageIdentifier.load(sharedRoot)
                vi = NpuPipeline.load(
                    directionRoot = viRoot,
                    asrStage = viSpec.stages.single { it.stage == "asr" },
                    stagePrefix = "vi-en",
                    asrArtifactPrefix = "asr_gipformer",
                    useCpuTts = true,
                    cpuTtsModel = File(viRoot, "tts_cpu.onnx"),
                )
                en = NpuPipeline.load(
                    directionRoot = enRoot,
                    asrStage = enSpec.stages.single { it.stage == "asr" },
                    stagePrefix = "en-vi",
                    asrArtifactPrefix = "asr_zipformer",
                    useCpuTts = true,
                    cpuTtsModel = File(enRoot, "tts_cpu.onnx"),
                )
                return AutoHybridPipeline(modelRoot, lid, vi, en)
            } catch (failure: Throwable) {
                runCatching { en?.close() }
                runCatching { vi?.close() }
                runCatching { lid?.close() }
                throw failure
            }
        }
    }

    fun detect(samples: FloatArray): LanguageDecision = languageIdentifier.classify(samples)

    private val cpuFallbacks = EnumMap<TranslationDirection, CpuFallbackBranch>(
        TranslationDirection::class.java,
    )
    private val disabledNpuDirections = HashSet<TranslationDirection>()

    /**
     * Run ASR+MT atomically. If the primary branch throws, discard every
     * partial tensor/text result and reprocess the original PCM with a lazily
     * loaded CPU branch. A failed direction stays quarantined for this engine
     * lifetime so subsequent utterances do not repeatedly hit a broken HTP.
     */
    fun recognizeAndTranslate(
        samples: FloatArray,
        direction: TranslationDirection,
    ): HybridTextResult {
        var primaryFailure: Throwable? = null
        if (direction !in disabledNpuDirections) {
            try {
                val asrStarted = System.nanoTime()
                val transcript = branch(direction).recognize(samples).trim()
                val asrMs = (System.nanoTime() - asrStarted) / 1_000_000L
                if (transcript.isEmpty()) {
                    return HybridTextResult("", "", ExecutionPath.HYBRID_NPU, asrMs, 0L)
                }
                val mtStarted = System.nanoTime()
                val translation = branch(direction).translate(transcript).trim()
                val mtMs = (System.nanoTime() - mtStarted) / 1_000_000L
                return HybridTextResult(
                    transcript,
                    translation,
                    ExecutionPath.HYBRID_NPU,
                    asrMs,
                    mtMs,
                )
            } catch (failure: Exception) {
                primaryFailure = failure
                disabledNpuDirections += direction
                Log.e(
                    TAG,
                    "Primary $direction ASR/MT failed; retrying original PCM on CPU",
                    failure,
                )
            }
        }

        return try {
            val fallback = cpuFallbacks[direction] ?: CpuFallbackBranch.load(
                File(modelRoot, direction.manifestId),
            ).also { cpuFallbacks[direction] = it }
            val asrStarted = System.nanoTime()
            val transcript = fallback.recognize(samples)
            val asrMs = (System.nanoTime() - asrStarted) / 1_000_000L
            if (transcript.isEmpty()) {
                HybridTextResult("", "", ExecutionPath.CPU_FALLBACK, asrMs, 0L)
            } else {
                val mtStarted = System.nanoTime()
                val translation = fallback.translate(transcript)
                HybridTextResult(
                    transcript,
                    translation,
                    ExecutionPath.CPU_FALLBACK,
                    asrMs,
                    (System.nanoTime() - mtStarted) / 1_000_000L,
                )
            }
        } catch (fallbackFailure: Exception) {
            primaryFailure?.let(fallbackFailure::addSuppressed)
            throw IllegalStateException(
                "Both Hybrid NPU and CPU recovery failed for $direction: " +
                    (fallbackFailure.message ?: fallbackFailure.javaClass.simpleName),
                fallbackFailure,
            )
        }
    }

    fun synthesize(text: String, direction: TranslationDirection): FloatArray =
        branch(direction).synthesize(text)

    /** Tokens the Marian decoder emitted for the last translate on [direction]. */
    fun lastMtOutputTokens(direction: TranslationDirection): Int =
        branch(direction).lastMtOutputTokens

    internal fun translateForSmoke(text: String, direction: TranslationDirection): String =
        branch(direction).translate(text)

    private fun branch(direction: TranslationDirection): NpuPipeline = when (direction) {
        TranslationDirection.VI_TO_EN -> viToEn
        TranslationDirection.EN_TO_VI -> enToVi
    }

    override fun close() {
        // Reverse the expensive construction order. The ORT environment is a
        // process singleton and is intentionally not closed here.
        cpuFallbacks.values.forEach { runCatching { it.close() } }
        cpuFallbacks.clear()
        runCatching { enToVi.close() }
        runCatching { viToEn.close() }
        runCatching { languageIdentifier.close() }
    }
}
