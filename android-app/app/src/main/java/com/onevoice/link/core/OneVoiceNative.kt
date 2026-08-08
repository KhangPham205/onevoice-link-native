package com.onevoice.link.core

import org.json.JSONObject

/**
 * Kotlin ↔ C++ bridge for the OneVoice realtime speech-to-speech core.
 *
 * Every `external` function below maps 1:1 to a JNI entry point in
 * `native-core/src/main_jni.cpp` (symbol `Java_com_onevoice_link_core_OneVoiceNative_*`).
 * The audio → AI → audio pipeline runs on native threads; the JVM only drives
 * lifecycle calls, so ART GC pauses never touch the hot path.
 *
 * IMPORTANT — model loading is per-stage, not one call. The deployed pipeline is
 * a cascade of independently-compiled QNN context binaries (ASR, MT enc/dec, TTS
 * enc/dec), each carrying its own AI Hub-generated graph name. There is no
 * single end-to-end `.bin`, so [nativeLoadModel] is called once per stage and
 * returns a JSON record the dashboard renders.
 */
object OneVoiceNative {

    /**
     * Whether libonevoice_core.so loaded for this ABI. The core is built for
     * arm64-v8a only (Snapdragon/Hexagon), so this is false on any other
     * device — and calling an `external fun` with [isLoaded] == false throws
     * UnsatisfiedLinkError, so callers must check it first.
     */
    val isLoaded: Boolean

    init {
        // libonevoice_core has no QNN ELF NEEDED entries. The QNN runtime is
        // dlopen'd later, after NpuEngine publishes ADSP_LIBRARY_PATH.
        isLoaded = runCatching { System.loadLibrary("onevoice_core") }.isSuccess
    }

    // ---- Raw JNI ABI (do not change arity/order without the C++ side) --------

    /**
     * Opens the audio streams and brings up the QNN backend + device. Loads no
     * models — call [loadModel] per cascade stage afterwards.
     *
     * @param backendPath absolute path to `libQnnHtp.so`
     * @param systemPath  absolute path to `libQnnSystem.so` (binary introspection)
     * @param adspLibraryPath semicolon-delimited FastRPC skeleton search path
     * @param useSignedProcessDomain true for a production/test-signed skeleton
     */
    external fun nativeInitRuntime(
        backendPath: String,
        systemPath: String,
        adspLibraryPath: String,
        useSignedProcessDomain: Boolean,
    ): Boolean

    /** Loads one context binary and retrieves [graphName]. Returns a JSON record. */
    external fun nativeLoadModel(stage: String, binPath: String, graphName: String): String

    /** How many contexts are currently resident on the HTP. */
    external fun nativeLoadedModelCount(): Int

    /** Frees every loaded context; backend/device stay up for a fast reload. */
    external fun nativeReleaseModels()

    /** Spawns the AI worker thread and starts full-duplex audio. */
    external fun nativeStartEngine(): Boolean

    /** Halts the worker and stops audio; the engine stays initialized. */
    external fun nativeStopEngine()

    /** Fully tears the engine down (worker joined, audio + QNN released). */
    external fun nativeDestroyEngine()

    // ---- Convenience layer ---------------------------------------------------

    /**
     * Brings up the runtime from the app's `nativeLibraryDir`, where Gradle
     * stages the QNN runtime `.so` files.
     */
    fun initRuntime(
        nativeLibDir: String,
        adspLibraryPath: String,
        useSignedProcessDomain: Boolean,
    ): Boolean =
        nativeInitRuntime(
            backendPath = "$nativeLibDir/$QNN_HTP_BACKEND",
            systemPath = "$nativeLibDir/$QNN_SYSTEM_LIB",
            adspLibraryPath = adspLibraryPath,
            useSignedProcessDomain = useSignedProcessDomain,
        )

    /** Loads one stage and parses the native JSON record into [ModelLoadReport]. */
    fun loadModel(stage: String, binPath: String, graphName: String): ModelLoadReport =
        ModelLoadReport.parse(nativeLoadModel(stage, binPath, graphName))

    private const val QNN_HTP_BACKEND = "libQnnHtp.so"
    private const val QNN_SYSTEM_LIB = "libQnnSystem.so"
}

/** One graph tensor as declared by the context binary itself. */
data class TensorSpec(
    val name: String,
    val dtype: String,
    val dims: List<Int>,
) {
    /** e.g. `input_features FLOAT_32 [1,1001,80]` */
    override fun toString(): String = "$name $dtype ${dims.joinToString(",", "[", "]")}"
}

/**
 * Result of loading one cascade stage onto the HTP — the parsed form of the
 * JSON that `nativeLoadModel` returns.
 */
data class ModelLoadReport(
    val stage: String,
    val ok: Boolean,
    val loadMs: Double,
    val sizeBytes: Long,
    val graphName: String,
    val socModel: Int,
    val buildId: String,
    val inputs: List<TensorSpec>,
    val outputs: List<TensorSpec>,
    val error: String?,
) {
    companion object {
        /**
         * Never throws: a malformed record still yields a failed report, so a
         * JNI/serialization bug surfaces in the UI instead of crashing it.
         */
        fun parse(json: String): ModelLoadReport = runCatching {
            val o = JSONObject(json)
            ModelLoadReport(
                stage = o.optString("stage"),
                ok = o.optBoolean("ok"),
                loadMs = o.optDouble("loadMs", 0.0),
                sizeBytes = o.optLong("sizeBytes"),
                graphName = o.optString("graphName"),
                socModel = o.optInt("socModel"),
                buildId = o.optString("buildId"),
                inputs = tensors(o, "inputs"),
                outputs = tensors(o, "outputs"),
                error = o.optString("error").ifBlank { null },
            )
        }.getOrElse { t ->
            ModelLoadReport(
                stage = "?", ok = false, loadMs = 0.0, sizeBytes = 0,
                graphName = "", socModel = 0, buildId = "",
                inputs = emptyList(), outputs = emptyList(),
                error = "bad native response: ${t.message ?: t.javaClass.simpleName}",
            )
        }

        private fun tensors(o: JSONObject, key: String): List<TensorSpec> {
            val arr = o.optJSONArray(key) ?: return emptyList()
            return (0 until arr.length()).map { i ->
                val t = arr.getJSONObject(i)
                val dims = t.optJSONArray("dims")
                TensorSpec(
                    name = t.optString("name"),
                    dtype = t.optString("dtype"),
                    dims = if (dims == null) emptyList()
                    else (0 until dims.length()).map { dims.getInt(it) },
                )
            }
        }
    }
}
