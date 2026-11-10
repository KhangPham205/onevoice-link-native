package com.onevoice.link.core

import android.util.Log
import com.onevoice.link.BuildConfig
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.util.EnumMap
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal data class HybridTextResult(
    val transcript: String,
    val translation: String,
    val executionPath: ExecutionPath,
    val asrMs: Long,
    val mtMs: Long,
)

internal data class TimedAsrHypothesis(
    val hypothesis: AsrHypothesis,
    val elapsedMs: Long,
)

internal data class RoutingDiagnostics(
    val mode: RoutingMode,
    val vi: TimedAsrHypothesis? = null,
    val en: TimedAsrHypothesis? = null,
    val frontendMs: Long = 0L,
    /** Wall time for both branch tasks; it is not the sum of their durations. */
    val dualAsrWallMs: Long = 0L,
    val priorExpected: TranslationDirection? = null,
    val decision: TranslationDirection? = null,
    val reason: RoutingDecisionReason,
    val normalizedMargin: Double? = null,
    val lidLanguage: String? = null,
    val lidConfidence: Float? = null,
    val lidMargin: Float? = null,
    val lidMs: Long? = null,
    val error: String? = null,
)

internal data class RoutedHybridTextResult(
    val direction: TranslationDirection?,
    val text: HybridTextResult?,
    val routing: RoutingDiagnostics,
)

/**
 * Automatic Hybrid pipeline with both NPU ASR/MT directions resident.
 *
 * DUAL_ASR computes the shared Kaldi frontend once, submits both ASR branches,
 * and compares their average emitted-token log probabilities. QNN graph calls
 * remain serialized by the native runtime's lifecycle/profile mutex; the two
 * host tasks still overlap one branch's CPU RNN-T decoder with the other HTP
 * encoder. LID remains an explicit build-time A/B mode and loads SpeechFlow
 * only when selected.
 */
internal class AutoHybridPipeline private constructor(
    private val modelRoot: File,
    private val viToEn: NpuPipeline,
    private val enToVi: NpuPipeline,
    private val routingConfig: RoutingConfig,
    private val languageIdentifier: SpeechFlowLanguageIdentifier?,
) : Closeable, LongTurnInference {
    companion object {
        private const val TAG = "OneVoiceAutoHybrid"
        private val ASR_THREAD_ID = AtomicInteger(0)

        /**
         * Host-asset prefix from the deployed context name, so swapping a
         * direction's ASR is a manifest change: `vi-en/asr_gipformer_encoder.bin`
         * -> `asr_gipformer`, `en-vi/asr_parakeet_ctc.bin` -> `asr_parakeet`.
         */
        internal fun asrArtifactPrefix(stage: StageSpec): String =
            File(stage.file).name.removeSuffix(".bin").removeSuffix("_encoder").removeSuffix("_ctc")

        fun load(
            modelRoot: File,
            manifest: NpuManifest,
            routingConfig: RoutingConfig,
        ): AutoHybridPipeline {
            val viSpec = requireNotNull(
                manifest.directions.firstOrNull { it.id == "vi-en" },
            ) { "Manifest has no vi-en direction" }
            val enSpec = requireNotNull(
                manifest.directions.firstOrNull { it.id == "en-vi" },
            ) { "Manifest has no en-vi direction" }
            val viRoot = File(modelRoot, "vi-en")
            val enRoot = File(modelRoot, "en-vi")
            var vi: NpuPipeline? = null
            var en: NpuPipeline? = null
            var lid: SpeechFlowLanguageIdentifier? = null
            val viAsr = viSpec.stages.single { it.stage == "asr" }
            val enAsr = enSpec.stages.single { it.stage == "asr" }
            try {
                vi = NpuPipeline.load(
                    directionRoot = viRoot,
                    asrStage = viAsr,
                    stagePrefix = "vi-en",
                    asrArtifactPrefix = asrArtifactPrefix(viAsr),
                    useCpuTts = true,
                    cpuTtsModel = File(viRoot, "tts_cpu.onnx"),
                )
                en = NpuPipeline.load(
                    directionRoot = enRoot,
                    asrStage = enAsr,
                    stagePrefix = "en-vi",
                    asrArtifactPrefix = asrArtifactPrefix(enAsr),
                    useCpuTts = true,
                    cpuTtsModel = File(enRoot, "tts_cpu.onnx"),
                    // Vietnamese target speech: Kokoro-VI or Supertonic-3 (A/B).
                    cpuTtsEngine = CpuTtsEngine.parse(BuildConfig.VI_TTS_ENGINE),
                )
                if (routingConfig.mode == RoutingMode.LID) {
                    lid = SpeechFlowLanguageIdentifier.load(File(modelRoot, "shared"))
                }
                return AutoHybridPipeline(modelRoot, vi, en, routingConfig, lid)
            } catch (failure: Throwable) {
                runCatching { lid?.close() }
                runCatching { en?.close() }
                runCatching { vi?.close() }
                throw failure
            }
        }
    }

    private val cpuFallbacks = EnumMap<TranslationDirection, CpuFallbackBranch>(
        TranslationDirection::class.java,
    )
    /** A failed Marian text path does not disable that branch's routing ASR. */
    private val disabledNpuTextDirections = HashSet<TranslationDirection>()
    private val sharedRnntFeatures: ByteBuffer = NpuPipeline.allocateSharedRnntFeatures()
    private val turnRouter = DualAsrTurnRouter(routingConfig)
    private val asrExecutor: ExecutorService = Executors.newFixedThreadPool(2) { task ->
        Thread(task, "onevoice-dual-asr-${ASR_THREAD_ID.incrementAndGet()}").apply {
            isDaemon = true
        }
    }

    val routingMode: RoutingMode get() = routingConfig.mode

    /** Production automatic routing entry point. */
    fun routeAndTranslate(
        samples: FloatArray,
        forcedBoundary: Boolean,
    ): RoutedHybridTextResult = when (routingConfig.mode) {
        RoutingMode.DUAL_ASR -> routeWithDualAsr(samples, forcedBoundary)
        RoutingMode.LID -> routeWithLid(samples, forcedBoundary)
    }

    private fun routeWithDualAsr(
        samples: FloatArray,
        forcedBoundary: Boolean,
    ): RoutedHybridTextResult {
        val routed = dualAsrRoute(samples)
        val direction = routed.direction ?: return RoutedHybridTextResult(
            direction = null,
            text = null,
            routing = routed.routing,
        )
        val text = translateSelected(
            samples = samples,
            direction = direction,
            transcript = checkNotNull(routed.hypothesis).transcript,
            // One frontend plus the dual task wall clock, never VI+EN summed.
            primaryAsrMs = routed.routing.frontendMs + routed.routing.dualAsrWallMs,
        )
        if (text.translation.isNotBlank()) turnRouter.commit(direction, forcedBoundary)
        return RoutedHybridTextResult(direction, text, routed.routing)
    }

    /**
     * Dual-ASR decision for one bounded window, without translating or
     * committing the turn prior. A selected direction always comes with a
     * non-blank winning hypothesis.
     */
    private fun dualAsrRoute(samples: FloatArray): RoutedChunk {
        val frontendStarted = System.nanoTime()
        val validFeatureFrames = try {
            NpuPipeline.prepareSharedRnntFeatures(samples, sharedRnntFeatures)
        } catch (failure: Throwable) {
            return routingFailure(
                mode = RoutingMode.DUAL_ASR,
                reason = RoutingDecisionReason.ASR_FAILURE,
                error = "shared frontend: ${failure.message ?: failure.javaClass.simpleName}",
            ).let { RoutedChunk(null, null, it.routing) }
        }
        val frontendMs = elapsedMs(frontendStarted)

        val dualStarted = System.nanoTime()
        val viFuture = asrExecutor.submit(asrTask(viToEn, samples, validFeatureFrames))
        val enFuture = asrExecutor.submit(asrTask(enToVi, samples, validFeatureFrames))
        val (viOutcome, enOutcome) = try {
            await(viFuture) to await(enFuture)
        } catch (interrupted: InterruptedException) {
            // Future.cancel(true) can mark a task as cancelled before a native
            // graphExecute/ORT decode has actually returned. A subsequent
            // stop -> start could then reuse the same branch buffers while the
            // old task is still writing them. Let both bounded ASR calls reach
            // a real terminal state before propagating the interruption.
            awaitCompletionUninterruptibly(viFuture)
            awaitCompletionUninterruptibly(enFuture)
            Thread.currentThread().interrupt()
            throw interrupted
        }
        val dualWallMs = elapsedMs(dualStarted)
        val prior = turnRouter.expectedNext()

        if (viOutcome.failure != null || enOutcome.failure != null) {
            val errors = buildList {
                viOutcome.failure?.let { add("vi=${it.message ?: it.javaClass.simpleName}") }
                enOutcome.failure?.let { add("en=${it.message ?: it.javaClass.simpleName}") }
            }.joinToString("; ")
            return RoutedChunk(
                direction = null,
                hypothesis = null,
                routing = RoutingDiagnostics(
                    mode = RoutingMode.DUAL_ASR,
                    vi = viOutcome.value,
                    en = enOutcome.value,
                    frontendMs = frontendMs,
                    dualAsrWallMs = dualWallMs,
                    priorExpected = prior,
                    reason = RoutingDecisionReason.ASR_FAILURE,
                    error = errors,
                ),
            )
        }

        val vi = checkNotNull(viOutcome.value)
        val en = checkNotNull(enOutcome.value)
        val decision = turnRouter.decide(
            vi.hypothesis.normalizedLogProb,
            en.hypothesis.normalizedLogProb,
            CoverageEvidence(
                viTokens = vi.hypothesis.tokenCount,
                enTokens = en.hypothesis.tokenCount,
                // The bounded window both branches decoded, as the routing row's audioMs.
                audioSeconds = samples.size.toDouble() / NpuEngine.SAMPLE_RATE,
            ),
        )
        val diagnostics = RoutingDiagnostics(
            mode = RoutingMode.DUAL_ASR,
            vi = vi,
            en = en,
            frontendMs = frontendMs,
            dualAsrWallMs = dualWallMs,
            priorExpected = decision.priorExpected,
            decision = decision.direction,
            reason = decision.reason,
            normalizedMargin = decision.normalizedMargin,
        )
        val direction = decision.direction ?: return RoutedChunk(
            direction = null,
            hypothesis = null,
            routing = diagnostics,
        )
        val selected = when (direction) {
            TranslationDirection.VI_TO_EN -> vi.hypothesis
            TranslationDirection.EN_TO_VI -> en.hypothesis
        }
        if (selected.transcript.isBlank()) {
            return RoutedChunk(null, null, diagnostics.copy(
                decision = null,
                reason = RoutingDecisionReason.FLOOR,
                error = "selected ASR transcript is empty",
            ))
        }
        return RoutedChunk(direction, selected, diagnostics)
    }

    private fun routeWithLid(
        samples: FloatArray,
        forcedBoundary: Boolean,
    ): RoutedHybridTextResult {
        val routed = lidRoute(samples)
        val direction = routed.direction
            ?: return RoutedHybridTextResult(null, null, routed.routing)
        val text = recognizeAndTranslate(samples, direction)
        if (text.translation.isNotBlank()) turnRouter.commit(direction, forcedBoundary)
        return RoutedHybridTextResult(direction, text, routed.routing)
    }

    /** SpeechFlow decision only; the caller runs the selected ASR. */
    private fun lidRoute(samples: FloatArray): RoutedChunk {
        val decision = try {
            checkNotNull(languageIdentifier) { "SpeechFlow is not loaded in LID mode" }
                .classify(samples)
        } catch (failure: Throwable) {
            return routingFailure(
                mode = RoutingMode.LID,
                reason = RoutingDecisionReason.LID_REJECTED,
                error = failure.message ?: failure.javaClass.simpleName,
            ).let { RoutedChunk(null, null, it.routing) }
        }
        val direction = decision.direction
        val diagnostics = RoutingDiagnostics(
            mode = RoutingMode.LID,
            priorExpected = turnRouter.expectedNext(),
            decision = direction,
            reason = if (direction == null) {
                RoutingDecisionReason.LID_REJECTED
            } else {
                RoutingDecisionReason.LID_ACCEPTED
            },
            lidLanguage = decision.language,
            lidConfidence = decision.confidence,
            lidMargin = decision.margin,
            lidMs = decision.inferenceMs,
        )
        return RoutedChunk(direction, null, diagnostics)
    }

    // ------------------------------------------------------------ long turns

    override fun routeChunk(samples: FloatArray): RoutedChunk = when (routingConfig.mode) {
        RoutingMode.DUAL_ASR -> dualAsrRoute(samples)
        RoutingMode.LID -> lidRoute(samples)
    }

    override fun recognize(
        samples: FloatArray,
        direction: TranslationDirection,
        path: ExecutionPath,
    ): AsrHypothesis = when (path) {
        ExecutionPath.HYBRID_NPU -> branch(direction).recognizeScored(samples)
        ExecutionPath.CPU_FALLBACK -> cpuFallback(direction).recognizeTimed(samples)
    }

    override fun translate(
        text: String,
        direction: TranslationDirection,
        path: ExecutionPath,
    ): MtOutput = when (path) {
        ExecutionPath.HYBRID_NPU -> branch(direction).translateChecked(text)
        // The ORT Marian reports no length cap hit; recovery output is
        // accepted as the legacy recovery path accepts it.
        ExecutionPath.CPU_FALLBACK -> MtOutput(cpuFallback(direction).translate(text).trim(), 0, false)
    }

    override fun sourceTokens(text: String, direction: TranslationDirection): Int =
        branch(direction).mtSourceTokens(text)

    override fun hasPunctuation(direction: TranslationDirection): Boolean =
        branch(direction).asrEmitsPunctuation

    override fun isNpuTextQuarantined(direction: TranslationDirection): Boolean =
        direction in disabledNpuTextDirections

    override fun quarantineNpuText(direction: TranslationDirection) {
        disabledNpuTextDirections += direction
    }

    /**
     * Commit the turn prior once per long turn, after it played in full.
     * [sameSpeakerNext] is true when the turn cap cut the speaker off.
     */
    fun commitTurn(direction: TranslationDirection, sameSpeakerNext: Boolean) =
        turnRouter.commit(direction, sameSpeakerNext)

    fun expectedNextDirection(): TranslationDirection? = turnRouter.expectedNext()

    private fun cpuFallback(direction: TranslationDirection): CpuFallbackBranch =
        cpuFallbacks[direction] ?: CpuFallbackBranch.load(
            File(modelRoot, direction.manifestId),
        ).also { cpuFallbacks[direction] = it }

    /**
     * RNN-T branches read the shared Kaldi fbank computed once per utterance.
     * A Parakeet branch needs NeMo log-mel instead, so it builds its own from the
     * same PCM on its worker thread, in parallel with the other branch.
     */
    private fun asrTask(
        branch: NpuPipeline,
        samples: FloatArray,
        validFeatureFrames: Int,
    ): Callable<TimedAsrHypothesis> = Callable {
        val started = System.nanoTime()
        TimedAsrHypothesis(
            hypothesis = if (branch.usesSharedRnntFeatures) {
                branch.recognizeSharedRnntFeatures(sharedRnntFeatures, validFeatureFrames)
            } else {
                branch.recognizeScored(samples)
            },
            elapsedMs = elapsedMs(started),
        )
    }

    /**
     * Explicit-direction entry point retained for deterministic smoke/benchmark
     * fixtures and for LID A/B. Production DUAL_ASR never calls it after a
     * decision, because doing so would execute the selected encoder twice.
     */
    fun recognizeAndTranslate(
        samples: FloatArray,
        direction: TranslationDirection,
    ): HybridTextResult {
        if (direction !in disabledNpuTextDirections) {
            try {
                val asrStarted = System.nanoTime()
                val transcript = branch(direction).recognize(samples).trim()
                val asrMs = elapsedMs(asrStarted)
                if (transcript.isEmpty()) {
                    return HybridTextResult("", "", ExecutionPath.HYBRID_NPU, asrMs, 0L)
                }
                return translateSelected(samples, direction, transcript, asrMs)
            } catch (failure: Exception) {
                disabledNpuTextDirections += direction
                Log.e(
                    TAG,
                    "Primary $direction ASR/MT failed; retrying original PCM on CPU",
                    failure,
                )
            }
        }
        return runCpuFallback(samples, direction, null)
    }

    /** Translate an ASR result already produced by the dual router. */
    private fun translateSelected(
        samples: FloatArray,
        direction: TranslationDirection,
        transcript: String,
        primaryAsrMs: Long,
    ): HybridTextResult {
        var primaryFailure: Throwable? = null
        if (direction !in disabledNpuTextDirections) {
            try {
                val mtStarted = System.nanoTime()
                val translation = branch(direction).translate(transcript).trim()
                return HybridTextResult(
                    transcript = transcript,
                    translation = translation,
                    executionPath = ExecutionPath.HYBRID_NPU,
                    asrMs = primaryAsrMs,
                    mtMs = elapsedMs(mtStarted),
                )
            } catch (failure: Exception) {
                primaryFailure = failure
                disabledNpuTextDirections += direction
                Log.e(
                    TAG,
                    "Primary $direction MT failed; retrying original PCM on CPU",
                    failure,
                )
            }
        }
        return runCpuFallback(samples, direction, primaryFailure)
    }

    /** Atomic cold recovery: restart ASR+MT from the original bounded PCM. */
    private fun runCpuFallback(
        samples: FloatArray,
        direction: TranslationDirection,
        primaryFailure: Throwable?,
    ): HybridTextResult = try {
        val fallback = cpuFallback(direction)
        val asrStarted = System.nanoTime()
        val transcript = fallback.recognize(samples)
        val asrMs = elapsedMs(asrStarted)
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
                elapsedMs(mtStarted),
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

    override fun synthesize(text: String, direction: TranslationDirection): FloatArray =
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
        asrExecutor.shutdownNow()
        // Future.cancel(true) does not prove that a native graph call has
        // returned. Do not close ORT tensors/sessions while either branch task
        // can still be inside its decoder.
        var interrupted = false
        while (!asrExecutor.isTerminated) {
            try {
                asrExecutor.awaitTermination(1L, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        runCatching { languageIdentifier?.close() }
        cpuFallbacks.values.forEach { runCatching { it.close() } }
        cpuFallbacks.clear()
        runCatching { enToVi.close() }
        runCatching { viToEn.close() }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private data class FutureOutcome<T>(val value: T?, val failure: Throwable?)

    private fun <T> await(future: Future<T>): FutureOutcome<T> = try {
        FutureOutcome(future.get(), null)
    } catch (failure: ExecutionException) {
        FutureOutcome(null, failure.cause ?: failure)
    } catch (failure: CancellationException) {
        FutureOutcome(null, failure)
    }

    private fun awaitCompletionUninterruptibly(future: Future<*>) {
        while (true) {
            try {
                future.get()
                return
            } catch (_: InterruptedException) {
                // Preserve the caller's interrupted status after both branch
                // tasks have genuinely stopped using their native/ORT state.
            } catch (_: ExecutionException) {
                return
            } catch (_: CancellationException) {
                return
            }
        }
    }

    private fun routingFailure(
        mode: RoutingMode,
        reason: RoutingDecisionReason,
        error: String,
    ) = RoutedHybridTextResult(
        direction = null,
        text = null,
        routing = RoutingDiagnostics(
            mode = mode,
            priorExpected = turnRouter.expectedNext(),
            reason = reason,
            error = error,
        ),
    )

    private fun elapsedMs(startedNs: Long): Long =
        (System.nanoTime() - startedNs) / 1_000_000L
}
