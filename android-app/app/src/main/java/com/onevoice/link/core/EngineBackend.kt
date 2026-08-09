package com.onevoice.link.core

/**
 * Common lifecycle for an offline speech-to-speech engine.
 *
 * [NpuEngine] provides both the six-stage QNN cascade and the hybrid QNN
 * ASR/MT + whole-utterance CPU TTS path. [com.onevoice.link.hf.HfEngineBackend]
 * is the CPU reference backend. All emit intermediate text through
 * [PipelineListener].
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
        fun onSegment(transcript: String, translation: String?)

        /** Called only after a replayable waveform has been published. */
        fun onAudioReady() {}

        /** Reports an asynchronous pipeline failure while the engine is running. */
        fun onPipelineError(message: String) {}

        /** Reports a fatal asynchronous failure after the engine has stopped itself. */
        fun onPipelineStopped(message: String) {}
    }
}
