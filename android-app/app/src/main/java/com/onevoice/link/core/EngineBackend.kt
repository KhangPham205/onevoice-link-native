package com.onevoice.link.core

/**
 * Which way the conversation is being translated. A 2-person conversation
 * flips this per speaker turn, so switching must be cheap (hot-switchable
 * while RUNNING on backends that support it).
 */
enum class TranslationDirection {
    VI_TO_EN,
    EN_TO_VI,
}

/**
 * A speech-to-speech engine implementation behind the dashboard.
 *
 * Two implementations exist:
 *  - [QnnEngineBackend] — the production path: native C++ core + Qualcomm QNN
 *    context binary (.bin) on the Hexagon HTP. arm64 Snapdragon devices only.
 *  - `hf.HfEngineBackend` — the emulator/dev path: HuggingFace models run with
 *    sherpa-onnx + ONNX Runtime on CPU (Parakeet-CTC-vi → opus-mt-vi-en →
 *    Kokoro-vi), so the full cascade is testable without an NPU.
 *
 * The lifecycle contract mirrors the native one exactly:
 * NOT_INITIALIZED → initialize() → INITIALIZED → start()/stop() → destroy().
 * All methods are called off the main thread (Dispatchers.IO) by the
 * EngineController and may block.
 */
interface EngineBackend {
    /** Human-readable backend name, shown in the dashboard. */
    val displayName: String

    /**
     * Prepare the engine (load models / open audio). Does NOT start streaming.
     * Returns false on failure; [lastError] then explains why.
     */
    fun initialize(): Boolean

    /** Begin the realtime capture→translate→playback stream. */
    fun start(): Boolean

    /** Halt streaming; the engine stays initialized for a fast restart. */
    fun stop()

    /** Release everything. Safe to call in any state; idempotent. */
    fun destroy()

    /** Optional detail for the last initialize()/start() failure. */
    val lastError: String?

    /**
     * Select the translation direction. Default no-op: the QNN backend's
     * cascade direction is baked into its context binary (model-team owned).
     */
    fun setDirection(direction: TranslationDirection) {}

    /**
     * Re-play the most recently synthesized audio (the spoken translation).
     * Returns false when unsupported or nothing has been synthesized yet.
     */
    fun replayLastAudio(): Boolean = false

    /**
     * Optional live pipeline text (transcript + translation), emitted by
     * backends that produce intermediate text. The QNN backend emits nothing
     * (its cascade is opaque inside the HTP context binary).
     */
    fun setPipelineListener(listener: PipelineListener?) {}

    interface PipelineListener {
        /** Called from a background thread whenever a segment finishes a stage. */
        fun onSegment(transcript: String, translation: String?)
    }
}
