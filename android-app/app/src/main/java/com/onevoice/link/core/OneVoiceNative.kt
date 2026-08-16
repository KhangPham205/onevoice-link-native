package com.onevoice.link.core

import org.json.JSONObject
import java.nio.ByteBuffer

/**
 * Kotlin ↔ C++ bridge for the OneVoice realtime speech-to-speech core.
 *
 * Every `external` function below maps 1:1 to a JNI entry point in
 * `native-core/src/main_jni.cpp` (symbol `Java_com_onevoice_link_core_OneVoiceNative_*`).
 * Kotlin owns bounded AudioRecord/AudioTrack and pipeline orchestration. JNI
 * supplies generic direct-buffer graph execution and native signal transforms.
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
     * Brings up the QNN backend + device. Loads no models and opens no audio
     * streams — call [loadModel] per cascade stage afterwards.
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

    /** Executes a loaded graph with direct buffers; null means success. */
    external fun nativeExecuteGraph(
        stage: String,
        inputs: Array<ByteBuffer>,
        outputs: Array<ByteBuffer>,
    ): String?

    /** NeMo-compatible 16 kHz PCM float -> direct [1,1001,80] FLOAT32. */
    external fun nativeExtractAsrFeatures(samples: FloatArray, output: ByteBuffer): String?

    /** Kaldi/icefall-compatible 16 kHz PCM float -> direct [1,1000,80] FLOAT32. */
    external fun nativeExtractGipformerFeatures(
        samples: FloatArray,
        output: ByteBuffer,
    ): String?

    /** NeMo AmberNet 16 kHz PCM float -> direct [1,80,1008] FLOAT32. */
    external fun nativeExtractAmberNetFeatures(
        samples: FloatArray,
        output: ByteBuffer,
    ): String?

    // ---- Benchmark surface -------------------------------------------------
    // Kept off the execute path on purpose: an out-parameter on
    // nativeExecuteGraph would add work to the hot call and change an arity
    // that this file and main_jni.cpp must keep byte-for-byte in agreement.

    /**
     * Turn per-execute QNN profiling on or off (BASIC level).
     *
     * Costs real time inside `graphExecute`, so a latency baseline must come
     * from a cohort where this was never enabled. Returns false when the
     * backend is down or the profile handle could not be created.
     */
    external fun nativeSetQnnProfiling(enabled: Boolean): Boolean

    /**
     * Pin the HTP DVFS/latency knobs. Returns null on success, else the error.
     * Modes: `burst`, `sustained_high_performance`, `balanced`.
     */
    external fun nativeApplyHtpPowerConfig(
        mode: String,
        rpcControlLatencyUs: Int,
        rpcPollingTimeUs: Int,
    ): String?

    /**
     * Stage name per collected sample. MUST be called before
     * [nativeDrainQnnProfile] — it takes the snapshot that call then reads, so
     * the two arrays line up positionally.
     */
    external fun nativeDrainQnnProfileStages(): Array<String>

    /**
     * Flat `[wallUs, executeUs, acceleratorUs, queueWaitUs, profiled] * n` over
     * the snapshot taken by [nativeDrainQnnProfileStages], which it consumes.
     */
    external fun nativeDrainQnnProfile(): LongArray

    /** Power mode currently in force, or `unavailable`. */
    external fun nativeActivePowerMode(): String

    /**
     * `AThermalStatus` value (0 none … 6 shutdown), or -1 before API 30.
     * Resolved with dlsym because this module ships minSdk 28.
     */
    external fun nativeThermalStatus(): Int

    /**
     * SpeechFlow LID 16 kHz PCM float -> direct [1,1000,80] FLOAT32.
     *
     * Time-major, whole-utterance normalized — deliberately not the same
     * contract as [nativeExtractAmberNetFeatures]. `outFrames[0]` receives the
     * unpadded frame count that must be passed to the model as `input_2`.
     */
    external fun nativeExtractSpeechFlowFeatures(
        samples: FloatArray,
        output: ByteBuffer,
        outFrames: IntArray,
    ): String?

    /** Kokoro F0 [50] -> harmonic STFT [22,3001], both direct FLOAT32. */
    external fun nativeBuildKokoroHar(
        f0: ByteBuffer,
        har: ByteBuffer,
        resetPhase: Boolean,
    ): String?

    /** Frees every loaded context; backend/device stay up for a fast reload. */
    external fun nativeReleaseModels()

    /** Legacy ABI; live audio is owned by [NpuEngine] and this returns false. */
    external fun nativeStartEngine(): Boolean

    /** Legacy no-op retained for ABI compatibility. */
    external fun nativeStopEngine()

    /** Fully tears the native QNN runtime down. */
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
