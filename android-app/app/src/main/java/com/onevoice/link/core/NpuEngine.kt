package com.onevoice.link.core

import android.content.Context
import android.system.Os
import android.util.Log
import com.onevoice.link.BuildConfig
import java.io.File

private const val TAG = "OneVoiceNpu"

/** Where one cascade stage stands in the load sequence. */
enum class StageState {
    /** Queued, not attempted yet. */
    PENDING,
    LOADING,
    /** Context created on the HTP and the expected graph retrieved. */
    LOADED,
    /** The `.bin` is not on the device — run tools/models/push-npu-models.sh. */
    MISSING,
    FAILED,
}

/** Per-stage status the dashboard renders, one row each. */
data class StageStatus(
    val spec: StageSpec,
    val state: StageState = StageState.PENDING,
    val report: ModelLoadReport? = null,
    val error: String? = null,
) {
    val loadMs: Double get() = report?.loadMs ?: 0.0
}

/**
 * Native QNN context loader for the Hexagon HTP.
 *
 * Scope note (deliberate): this currently **loads and verifies** the cascade, it
 * does not translate. `initialize()` proves, per stage, that the `.bin` parses,
 * that its graphs place onto this phone's HTP, and that the expected graph name
 * resolves. Actually running the cascade additionally needs graph-execution
 * host glue and missing TTS artifacts. In particular, the delivered Kokoro
 * split has no learned F0Ntrain prosody network or exact HAR/overlap-add
 * contract. `start()` therefore refuses to present a fake live mode. The
 * complete runnable path is [com.onevoice.link.hf.HfEngineBackend].
 *
 * Models live under `<externalFilesDir>/models/npu/<direction>/` so a plain
 * `adb push` can place them — no `run-as` needed.
 */
class NpuEngine(context: Context) : EngineBackend {

    override val displayName = "QNN / Hexagon HTP · context verification"

    /**
     * The delivered Kokoro pair omits the learned F0Ntrain prosody network.
     * Loading five contexts is valid, but they do not form an executable
     * speech-to-speech cascade, so the UI must never present this as live.
     */
    override val supportsLiveTranslation = false

    override var lastError: String? = null
        private set

    /** Parsed once; the asset is generated from the binaries themselves. */
    val manifest: NpuManifest = NpuManifest.load(context)

    private val nativeLibDir: String = context.applicationInfo.nativeLibraryDir
    private val appContext = context.applicationContext

    /**
     * The DSP half of the HTP backend, extracted from assets on first run.
     *
     * It has to be a real file on a path FastRPC can read, so it cannot ride
     * along in jniLibs: `useLegacyPackaging = false` keeps packaged .so files
     * inside the APK and ADSP_LIBRARY_PATH needs a filesystem directory.
     */
    private val dspRoot = File(context.filesDir, "dsp")

    /**
     * Where the `.bin` cascade may live, in priority order.
     *
     * External storage is listed first because `adb push` can write there
     * directly, but it is NOT reliable: on this Android 16 / One UI device the
     * app process cannot see anything under its own
     * `Android/data/<pkg>/files/` that it did not create itself — the path
     * resolves, `adb shell` lists the files, and the app still gets
     * `exists() == false`. Internal `filesDir` has no such ambiguity, so the
     * push script stages there via `run-as` and that is what actually works.
     */
    private val candidateRoots: List<File> = listOfNotNull(
        context.getExternalFilesDir(null)?.let { File(it, manifest.modelRoot) },
        File(context.filesDir, manifest.modelRoot),
    )

    /** First candidate that actually holds models, else the last (for errors). */
    private val modelRoot: File
        get() = candidateRoots.firstOrNull { it.isDirectory && (it.list()?.isNotEmpty() == true) }
            ?: candidateRoots.last()

    /** Absolute path shown in the UI so a missing-models error is actionable. */
    val modelRootPath: String get() = modelRoot.absolutePath

    @Volatile
    var direction: TranslationDirection = TranslationDirection.VI_TO_EN
        private set

    /** Current per-stage status for [direction]; replaced wholesale on change. */
    @Volatile
    var stages: List<StageStatus> = emptyList()
        private set

    private var listener: ((List<StageStatus>) -> Unit)? = null
    private var runtimeUp = false

    fun setStageListener(l: ((List<StageStatus>) -> Unit)?) {
        listener = l
    }

    /** The stages the manifest declares for [direction], before any load. */
    fun plannedStages(d: TranslationDirection = direction): List<StageStatus> =
        manifest.forDirection(d)?.stages?.map { StageStatus(it) } ?: emptyList()

    fun directionSpec(d: TranslationDirection = direction): DirectionSpec? =
        manifest.forDirection(d)

    // ------------------------------------------------------------------ init
    /**
     * Brings up the QNN backend, then loads every stage of the current
     * direction. Returns true only when all stages loaded.
     */
    override fun initialize(): Boolean {
        // This must happen before the first reference to OneVoiceNative. Loading
        // libonevoice_core used to pull libQnnHtp in through ELF NEEDED entries,
        // which made a later setenv ineffective because FastRPC had initialized.
        val dsp = runCatching { prepareDspRuntime() }.getOrElse {
            lastError = it.message ?: "Cannot prepare Hexagon skeleton."
            Log.e(TAG, lastError, it)
            return false
        }
        runCatching {
            Os.setenv("ADSP_LIBRARY_PATH", dsp.libraryPath, true)
        }.onFailure {
            lastError = "Cannot set ADSP_LIBRARY_PATH: ${it.message}"
            Log.e(TAG, lastError, it)
            return false
        }
        Log.i(
            TAG,
            "DSP mode=${if (dsp.signedPd) "signed" else "unsigned"} " +
                "source=${dsp.source} ADSP_LIBRARY_PATH=${dsp.libraryPath}",
        )

        if (!OneVoiceNative.isLoaded) {
            lastError = "Native core unavailable on this ABI (arm64-v8a required)."
            return false
        }
        val spec = manifest.forDirection(direction)
        if (spec == null) {
            lastError = "Manifest has no entry for ${direction.manifestId}."
            return false
        }
        if (!spec.available) {
            lastError = spec.unavailableReason ?: "Direction unavailable."
            stages = spec.stages.map { StageStatus(it, StageState.PENDING) }
            publish()
            return false
        }

        if (!runtimeUp) {
            runtimeUp = runCatching {
                OneVoiceNative.initRuntime(
                    nativeLibDir = nativeLibDir,
                    adspLibraryPath = dsp.libraryPath,
                    useSignedProcessDomain = dsp.signedPd,
                )
            }
                .getOrElse { t ->
                    lastError = "Native runtime error: ${t.message ?: t.javaClass.simpleName}"
                    return false
                }
            if (!runtimeUp) {
                lastError = "QNN HTP device initialization failed."
                return false
            }
        }

        return loadStagesFor(spec)
    }

    /** Select a process domain and the matching skeleton explicitly. */
    private fun prepareDspRuntime(): DspRuntimeConfig {
        val requestedMode = BuildConfig.QNN_PD_MODE
        val signedAsset = "$SIGNED_ASSET_DIR/$DSP_SKEL"
        val hasSignedAsset = appContext.assets.list(SIGNED_ASSET_DIR)
            ?.contains(DSP_SKEL) == true
        if (hasSignedAsset && requestedMode != "unsigned") {
            val outDir = File(dspRoot, "signed")
            extractAsset(signedAsset, File(outDir, DSP_SKEL))
            return DspRuntimeConfig(
                libraryPath = adspPath(outDir.absolutePath),
                signedPd = true,
                source = "APK signed skeleton",
            )
        }

        // The OEM skeleton is built for Samsung's private host/stub stack and
        // must not be assumed ABI-compatible with the QNN libraries in this APK.
        // Signed mode therefore requires an explicitly supplied, matching image.
        if (requestedMode == "signed") {
            error(
                "Signed PD requested but $signedAsset is missing. " +
                    "Provide -PqnnSignedSkel=<matching signed V73 skeleton>.",
            )
        }

        // In auto mode, use the matching SDK skeleton bundled with the app unless
        // an explicitly supplied signed artifact was staged above.
        val outDir = File(dspRoot, "unsigned")
        extractAsset("dsp/$DSP_SKEL", File(outDir, DSP_SKEL))
        return DspRuntimeConfig(
            libraryPath = adspPath(outDir.absolutePath),
            signedPd = false,
            source = "APK unsigned skeleton ($requestedMode mode)",
        )
    }

    private fun extractAsset(assetPath: String, out: File) {
        runCatching {
            out.parentFile?.mkdirs()
            val temp = File(out.parentFile, "${out.name}.tmp")
            appContext.assets.open(assetPath).use { input ->
                temp.outputStream().use { input.copyTo(it) }
            }
            check(!out.exists() || out.delete()) { "cannot replace ${out.absolutePath}" }
            check(temp.renameTo(out)) { "cannot publish ${out.absolutePath}" }
            check(out.setReadable(true, true)) { "cannot chmod ${out.absolutePath}" }
            Log.i(TAG, "extracted $assetPath (${out.length()} bytes) to ${out.parent}")
        }.getOrElse {
            throw IllegalStateException("Failed to extract $assetPath", it)
        }
    }

    private fun adspPath(vararg first: String): String =
        (first.asList() + OEM_DSP_DIRS).distinct().joinToString(";")

    private data class DspRuntimeConfig(
        val libraryPath: String,
        val signedPd: Boolean,
        val source: String,
    )

    /** Load every stage of [spec], publishing progress as each one completes. */
    private fun loadStagesFor(spec: DirectionSpec): Boolean {
        var working = spec.stages.map { StageStatus(it) }
        stages = working
        publish()

        candidateRoots.forEach {
            Log.i(TAG, "candidate ${it.absolutePath} isDir=${it.isDirectory} " +
                "children=${it.list()?.size ?: -1}")
        }
        Log.i(TAG, "using modelRoot=$modelRootPath")

        var allOk = true
        spec.stages.forEachIndexed { i, stageSpec ->
            val file = stageSpec.resolve(modelRoot)
            Log.i(TAG, "stage=${stageSpec.stage} path=${file.absolutePath} " +
                "isFile=${file.isFile} canRead=${file.canRead()} len=${file.length()}")
            if (!file.isFile) {
                working = working.replaceAt(
                    i,
                    StageStatus(stageSpec, StageState.MISSING, error = file.absolutePath),
                )
                stages = working
                publish()
                allOk = false
                return@forEachIndexed
            }

            working = working.replaceAt(i, StageStatus(stageSpec, StageState.LOADING))
            stages = working
            publish()

            val report = runCatching {
                OneVoiceNative.loadModel(stageSpec.stage, file.absolutePath, stageSpec.graph)
            }.getOrElse { t ->
                ModelLoadReport(
                    stage = stageSpec.stage, ok = false, loadMs = 0.0, sizeBytes = 0,
                    graphName = "", socModel = 0, buildId = "",
                    inputs = emptyList(), outputs = emptyList(),
                    error = t.message ?: t.javaClass.simpleName,
                )
            }

            // A .bin built for another Hexagon generation usually fails in
            // contextCreateFromBinary, but when it does load, say so plainly
            // rather than letting a wrong-chip artifact pass silently.
            val socWarning =
                if (report.ok && report.socModel != 0 &&
                    report.socModel != manifest.targetSocModel
                ) {
                    "built for socModel=${report.socModel}, " +
                        "expected ${manifest.targetSocModel} (${manifest.targetSocName})"
                } else {
                    null
                }

            working = working.replaceAt(
                i,
                StageStatus(
                    spec = stageSpec,
                    state = if (report.ok) StageState.LOADED else StageState.FAILED,
                    report = report,
                    error = report.error ?: socWarning,
                ),
            )
            stages = working
            publish()
            if (!report.ok) {
                allOk = false
                Log.e(TAG, "stage ${stageSpec.stage} failed: ${report.error}")
            }
        }

        lastError = when {
            allOk -> null
            working.any { it.state == StageState.MISSING } ->
                "Missing .bin under $modelRootPath — run tools/models/push-npu-models.sh."
            else -> working.firstOrNull { it.state == StageState.FAILED }?.error
                ?: "One or more stages failed to load."
        }
        return allOk
    }

    // ------------------------------------------------------------- direction
    /**
     * Switch direction. Contexts for the previous direction are released first:
     * the two cascades together are ~2 GB of weights, which is not worth keeping
     * resident. Callers must re-run [initialize] afterwards.
     */
    override fun setDirection(direction: TranslationDirection) {
        if (direction == this.direction) return
        this.direction = direction
        if (runtimeUp) runCatching { OneVoiceNative.nativeReleaseModels() }
        stages = plannedStages(direction)
        lastError = null
        publish()
    }

    // ------------------------------------------------------------- lifecycle
    override fun start(): Boolean {
        lastError =
            "QNN contexts are load/verify only: the delivered Kokoro split is missing " +
                "the learned F0Ntrain prosody stage. Select Offline CPU for the complete pipeline."
        return false
    }

    override fun stop() {
        if (!runtimeUp) return
        runCatching { OneVoiceNative.nativeStopEngine() }
    }

    override fun destroy() {
        if (!OneVoiceNative.isLoaded) return
        runCatching { OneVoiceNative.nativeDestroyEngine() }
        runtimeUp = false
        stages = emptyList()
    }

    /** How many contexts the native side reports as resident right now. */
    fun loadedModelCount(): Int =
        if (runtimeUp) runCatching { OneVoiceNative.nativeLoadedModelCount() }.getOrDefault(0)
        else 0

    private fun publish() {
        listener?.invoke(stages)
    }

    private companion object {
        /** Hexagon v73 = Snapdragon 8 Gen 2; matches the manifest's target SoC. */
        const val DSP_SKEL = "libQnnHtpV73Skel.so"
        const val SIGNED_ASSET_DIR = "dsp/signed"

        val OEM_DSP_DIRS = listOf(
            "/vendor/lib/rfsa/adsp/snap",
            "/vendor/dsp/cdsp",
            "/vendor/lib/rfsa/adsp",
            "/system/lib/rfsa/adsp",
            "/dsp",
        )
    }
}

private fun <T> List<T>.replaceAt(index: Int, value: T): List<T> =
    toMutableList().also { it[index] = value }
