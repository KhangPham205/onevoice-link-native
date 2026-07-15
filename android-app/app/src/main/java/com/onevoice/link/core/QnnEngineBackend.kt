package com.onevoice.link.core

/**
 * Production backend: thin adapter over [OneVoiceNative], preserving the exact
 * call sequencing the dashboard has always used. The native core loads the
 * serialized QNN context binary (.bin) onto the Hexagon HTP — this path is
 * untouched by the emulator work and remains the default on arm64 devices.
 */
class QnnEngineBackend(
    private val nativeLibDir: String,
    private val modelDir: String,
) : EngineBackend {

    override val displayName = "QNN / Hexagon NPU (.bin)"

    override var lastError: String? = null
        private set

    override fun initialize(): Boolean {
        if (!OneVoiceNative.isLoaded) {
            lastError = "Native core library unavailable on this ABI."
            return false
        }
        val ok = OneVoiceNative.initialize(nativeLibDir, modelDir)
        if (!ok) lastError = "QNN init failed (check context .bin / backend path)."
        return ok
    }

    override fun start(): Boolean {
        val ok = OneVoiceNative.nativeStartEngine()
        if (!ok) lastError = "Native audio stream failed to start."
        return ok
    }

    override fun stop() = OneVoiceNative.nativeStopEngine()

    override fun destroy() {
        if (OneVoiceNative.isLoaded) OneVoiceNative.nativeDestroyEngine()
    }
}
