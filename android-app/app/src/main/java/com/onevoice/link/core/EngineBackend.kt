package com.onevoice.link.core

/**
 * Common lifecycle for an offline speech-to-speech engine.
 *
 * Production uses one auto-routing Hybrid engine. The CPU implementation is
 * retained as a failover/reference implementation but is not user-selectable.
 */
interface EngineBackend {
    val displayName: String
    val lastError: String?

    /** True only when start() can produce translated speech. */
    val supportsLiveTranslation: Boolean get() = true

    fun initialize(): Boolean
    fun start(): Boolean
    fun stop()
    fun destroy()

    fun setDirection(direction: TranslationDirection) {}
    fun replayLastAudio(): Boolean = false
    fun setPipelineListener(listener: PipelineListener?) {}

    interface PipelineListener {
        /** Called from a worker thread after ASR and again after MT. */
        fun onSegment(segment: PipelineSegment)

        /** Called only after a replayable waveform has been published. */
        fun onAudioReady() {}

        /** Reports an asynchronous pipeline failure while the engine is running. */
        fun onPipelineError(message: String) {}

        /** Reports a fatal asynchronous failure after the engine has stopped itself. */
        fun onPipelineStopped(message: String) {}
    }
}

/** Which compute path produced a segment; it is diagnostic, never a UI mode. */
enum class ExecutionPath {
    HYBRID_NPU,
    CPU_FALLBACK,
}

/**
 * Atomic per-utterance result. Direction and confidence travel with the text,
 * preventing an asynchronous language update from relabeling another turn.
 */
data class PipelineSegment(
    val transcript: String,
    val translation: String?,
    val direction: TranslationDirection,
    val lidConfidence: Float,
    val executionPath: ExecutionPath,
)
