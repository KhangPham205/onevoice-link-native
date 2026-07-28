package com.onevoice.link.core

/**
 * Kotlin ↔ C++ bridge for the OneVoice realtime speech-to-speech core.
 *
 * Every `external` function below maps 1:1 to a JNI entry point in
 * `native-core/src/main_jni.cpp` (symbol `Java_com_onevoice_link_core_OneVoiceNative_*`).
 * The entire audio → AI → audio pipeline runs on native threads; the JVM only
 * drives these four lifecycle calls, so ART GC pauses never touch the hot path.
 *
 * IMPORTANT — the native `nativeInitEngine` takes **three** strings
 * (`backendPath`, `contextBinPath`, `graphName`), matching `InferenceConfig` in
 * the C++ layer. Declaring a single-arg version here would hand the JVM a frame
 * that is one `jstring` short of what C++ reads, and the extra parameters would
 * dereference uninitialized register slots → native crash. The high-level
 * [initialize] wrapper composes those three paths from one logical model
 * location for callers that only think in terms of "a model path".
 */
object OneVoiceNative {

    /**
     * Whether libonevoice_core.so loaded for this ABI. The core now builds for
     * x86_64 too (QNN-stub mode), so this is normally true even on the
     * emulator — but calling any `external fun` with [isLoaded] == false would
     * throw UnsatisfiedLinkError, so backends must check it first.
     */
    val isLoaded: Boolean

    init {
        // Loads libonevoice_core.so from the APK's nativeLibraryDir. Its QNN
        // NEEDED deps (libQnnSystem/libQnnHtp/…) are staged there by the
        // copyQnnRuntimeLibs Gradle task, so this call does not UnsatisfiedLinkError.
        isLoaded = runCatching { System.loadLibrary("onevoice_core") }.isSuccess
    }

    // ---- Raw JNI ABI (do not change arity/order without the C++ side) --------

    /**
     * Opens the audio streams and loads the QNN context. Does NOT start audio.
     *
     * @param backendPath   absolute path to the QNN backend .so to dlopen,
     *                       e.g. `<nativeLibraryDir>/libQnnHtp.so`.
     * @param contextBinPath absolute path to the serialized QNN context binary.
     * @param graphName     name of the graph to select inside that context.
     * @return true once audio + QNN are ready.
     */
    external fun nativeInitEngine(
        backendPath: String,
        contextBinPath: String,
        graphName: String,
    ): Boolean

    /** Spawns the AI worker thread and starts full-duplex audio. */
    external fun nativeStartEngine(): Boolean

    /** Halts the worker and stops audio; engine stays initialized for a fast restart. */
    external fun nativeStopEngine()

    /** Fully tears the engine down (worker joined, audio + QNN released). */
    external fun nativeDestroyEngine()

    // ---- Convenience layer ---------------------------------------------------

    /**
     * Initializes the engine from a single logical model location.
     *
     * @param nativeLibDir the app's `applicationInfo.nativeLibraryDir`, where the
     *   packaged QNN backend `.so` files live.
     * @param modelDir directory holding the serialized QNN context binary
     *   ([CONTEXT_BINARY]). For the current mock build this can be any path.
     */
    fun initialize(nativeLibDir: String, modelDir: String): Boolean =
        nativeInitEngine(
            backendPath = "$nativeLibDir/$QNN_HTP_BACKEND",
            contextBinPath = "$modelDir/$CONTEXT_BINARY",
            graphName = GRAPH_NAME,
        )

    private const val QNN_HTP_BACKEND = "libQnnHtp.so"
    private const val CONTEXT_BINARY = "onevoice_s2st.bin"
    private const val GRAPH_NAME = "onevoice_s2st"
}
