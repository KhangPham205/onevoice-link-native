package com.onevoice.link.core

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Process
import android.system.Os
import android.util.Log
import androidx.annotation.RequiresApi
import com.onevoice.link.BuildConfig
import java.io.File
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import org.json.JSONObject

private const val TAG = "OneVoiceNpu"
private const val HALF_DUPLEX_TAG = "OneVoiceHalfDuplex"
private const val TUNING_TAG = "OneVoiceRouting"
private const val VAD_TAG = "OneVoiceVad"
/** Per-window and per-unit rows of long turns; the per-turn row stays in OneVoiceRouting. */
private const val LONG_TURN_TAG = "OneVoiceLongTurn"

/** Legacy construction option; production exposes only [CPU_WHOLE_UTTERANCE]. */
enum class NpuTtsMode {
    QNN_BUCKETED,
    CPU_WHOLE_UTTERANCE,
}

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
 * Automatic Hybrid pipeline. Audio/VAD and deterministic tensor transforms use
 * bounded CPU buffers. Both ASRs score every utterance by default; SpeechFlow
 * remains a build-time A/B router. ASR/MT execute on HTP where available and
 * Kokoro renders one continuous target utterance on CPU.
 *
 * Models normally live under internal `<filesDir>/models/npu/<direction>/`;
 * the deploy script stages them with `run-as` because direct external-storage
 * pushes are not visible to this app on the target Android 16 build.
 */
class NpuEngine(
    context: Context,
    @Suppress("UNUSED_PARAMETER") ttsMode: NpuTtsMode = NpuTtsMode.CPU_WHOLE_UTTERANCE,
) : EngineBackend {

    override val displayName =
        "Hybrid · dual ASR · half-duplex · QNN ASR + MT · CPU Kokoro"
    override val supportsLiveTranslation: Boolean = true

    init {
        // The benchmark receiver is a separate instance created by the
        // framework, so it needs a way to reach the engine the Activity owns.
        // Weak so a destroyed engine is not pinned alive by this alone.
        activeEngine = WeakReference(this)
    }

    override var lastError: String? = null
        private set

    /** Parsed once; the asset is generated from the binaries themselves. */
    val manifest: NpuManifest = NpuManifest.load(context)

    private val nativeLibDir: String = context.applicationInfo.nativeLibraryDir
    private val appContext = context.applicationContext
    private val routingConfig = RoutingConfig.fromBuildConfig()
    /** Build-time long-turn policy; a bench replay may run a baseline (B0..B3) instead. */
    private val buildLongTurnConfig = LongTurnConfig.fromBuildConfig()
    @Volatile private var longTurnConfig = buildLongTurnConfig
    private val vadMode = VadMode.parse(BuildConfig.VAD_MODE)
    private val routePreferences = AudioRoutePreferences(appContext)

    /**
     * Audio I/O choice read at the start of every turn. Changing it never
     * interrupts a live turn; the next IDLE -> CAPTURING edge resolves it.
     */
    @Volatile
    var audioRoutePreference: AudioRoutePreference = routePreferences.get()
        set(value) {
            field = value
            routePreferences.set(value)
        }

    /** Human-readable input -> output route of the most recent turn, for the UI. */
    @Volatile
    var lastRouteLabel: String? = null
        private set

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

    /** Current per-stage status for both resident translation directions. */
    @Volatile
    var stages: List<StageStatus> = emptyList()
        private set

    private var listener: ((List<StageStatus>) -> Unit)? = null
    private var runtimeUp = false

    @Volatile
    private var pipelineListener: EngineBackend.PipelineListener? = null
    @Volatile
    private var pipeline: AutoHybridPipeline? = null
    private val running = AtomicBoolean(false)
    private val releasePending = AtomicBoolean(false)
    private val destroyed = AtomicBoolean(false)
    private val playbackLock = ReentrantLock()
    private val turnIds = AtomicLong(0L)
    private val halfDuplex = HalfDuplexCoordinator { transition ->
        Log.i(
            HALF_DUPLEX_TAG,
            "turn=${transition.turnId} from=${transition.from} to=${transition.to} " +
                "timestampNs=${transition.timestampNs} dwellMs=${transition.dwellMs} " +
                "reason=${transition.reason}",
        )
        val phase = when (transition.to) {
            HalfDuplexState.IDLE -> LivePhase.IDLE
            HalfDuplexState.CAPTURING -> LivePhase.CAPTURING
            HalfDuplexState.PROCESSING -> LivePhase.PROCESSING
            HalfDuplexState.PLAYING -> LivePhase.PLAYING
        }
        runCatching { pipelineListener?.onLivePhase(phase) }
    }

    /**
     * Live-turn evidence that survives without logcat. Bench fixtures are not
     * recorded here; the harness keeps its own CSV.
     */
    private val sessionRecorder = SessionRecorder(File(context.filesDir, "sessions")) {
        sessionHeader()
    }

    /** Tagged bench fixtures: same row shape as a live session, for scoring. */
    private val benchRecorder = SessionRecorder(File(context.filesDir, "bench/turns")) {
        sessionHeader()
    }

    private fun sessionHeader(): JSONObject =
        JSONObject().apply {
            put("timestamp", System.currentTimeMillis())
            put("versionName", BuildConfig.VERSION_NAME)
            put("buildType", BuildConfig.BUILD_TYPE)
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("soc", if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.HARDWARE)
            put("android", Build.VERSION.RELEASE)
            put("routingMode", routingConfig.mode.name)
            put("marginThreshold", routingConfig.marginThreshold)
            put("floorThreshold", routingConfig.floorThreshold)
            put("priorWeight", routingConfig.priorWeight)
            put("enScoreOffset", routingConfig.enScoreOffset)
            put("vadMode", vadMode.name)
            put("viTtsEngine", BuildConfig.VI_TTS_ENGINE)
            when (BuildConfig.VI_TTS_ENGINE) {
                "SUPERTONIC" -> put(
                    "viTtsConfig",
                    "voice=${BuildConfig.SUPERTONIC_VOICE} steps=${BuildConfig.SUPERTONIC_STEPS} " +
                        "speed=${BuildConfig.SUPERTONIC_SPEED} threads=${BuildConfig.SUPERTONIC_THREADS}",
                )
                "VIENEU" -> put(
                    "viTtsConfig",
                    "voice=${BuildConfig.VIENEU_VOICE} steps=${BuildConfig.VIENEU_STEPS} " +
                        "cfg=${BuildConfig.VIENEU_CFG} speed=${BuildConfig.VIENEU_SPEED} " +
                        "threads=${BuildConfig.VIENEU_THREADS} seed=${BuildConfig.VIENEU_SEED}",
                )
            }
            put("kokoroThreads", BuildConfig.KOKORO_THREADS)
            put("audioRoutePreference", audioRoutePreference.name)
            put("longTurn", longTurnConfig.toJson())
        }

    /** JSONL file of the current (or last) live session; null before any turn. */
    val sessionFile: File? get() = sessionRecorder.currentFile

    /** Close the current session log; the next live turn opens a new file. */
    fun startNewSession() = sessionRecorder.rotate()

    /** Start a fresh tagged-fixture log, e.g. once per dataset evaluation run. */
    internal fun rotateBenchLog() = benchRecorder.rotate()

    /**
     * Benchmark sink. Inert unless the harness created `<filesDir>/bench`, so a
     * production run pays only one directory check per utterance.
     */
    private val telemetry: BenchTelemetry? =
        runCatching { BenchTelemetry(appContext) }.getOrNull()
    /**
     * A bounded utterance plus the two facts about it that only the capture
     * loop knows.
     *
     * [enqueuedNs] exists so queue_wait_ms can be separated from inference
     * time: under back-pressure an utterance can sit here for hundreds of
     * milliseconds, and folding that into the model timings would blame the
     * models for a scheduling effect.
     */
    private class CapturedSegment(
        val id: Long,
        val samples: FloatArray,
        val enqueuedNs: Long,
        /** True when the 10 s ASR ceiling cut the utterance, not silence. */
        val forcedBoundary: Boolean,
        /** Capture route of this turn; playback follows the same route. */
        val route: AudioRouteKind,
        /** Communication endpoint used to open SCO for this bounded turn. */
        val communicationAddress: String?,
        val communicationName: String,
        /** Setup and teardown costs around the SCO capture session. */
        val captureSetupMs: Long,
        val scoCloseMs: Long,
        val scoClosedNs: Long,
        /** Bench fixtures bypass the live half-duplex coordinator. */
        val benchmark: Boolean = false,
        /** Bench-forced branch; null routes the fixture like a live turn. */
        val benchmarkDirection: TranslationDirection? = null,
        /**
         * Dataset item id. A tagged fixture also records its text and target
         * waveform so accuracy can be scored offline against the references.
         */
        val benchmarkTag: String? = null,
        /** Set for a bench replay turn: its row goes to the bench log with this tag. */
        val replayTag: String? = null,
        val inputMode: String = "microphone",
    )

    private val segments = LinkedBlockingQueue<CapturedSegment>(MAX_QUEUED_SEGMENTS)
    private data class ReplayAudio(
        val mono: FloatArray,
        val sampleRate: Int,
        val direction: TranslationDirection,
        val route: AudioRouteKind,
        val communicationAddress: String?,
        val communicationName: String,
    )
    @Volatile private var lastAudio: ReplayAudio? = null
    private var captureThread: Thread? = null
    private var pipelineThread: Thread? = null
    @Volatile private var replayThread: Thread? = null
    @Volatile private var activeRecord: AudioRecord? = null
    @Volatile private var activePlaybackTrack: AudioTrack? = null
    @Volatile private var ttsThread: Thread? = null
    @Volatile private var longTurnCoordinator: LongTurnCoordinator? = null
    @Volatile private var activeLongTurn: LongTurn? = null
    /** Bumped by stop(): a late result of an older session is never published. */
    private val sessionGeneration = AtomicLong(0L)
    /** One archive reused by every long turn; a turn clears it only after the previous one finished. */
    private var turnArchive: TurnArchive? = null

    override fun setPipelineListener(listener: EngineBackend.PipelineListener?) {
        pipelineListener = listener
    }

    fun setStageListener(l: ((List<StageStatus>) -> Unit)?) {
        listener = l
    }

    /** All HTP contexts kept resident by the auto-direction Hybrid pipeline. */
    fun plannedStages(): List<StageStatus> = manifest.directions.flatMap { spec ->
        selectedStages(spec).map { StageStatus(it) }
    }

    fun directionSpec(d: TranslationDirection): DirectionSpec? = manifest.forDirection(d)

    // ------------------------------------------------------------------ init
    /** Brings up QNN and loads both ASR/MT branches plus their CPU host glue. */
    @Synchronized
    override fun initialize(): Boolean {
        if (destroyed.get()) {
            lastError = "NPU engine has already been destroyed."
            return false
        }
        if (pipeline != null) return true
        if (releasePending.get()) {
            lastError = "Previous NPU inference is still shutting down."
            return false
        }
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
        val directionSpecs = listOf("vi-en", "en-vi").map { id ->
            manifest.directions.firstOrNull { it.id == id } ?: run {
                lastError = "Manifest has no entry for $id."
                return false
            }
        }
        directionSpecs.firstOrNull { !it.available }?.let { unavailable ->
            lastError = unavailable.unavailableReason
                ?: "Hybrid direction ${unavailable.id} is unavailable."
            stages = plannedStages()
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

        if (!loadStagesFor(directionSpecs)) {
            // loadModel publishes each context as soon as that stage succeeds.
            // A later missing/invalid stage must not leave a partial six-context
            // set resident: retrying initialize() would otherwise append another
            // copy and can exhaust HTP/device memory.
            runCatching { OneVoiceNative.nativeReleaseModels() }
            return false
        }
        val residentContexts = loadedModelCount()
        if (residentContexts != EXPECTED_HYBRID_CONTEXTS) {
            lastError = "QNN loaded $residentContexts contexts; expected $EXPECTED_HYBRID_CONTEXTS."
            Log.e(TAG, lastError!!)
            runCatching { OneVoiceNative.nativeReleaseModels() }
            return false
        }
        logResidentMemory("qnn_contexts_loaded", residentContexts)
        return runCatching {
            pipeline = AutoHybridPipeline.load(modelRoot, manifest, routingConfig)
            lastError = null
            logResidentMemory("hybrid_host_glue_loaded", residentContexts)
            Log.i(
                TAG,
                "Auto Hybrid pipeline ready directions=vi-en,en-vi " +
                    "routingMode=${routingConfig.mode} " +
                    "margin=${routingConfig.marginThreshold} floor=${routingConfig.floorThreshold} " +
                    "priorWeight=${routingConfig.priorWeight} enOffset=${routingConfig.enScoreOffset} " +
                    "kokoroThreads=${BuildConfig.KOKORO_THREADS} " +
                    "root=$modelRootPath",
            )
            true
        }.getOrElse { failure ->
            Log.e(TAG, "NPU frontend initialization failed", failure)
            lastError = "NPU pipeline init failed: ${failure.message ?: failure.javaClass.simpleName}"
            runCatching { OneVoiceNative.nativeReleaseModels() }
            false
        }
    }

    /** App-process memory only; DSP-private allocations need adb/dmabuf telemetry. */
    private fun logResidentMemory(label: String, residentContexts: Int) {
        val snapshot = (telemetry ?: BenchTelemetry(appContext)).memorySnapshot()
        Log.i(
            TAG,
            "memory label=$label contexts=$residentContexts " +
                snapshot.entries.joinToString(" ") { "${it.key}=${it.value}" },
        )
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

    /** Load both direction-specific context sets under collision-free keys. */
    private fun loadStagesFor(specs: List<DirectionSpec>): Boolean {
        val stagesToLoad = specs.flatMap { spec ->
            selectedStages(spec).map { stage -> spec.id to stage }
        }
        var working = stagesToLoad.map { (_, stage) -> StageStatus(stage) }
        stages = working
        publish()

        candidateRoots.forEach {
            Log.i(TAG, "candidate ${it.absolutePath} isDir=${it.isDirectory} " +
                "children=${it.list()?.size ?: -1}")
        }
        Log.i(TAG, "using modelRoot=$modelRootPath")

        var allOk = true
        stagesToLoad.forEachIndexed { i, (directionId, stageSpec) ->
            val runtimeKey = "$directionId/${stageSpec.stage}"
            val file = stageSpec.resolve(modelRoot)
            Log.i(TAG, "stage=$runtimeKey path=${file.absolutePath} " +
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
                OneVoiceNative.loadModel(runtimeKey, file.absolutePath, stageSpec.graph)
            }.getOrElse { t ->
                ModelLoadReport(
                    stage = runtimeKey, ok = false, loadMs = 0.0, sizeBytes = 0,
                    graphName = "", socModel = 0, buildId = "",
                    inputs = emptyList(), outputs = emptyList(),
                    error = t.message ?: t.javaClass.simpleName,
                )
            }

            val contractError = if (report.ok) validateContract(stageSpec, report) else null
            // The production manifest contains only device-specific SM8550
            // contexts. Unknown (0) is not a harmless warning: accepting it
            // would let a stale/generic artifact appear loaded and fail later.
            val socError = if (report.ok && report.socModel != manifest.targetSocModel) {
                if (report.socModel == 0) {
                    "binary did not report a target socModel; expected " +
                        "${manifest.targetSocModel} (${manifest.targetSocName})"
                } else {
                    "built for socModel=${report.socModel}, " +
                        "expected ${manifest.targetSocModel} (${manifest.targetSocName})"
                }
            } else {
                null
            }
            val stageOk = report.ok && contractError == null && socError == null

            working = working.replaceAt(
                i,
                StageStatus(
                    spec = stageSpec,
                    state = if (stageOk) StageState.LOADED else StageState.FAILED,
                    report = report,
                    error = report.error ?: contractError ?: socError,
                ),
            )
            stages = working
            publish()
            if (!stageOk) {
                allOk = false
                Log.e(TAG, "stage $runtimeKey failed: ${report.error ?: contractError ?: socError}")
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

    private fun selectedStages(spec: DirectionSpec): List<StageSpec> =
        spec.stages.filter { it.stage in HYBRID_QNN_STAGES }

    private fun validateContract(spec: StageSpec, report: ModelLoadReport): String? {
        if (report.sizeBytes != spec.sizeBytes) {
            return "file size ${report.sizeBytes}, expected ${spec.sizeBytes} bytes"
        }
        if (report.graphName != spec.graph) {
            return "graph ${report.graphName}, expected ${spec.graph}"
        }

        fun mismatch(expected: List<TensorSpec>, actual: List<TensorSpec>, kind: String): String? {
            if (expected.size != actual.size) {
                return "$kind count ${actual.size}, expected ${expected.size}"
            }
            expected.indices.forEach { index ->
                if (expected[index] != actual[index]) {
                    return "$kind[$index] ${actual[index]}, expected ${expected[index]}"
                }
            }
            return null
        }
        return mismatch(spec.inputs, report.inputs, "input")
            ?: mismatch(spec.outputs, report.outputs, "output")
    }

    // ------------------------------------------------------------- lifecycle
    @SuppressLint("MissingPermission")
    @Synchronized
    override fun start(): Boolean {
        if (destroyed.get()) {
            lastError = "NPU engine has already been destroyed."
            return false
        }
        if (pipeline == null) {
            lastError = "NPU pipeline is not initialized."
            return false
        }
        val staleWorker = listOfNotNull(captureThread, pipelineThread, ttsThread)
            .firstOrNull(Thread::isAlive)
        if (!running.get() && staleWorker != null) {
            lastError = "Previous audio worker ${staleWorker.name} is still stopping."
            return false
        }
        val replay = replayThread
        if (replay?.isAlive == true) {
            replay.interrupt()
            joinWorker(replay, REPLAY_JOIN_MS)
            if (replay.isAlive) {
                lastError = "Cannot open SCO while audio replay is still draining."
                return false
            }
            if (replayThread === replay) replayThread = null
        }
        if (!running.compareAndSet(false, true)) return true
        lastError = null
        longTurnConfig = buildLongTurnConfig
        segments.clear()
        halfDuplex.reset()
        startInferenceWorkers(longTurnConfig)
        captureThread = Thread(::liveCaptureLoop, "onevoice-npu-capture").apply { start() }
        return true
    }

    /** LEGACY: one whole-utterance worker. ADAPTIVE: the long-turn inference and TTS workers. */
    private fun startInferenceWorkers(config: LongTurnConfig) {
        if (config.mode == LongTurnMode.ADAPTIVE) {
            val coordinator = LongTurnCoordinator(checkNotNull(pipeline), longTurnListener)
            longTurnCoordinator = coordinator
            pipelineThread = Thread(coordinator::runInferenceLoop, "onevoice-longturn-inference")
                .apply { start() }
            ttsThread = Thread(coordinator::runTtsLoop, "onevoice-longturn-tts").apply { start() }
        } else {
            longTurnCoordinator = null
            pipelineThread = Thread(::pipelineLoop, "onevoice-npu-pipeline").apply { start() }
        }
    }

    /**
     * Run the inference worker over a fixture instead of the microphone.
     *
     * The benchmark needs the same utterance every iteration; a live mic makes
     * every run a different input and the numbers stop being comparable. This
     * starts the real [pipelineLoop] and pushes PCM into the same bounded queue
     * the capture loop would have written to, so the selected HTP branch, TTS
     * and playback execute exactly as in production. A null [direction] runs
     * the production router (dual ASR or LID); an explicit one measures a
     * single branch.
     *
     * Playback stays enabled on purpose: AudioTrack contention and underruns
     * are part of what the gates measure.
     *
     * Returns false when the pipeline is not loaded or a live session already
     * owns the worker.
     */
    @Synchronized
    fun benchFeed(
        samples: FloatArray,
        direction: TranslationDirection?,
        forcedBoundary: Boolean = false,
        tag: String? = null,
    ): Boolean {
        if (telemetry?.enabled != true) return false
        if (pipeline == null) {
            lastError = "NPU pipeline is not initialized."
            return false
        }
        if (captureThread?.isAlive == true) {
            lastError = "benchFeed refused: microphone capture is running."
            return false
        }
        if (replayThread?.isAlive == true) {
            lastError = "benchFeed refused: audio replay is running."
            return false
        }
        if (!running.get() && (pipelineThread?.isAlive == true || ttsThread?.isAlive == true)) {
            lastError = "benchFeed refused: previous pipeline worker is still stopping."
            return false
        }
        if (running.get() && longTurnCoordinator != null) {
            lastError = "benchFeed refused: a long-turn session owns the workers."
            return false
        }
        if (running.compareAndSet(false, true)) {
            // No capture thread: the fixture is the only producer. Fixtures
            // are whole utterances, so they always run the legacy worker.
            longTurnCoordinator = null
            segments.clear()
            pipelineThread = Thread(::pipelineLoop, "onevoice-npu-pipeline").apply { start() }
        }
        val queued = CapturedSegment(
            id = turnIds.incrementAndGet(),
            samples = samples,
            enqueuedNs = System.nanoTime(),
            forcedBoundary = forcedBoundary,
            route = standalonePlaybackRoute(),
            communicationAddress = null,
            communicationName = "benchmark fixture",
            captureSetupMs = 0L,
            scoCloseMs = 0L,
            scoClosedNs = System.nanoTime(),
            benchmark = true,
            benchmarkDirection = direction,
            benchmarkTag = tag,
        )
        // Blocking put, not offer: dropping a fixture would silently shrink the
        // sample count a percentile is computed from.
        return runCatching { segments.put(queued); true }.getOrDefault(false)
    }

    /**
     * Synthesize speech with the loaded TTS so the harness can build a fixture.
     *
     * Returns 24 kHz float PCM, or null when the pipeline is not loaded. Used
     * only to author fixtures; nothing on the measured path calls it.
     */
    internal fun benchSynthesize(
        text: String,
        direction: TranslationDirection,
    ): FloatArray? {
        if (telemetry?.enabled != true) return null
        // The TTS session belongs to the inference workers while a session runs.
        if (running.get()) return null
        val active = pipeline ?: return null
        return runCatching { active.synthesize(text, direction) }.getOrNull()
    }

    @Synchronized
    override fun stop() {
        running.set(false)
        sessionGeneration.incrementAndGet()
        halfDuplex.cancel("engine_stop")
        activeLongTurn?.let { turn ->
            turn.cancelled = true
            turn.audio.cancel()
        }
        longTurnCoordinator?.cancelAll()
        activeRecord?.let { runCatching { it.stop() } }
        activePlaybackTrack?.let { active ->
            runCatching { active.pause() }
            runCatching { active.stop() }
        }
        val capture = captureThread
        val inference = pipelineThread
        val tts = ttsThread
        val replay = replayThread
        capture?.interrupt()
        inference?.interrupt()
        tts?.interrupt()
        replay?.interrupt()
        joinWorker(capture, CAPTURE_JOIN_MS)
        joinWorker(inference, PIPELINE_JOIN_MS)
        joinWorker(tts, PIPELINE_JOIN_MS)
        joinWorker(replay, REPLAY_JOIN_MS)
        if (capture?.isAlive != true && captureThread === capture) captureThread = null
        if (inference?.isAlive != true && pipelineThread === inference) pipelineThread = null
        if (tts?.isAlive != true && ttsThread === tts) ttsThread = null
        if (replay?.isAlive != true && replayThread === replay) replayThread = null
        segments.clear()
        activeLongTurn = null
    }

    @Synchronized
    override fun destroy() {
        if (!destroyed.compareAndSet(false, true)) return
        stop()
        val loaded = pipeline
        pipeline = null
        lastAudio = null
        // Native/ORT sessions are closed only after every worker that can
        // still be inside a model call (inference or TTS) has returned.
        val workers = listOfNotNull(pipelineThread, ttsThread).filter(Thread::isAlive)
        if (workers.isEmpty()) {
            releasePipeline(loaded)
        } else if (releasePending.compareAndSet(false, true)) {
            Thread({
                try {
                    workers.forEach(Thread::join)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                } finally {
                    releasePipeline(loaded)
                    releasePending.set(false)
                    if (pipelineThread in workers) pipelineThread = null
                    if (ttsThread in workers) ttsThread = null
                }
            }, "onevoice-npu-release").start()
        }
    }

    @Synchronized
    override fun replayLastAudio(): Boolean {
        val replay = lastAudio ?: return false
        // A live capture worker owns the explicit half-duplex coordinator.
        // Replay is therefore available only after capture has stopped.
        if (running.get()) return false
        if (captureThread?.isAlive == true || pipelineThread?.isAlive == true || ttsThread?.isAlive == true) {
            lastError = "Audio replay refused: previous live workers are still stopping."
            return false
        }
        if (replayThread?.isAlive == true) return false
        val thread = Thread({
            try {
                playStereo(
                    mono = replay.mono,
                    sampleRate = replay.sampleRate,
                    direction = replay.direction,
                    route = replay.route,
                    communicationAddress = replay.communicationAddress,
                    communicationName = replay.communicationName,
                    scoClosedNs = System.nanoTime(),
                    turnId = turnIds.incrementAndGet(),
                    coordinated = false,
                )
            } catch (failure: Throwable) {
                notifyPipelineError("Audio replay failed: ${failure.message ?: failure.javaClass.simpleName}")
            } finally {
                if (replayThread === Thread.currentThread()) replayThread = null
            }
        }, "onevoice-npu-replay")
        replayThread = thread
        thread.start()
        return true
    }

    // --------------------------------------------------------------- capture

    /** Microphone session: resolves the per-turn route and opens AudioRecord per turn. */
    private fun liveCaptureLoop() {
        val audioManager = appContext.getSystemService(AudioManager::class.java)
        if (audioManager == null) {
            running.set(false)
            notifyPipelineStopped("Android AudioManager is unavailable.")
            pipelineThread?.interrupt()
            ttsThread?.interrupt()
            return
        }
        val adaptive = longTurnConfig.mode == LongTurnMode.ADAPTIVE
        if (adaptive) {
            // Inference and TTS now run while the microphone is open. The read
            // loop must not be starved into an AudioRecord overrun.
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
        }
        captureLoop(LiveCaptureProvider(audioManager, largeBuffer = adaptive))
    }

    private fun captureLoop(provider: CaptureProvider) {
        var activeTurnId: Long? = null
        // One detector per session: Silero's recurrent state is reset per turn,
        // but loading the graph per turn would add latency to every capture.
        val detector = createSpeechDetector(vadMode, appContext.assets, VAD_RMS_THRESHOLD)
        val config = longTurnConfig
        Log.i(
            TAG,
            "capture VAD=${detector.name} (configured ${vadMode.name}) input=${provider.inputMode} " +
                "longTurn=${config.mode.name}",
        )
        try {
            while (running.get() && !provider.exhausted) {
                val turnId = turnIds.incrementAndGet()
                activeTurnId = turnId
                if (!halfDuplex.awaitIdleThenCapture(turnId)) break
                if (config.mode == LongTurnMode.ADAPTIVE) {
                    runLongTurn(provider, turnId, detector, config)
                    activeTurnId = null
                    continue
                }
                val captured = captureOneTurn(provider, turnId, detector)
                if (captured == null) {
                    halfDuplex.finishTurn(
                        turnId,
                        if (provider.exhausted) "input_end" else "capture_cancelled_or_noise",
                    )
                    activeTurnId = null
                    continue
                }
                halfDuplex.transition(
                    turnId,
                    HalfDuplexState.CAPTURING,
                    HalfDuplexState.PROCESSING,
                    "utterance_bounded_sco_closed",
                )
                segments.put(captured)
                halfDuplex.awaitTurnFinished(turnId)
                activeTurnId = null
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (failure: Throwable) {
            if (running.get()) {
                running.set(false)
                notifyPipelineStopped("Microphone capture failed: ${failure.message}")
                pipelineThread?.interrupt()
                ttsThread?.interrupt()
            }
        } finally {
            activeTurnId?.let { halfDuplex.finishTurn(it, "capture_worker_exit") }
            runCatching { detector.close() }
        }
    }

    /** LEGACY: one bounded utterance per capture; the 10 s ceiling closes the input. */
    private fun captureOneTurn(
        provider: CaptureProvider,
        turnId: Long,
        detector: SpeechActivityDetector,
    ): CapturedSegment? {
        val capture = provider.open(turnId) ?: return null
        var bounded: BoundedUtterance? = null
        try {
            bounded = readBoundedUtterance(capture, detector, capture.guardSamples)
        } finally {
            capture.close()
        }
        val utterance = bounded ?: return null
        return CapturedSegment(
            id = turnId,
            samples = utterance.samples,
            enqueuedNs = System.nanoTime(),
            forcedBoundary = utterance.forcedBoundary,
            route = capture.route,
            communicationAddress = capture.communicationAddress,
            communicationName = capture.communicationName,
            captureSetupMs = capture.setupMs,
            scoCloseMs = capture.scoCloseMs,
            scoClosedNs = capture.scoClosedNs,
            replayTag = provider.replayTag,
            inputMode = provider.inputMode,
        )
    }

    private inner class LiveCaptureProvider(
        private val audioManager: AudioManager,
        private val largeBuffer: Boolean,
    ) : CaptureProvider {
        override val inputMode = "microphone"
        override val replayTag: String? = null
        override val blockingSubmit = false
        override val exhausted = false
        override fun open(turnId: Long): TurnCapture {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                throw IllegalStateException("Communication-device routing requires Android 12 or newer.")
            }
            return openLiveCapture(audioManager, turnId, largeBuffer)
        }
    }

    /**
     * Resolve the route, claim SCO for Bluetooth, open AudioRecord and prove
     * the actual input device. Everything claimed is released if any step
     * fails, exactly as the single-utterance capture always did.
     */
    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.S)
    private fun openLiveCapture(
        audioManager: AudioManager,
        turnId: Long,
        largeBuffer: Boolean,
    ): LiveCapture {
        val setupStartedNs = System.nanoTime()
        // Resolved per turn, so connecting or removing a headset between turns
        // takes effect without restarting the session.
        val communication: AudioDeviceInfo? = when (val choice = resolveCaptureRoute(audioManager)) {
            RouteChoice.Phone -> null
            is RouteChoice.Bluetooth -> choice.device
            is RouteChoice.Unavailable -> error(choice.reason)
        }
        val route = if (communication == null) AudioRouteKind.PHONE else AudioRouteKind.BLUETOOTH
        if (communication != null) {
            check(audioManager.setCommunicationDevice(communication)) {
                "Android rejected the Bluetooth communication route ${communication.productName}."
            }
        }
        var record: AudioRecord? = null
        try {
            val minimumBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            check(minimumBuffer > 0) { "AudioRecord does not support 16 kHz mono PCM." }
            // A long turn keeps reading while inference and TTS load the CPU;
            // a 2 s buffer absorbs scheduling stalls the 250 ms one could not.
            val bufferBytes = maxOf(
                minimumBuffer,
                if (largeBuffer) LONG_TURN_RECORD_BUFFER_BYTES else SAMPLE_RATE / 2,
            )
            val active = AudioRecord(
                // HFP needs the communication source to open SCO. The built-in
                // mic uses VOICE_RECOGNITION: half-duplex already removes the
                // echo AEC would cancel, and ASR prefers less call processing.
                if (communication != null) {
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION
                } else {
                    MediaRecorder.AudioSource.VOICE_RECOGNITION
                },
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
            )
            record = active
            check(active.state == AudioRecord.STATE_INITIALIZED) {
                "AudioRecord initialization failed"
            }
            activeRecord = active
            val builtInMic = if (communication == null) builtInMicrophone(audioManager) else null
            builtInMic?.let(active::setPreferredDevice)
            active.startRecording()
            val routedInput = if (communication != null) {
                awaitBluetoothInputRoute(active, communication)
            } else {
                awaitPhoneInputRoute(active)
            }
            val setupMs = (System.nanoTime() - setupStartedNs) / 1_000_000L
            lastRouteLabel = routeLabel(
                route,
                communication?.productName?.toString()?.trim().orEmpty()
                    .ifEmpty { "Bluetooth headset" },
            )
            Log.i(
                HALF_DUPLEX_TAG,
                "turn=$turnId route=${route.name.lowercase(Locale.ROOT)} captureSetupMs=$setupMs " +
                    "requested=${describeDevice(communication ?: builtInMic)} " +
                    "routed=${describeDevice(routedInput)} sampleRate=${active.sampleRate}",
            )
            return LiveCapture(
                audioManager = audioManager,
                turnId = turnId,
                record = active,
                communication = communication,
                route = route,
                setupMs = setupMs,
                bufferFrames = bufferBytes / Short.SIZE_BYTES,
                trackLag = largeBuffer,
            )
        } catch (failure: Throwable) {
            record?.let { active ->
                runCatching {
                    if (active.recordingState == AudioRecord.RECORDSTATE_RECORDING) active.stop()
                }
                runCatching { active.release() }
            }
            if (activeRecord === record) activeRecord = null
            if (communication != null) runCatching { audioManager.clearCommunicationDevice() }
            throw failure
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private inner class LiveCapture(
        private val audioManager: AudioManager,
        private val turnId: Long,
        private val record: AudioRecord,
        private val communication: AudioDeviceInfo?,
        override val route: AudioRouteKind,
        override val setupMs: Long,
        private val bufferFrames: Int,
        private val trackLag: Boolean,
    ) : TurnCapture {
        override val communicationAddress: String? = communication?.let { visibleAddress(it.address) }
        override val communicationName: String = communication?.productName?.toString()?.trim()
            ?.ifEmpty { "Bluetooth headset" }
            ?: PHONE_ROUTE_NAME
        // The loudspeaker tail of the previous translation reaches the
        // built-in mic after AudioTrack reports drained; a headset does
        // not. Guarded audio cannot open a turn but is kept as pre-roll.
        override val guardSamples: Int = if (communication == null) PHONE_ECHO_GUARD_SAMPLES else 0
        override var scoCloseMs: Long = 0L
            private set
        override var scoClosedNs: Long = 0L
            private set
        override var maxReadLagMs: Long = 0L
            private set
        override var overrunSuspected: Int = 0
            private set
        private var closed = false
        private var framesRead = 0L
        private var reads = 0
        private val timestamp = AudioTimestamp()

        override fun read(frame: ShortArray): Int {
            val count = record.read(frame, 0, frame.size)
            if (count < 0) error("AudioRecord.read failed with code $count")
            framesRead += count
            if (trackLag && ++reads % READ_LAG_CHECK_READS == 0 &&
                record.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS
            ) {
                // Frames the HAL captured that this loop has not read yet. At
                // the buffer size, AudioRecord starts overwriting audio.
                val lag = (timestamp.framePosition - framesRead).coerceAtLeast(0L)
                maxReadLagMs = maxOf(maxReadLagMs, lag * 1_000L / SAMPLE_RATE)
                if (lag >= bufferFrames) overrunSuspected++
            }
            return count
        }

        override fun close() {
            if (closed) return
            closed = true
            val closeStartedNs = System.nanoTime()
            runCatching {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
            }
            runCatching { record.release() }
            if (activeRecord === record) activeRecord = null
            // Always release the app-owned communication route. Keeping it set
            // leaves the TWS in SCO/call mode even if AudioRecord is gone. The
            // phone route never claimed one, so it must not clear another app's.
            if (communication != null) runCatching { audioManager.clearCommunicationDevice() }
            scoClosedNs = System.nanoTime()
            scoCloseMs = (scoClosedNs - closeStartedNs) / 1_000_000L
            Log.i(HALF_DUPLEX_TAG, "turn=$turnId scoCloseMs=$scoCloseMs")
        }
    }

    // ------------------------------------------------------------ long turns

    private fun archiveFor(config: LongTurnConfig): TurnArchive =
        turnArchive?.takeIf { it.capacity == config.archiveCapacitySamples }
            ?: TurnArchive(config.archiveCapacitySamples).also { turnArchive = it }

    /**
     * ADAPTIVE turn: the microphone stays open across ASR windows while the
     * coordinator recognizes, translates and synthesizes them. When the turn
     * ends the input is closed (SCO released), and only then does this same
     * thread prove the output route and play, so capture and playback can
     * never overlap. The next turn starts only after every worker finished
     * with this one, which is also what makes reusing the archive safe.
     */
    private fun runLongTurn(
        provider: CaptureProvider,
        turnId: Long,
        detector: SpeechActivityDetector,
        config: LongTurnConfig,
    ) {
        val coordinator = checkNotNull(longTurnCoordinator) { "long-turn workers are not running" }
        val capture = provider.open(turnId)
        if (capture == null) {
            halfDuplex.finishTurn(turnId, "input_end")
            return
        }
        val archive = archiveFor(config)
        archive.clear()
        val turn = LongTurn(turnId, sessionGeneration.get(), archive, config, System.nanoTime())
        activeLongTurn = turn
        var ended: SegmenterEvent.TurnEnded? = null
        var turnOpened = false
        try {
            val segmenter = TurnSegmenter(
                detector = detector,
                archive = archive,
                config = config,
                guardSamples = capture.guardSamples,
                log = { Log.i(TAG, it) },
            )
            val frame = ShortArray(FRAME_SAMPLES)
            val vadLog = VadDebugLog(detector)
            var inputEnded = false
            while (running.get() && ended == null && !inputEnded) {
                val abort = turn.abortRequest
                val events: List<SegmenterEvent> = if (abort != null) {
                    // Inference failed closed (e.g. routing rejected): stop
                    // capturing now instead of recording a turn nobody will hear.
                    listOfNotNull(segmenter.abort(abort))
                } else {
                    val count = capture.read(frame)
                    if (count == 0) continue
                    if (count < 0) {
                        // End of a replay: flush an open turn, else there is nothing left.
                        inputEnded = true
                        segmenter.finish(TurnEndReason.INPUT_END)
                    } else {
                        val live = !segmenter.guarding
                        segmenter.accept(frame, count).also {
                            vadLog.onFrame(live, segmenter.lastFrameSpeech)
                        }
                    }
                }
                turn.capturedSamples = archive.size
                turnOpened = turnOpened || segmenter.turnOpen || events.isNotEmpty()
                for (event in events) {
                    if (event is SegmenterEvent.TurnEnded) {
                        ended = event
                        turn.turnEndDetectedNs = System.nanoTime()
                        // The terminal event must reach the worker even under
                        // backlog: it is what releases this turn.
                        coordinator.submitBlocking(turn, event)
                    } else if (provider.blockingSubmit) {
                        coordinator.submitBlocking(turn, event)
                    } else if (!coordinator.submit(turn, event)) {
                        turn.error = "Long-turn inference backlog overflow"
                        turn.abortRequest = TurnEndReason.FAILED
                    }
                }
                if (abort != null && events.isEmpty()) break
            }
        } finally {
            capture.close()
            turn.captureClosedNs = capture.scoClosedNs
        }

        if (ended == null) {
            if (turnOpened) {
                // Stopped while a turn was open: release the worker, report nothing played.
                turn.cancelled = true
                turn.audio.cancel()
                runCatching {
                    coordinator.submitBlocking(
                        turn,
                        SegmenterEvent.TurnEnded(TurnEndReason.CANCELLED, archive.size, archive.size, 0),
                    )
                }
            }
            halfDuplex.finishTurn(turnId, if (provider.exhausted) "input_end" else "capture_cancelled_or_noise")
            activeLongTurn = null
            return
        }
        val terminal = checkNotNull(ended)
        var playback: LongTurnPlayback? = null
        val failedClosed = terminal.reason == TurnEndReason.ROUTING_REJECTED ||
            terminal.reason == TurnEndReason.FAILED || turn.error != null
        if (!failedClosed && running.get()) {
            halfDuplex.transition(
                turnId,
                HalfDuplexState.CAPTURING,
                HalfDuplexState.PROCESSING,
                "long_turn_ended_capture_closed",
            )
            playback = try {
                playLongTurn(turn, capture, allowSoleFallback = provider.replayTag != null)
            } catch (interrupted: InterruptedException) {
                throw interrupted
            } catch (failure: Throwable) {
                Log.e(TAG, "Long turn $turnId playback failed", failure)
                val message = "Playback failed: ${failure.message ?: failure.javaClass.simpleName}"
                if (turn.error == null) turn.error = message
                notifyPipelineError(message)
                null
            }
        }
        while (!turn.awaitWorker(LONG_TURN_WORKER_POLL_MS) && running.get()) Unit
        val complete = playback?.completed == true && turn.error == null
        val direction = turn.direction
        if (complete && direction != null) {
            // Once per turn, after it was heard in full. A turn cut by the cap
            // expects the same speaker to continue; otherwise the other one.
            pipeline?.commitTurn(direction, sameSpeakerNext = terminal.reason == TurnEndReason.TURN_CAP)
        }
        if (terminal.reason == TurnEndReason.TURN_CAP && turn.error == null) {
            notifyPipelineError(
                "Turn reached the ${config.maxTurnSamples / SAMPLE_RATE} s limit; " +
                    "speech after it was not captured.",
            )
        }
        reportLongTurn(turn, terminal, capture, playback, provider)
        halfDuplex.finishTurn(turnId, turn.error?.let { "long_turn_failed" } ?: "turn_complete")
        activeLongTurn = null
    }

    private class LongTurnPlayback(
        val a2dpRestoreMs: Long,
        val routePollMs: Long,
        val playbackMs: Long,
        val unitsPlayed: Int,
        /** Content frames written after the route proof, pauses included. */
        val framesWritten: Long,
        val underruns: Int,
        /** Time the track had played everything written while the next unit was not ready. */
        val starvedMs: Long,
        val completed: Boolean,
        val outcome: String,
    )

    /**
     * One AudioTrack for the whole turn: route proof once, then every unit is
     * appended in order with a short pause between units. The proof runs while
     * the last units are still being synthesized. Stereo frames are staged in
     * small blocks instead of one turn-sized interleaved copy.
     */
    private fun playLongTurn(
        turn: LongTurn,
        capture: TurnCapture,
        allowSoleFallback: Boolean,
    ): LongTurnPlayback {
        var locked = false
        var track: AudioTrack? = null
        val replay = ReplayAccumulator(turn.config.replayMaxBytes)
        try {
            playbackLock.lockInterruptibly(); locked = true
            val proven = openProvenTrack(
                sampleRate = TTS_SAMPLE_RATE,
                bufferFrames = TTS_SAMPLE_RATE,
                route = capture.route,
                communicationAddress = capture.communicationAddress,
                communicationName = capture.communicationName,
                scoClosedNs = capture.scoClosedNs,
                turnId = turn.turnId,
                coordinated = true,
                allowSoleFallback = allowSoleFallback,
            )
            turn.routeConfirmedNs = System.nanoTime()
            val activeTrack = proven.track
            track = activeTrack
            val scratch = FloatArray(PLAYBACK_BLOCK_FRAMES * 2)
            val pauseFrames = turn.config.unitPauseMs * TTS_SAMPLE_RATE / 1_000
            var unitsPlayed = 0
            var written = 0L
            var baselineUnderruns = 0
            var starvedMs = 0L
            var playbackStartedNs = 0L
            fun delivered(): Long =
                ((activeTrack.playbackHeadPosition.toLong() and 0xffff_ffffL) - proven.contentStartFrame)
                    .coerceAtLeast(0L)
            var outcome = "stopped"
            loop@ while (true) {
                when (val take = turn.audio.take(LONG_TURN_PLAYBACK_POLL_MS)) {
                    is AudioTake.Item -> {
                        val audio = take.audio
                        if (audio.revision != turn.revision) continue@loop
                        val direction = checkNotNull(turn.direction)
                        if (unitsPlayed == 0) {
                            // Lost the race with a CPU recovery: its units replace this one.
                            if (!turn.markPlaybackStarted()) continue@loop
                            baselineUnderruns = activeTrack.underrunCount
                            halfDuplex.transition(
                                turn.turnId,
                                HalfDuplexState.PROCESSING,
                                HalfDuplexState.PLAYING,
                                if (capture.route == AudioRouteKind.PHONE) "speaker_route_ready" else "a2dp_route_confirmed",
                            )
                            playbackStartedNs = System.nanoTime()
                            turn.firstContentWriteNs = playbackStartedNs
                        } else {
                            written += writeSilence(activeTrack, pauseFrames, scratch)
                            replay.appendSilence(pauseFrames)
                        }
                        written += writeStereoBlocks(activeTrack, audio.mono, direction, capture.route, scratch)
                        replay.append(audio.mono)
                        unitsPlayed++
                    }
                    AudioTake.Finished -> {
                        outcome = "complete"
                        break@loop
                    }
                    is AudioTake.Failed -> {
                        outcome = if (unitsPlayed > 0) "partial_failure" else "failed"
                        break@loop
                    }
                    AudioTake.Cancelled -> {
                        outcome = "cancelled"
                        break@loop
                    }
                    AudioTake.Timeout -> {
                        if (!running.get()) break@loop
                        if (unitsPlayed > 0 && delivered() >= written) starvedMs += LONG_TURN_PLAYBACK_POLL_MS
                    }
                }
            }
            if (unitsPlayed > 0) {
                val remainingMs = (written - delivered()).coerceAtLeast(0L) * 1_000L / TTS_SAMPLE_RATE
                val deadlineNs = System.nanoTime() + (remainingMs + PLAYBACK_DRAIN_GRACE_MS) * 1_000_000L
                while (delivered() < written &&
                    activeTrack.playState == AudioTrack.PLAYSTATE_PLAYING &&
                    System.nanoTime() < deadlineNs
                ) {
                    Thread.sleep(PLAYBACK_DRAIN_POLL_MS)
                }
            }
            val drained = delivered() >= written
            val underruns = if (unitsPlayed > 0) activeTrack.underrunCount - baselineUnderruns else 0
            telemetry?.noteAudio(requested = written, written = written, underrun = underruns > 0)
            Log.i(
                TAG,
                "Long-turn playback turn=${turn.turnId} outcome=$outcome units=$unitsPlayed " +
                    "frames=$written delivered=${delivered()} underruns=$underruns starvedMs=$starvedMs " +
                    "targetChannel=${targetChannelLabel(capture.route, turn.direction)}",
            )
            val played = if (unitsPlayed > 0) replay.result() else null
            turn.replayAudio = played
            if (played != null) {
                played.let { mono ->
                    lastAudio = ReplayAudio(
                        mono = mono,
                        sampleRate = TTS_SAMPLE_RATE,
                        direction = checkNotNull(turn.direction),
                        route = capture.route,
                        communicationAddress = capture.communicationAddress,
                        communicationName = capture.communicationName,
                    )
                    runCatching { pipelineListener?.onAudioReady() }
                }
            }
            return LongTurnPlayback(
                a2dpRestoreMs = proven.a2dpRestoreMs,
                routePollMs = proven.routePollMs,
                playbackMs = if (playbackStartedNs == 0L) 0L else (System.nanoTime() - playbackStartedNs) / 1_000_000L,
                unitsPlayed = unitsPlayed,
                framesWritten = written,
                underruns = underruns,
                starvedMs = starvedMs,
                completed = outcome == "complete" && unitsPlayed > 0 && drained,
                outcome = if (outcome == "complete" && !drained) "drain_timeout" else outcome,
            )
        } finally {
            track?.let { runCatching { it.stop() }; runCatching { it.release() } }
            if (activePlaybackTrack === track) activePlaybackTrack = null
            if (locked) playbackLock.unlock()
        }
    }

    private fun targetChannelLabel(route: AudioRouteKind, direction: TranslationDirection?): String = when {
        route == AudioRouteKind.PHONE -> "both_speaker"
        direction == TranslationDirection.EN_TO_VI -> "left_vi"
        else -> "right_en"
    }

    /** Mono -> stereo in fixed blocks: target VI left, EN right on Bluetooth; both on the phone. */
    private fun writeStereoBlocks(
        track: AudioTrack,
        mono: FloatArray,
        direction: TranslationDirection,
        route: AudioRouteKind,
        scratch: FloatArray,
    ): Long {
        val blockFrames = scratch.size / 2
        val target = if (direction == TranslationDirection.EN_TO_VI) 0 else 1
        var frame = 0
        while (frame < mono.size) {
            val count = minOf(blockFrames, mono.size - frame)
            for (index in 0 until count) {
                val sample = mono[frame + index]
                if (route == AudioRouteKind.PHONE) {
                    scratch[index * 2] = sample
                    scratch[index * 2 + 1] = sample
                } else {
                    scratch[index * 2 + target] = sample
                    scratch[index * 2 + 1 - target] = 0f
                }
            }
            writeFully(track, scratch, count * 2)
            frame += count
        }
        return mono.size.toLong()
    }

    private fun writeSilence(track: AudioTrack, frames: Int, scratch: FloatArray): Long {
        java.util.Arrays.fill(scratch, 0f)
        var remaining = frames
        while (remaining > 0) {
            val count = minOf(scratch.size / 2, remaining)
            writeFully(track, scratch, count * 2)
            remaining -= count
        }
        return frames.toLong()
    }

    /** A blocking write may still accept part of the buffer; loop until all floats are in. */
    private fun writeFully(track: AudioTrack, data: FloatArray, floats: Int) {
        var offset = 0
        while (offset < floats) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val count = track.write(data, offset, floats - offset, AudioTrack.WRITE_BLOCKING)
            check(count > 0) { "AudioTrack.write failed with code $count" }
            offset += count
        }
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.S)
    private fun resolveCaptureRoute(audioManager: AudioManager): RouteChoice<AudioDeviceInfo> {
        val candidates = audioManager.availableCommunicationDevices.filter { device ->
            device.isSink && isBluetoothCommunicationType(device.type)
        }
        val current = audioManager.communicationDevice?.let { active ->
            candidates.firstOrNull { it.id == active.id }
        }
        return chooseAudioRoute(audioRoutePreference, candidates, current)
    }

    /**
     * Route for playback that has no capture turn (replay, bench fixtures,
     * smoke): honor PHONE, otherwise use Bluetooth media only when one exists.
     */
    private fun standalonePlaybackRoute(): AudioRouteKind {
        if (audioRoutePreference == AudioRoutePreference.PHONE) return AudioRouteKind.PHONE
        val audioManager = appContext.getSystemService(AudioManager::class.java)
        val hasA2dp = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            ?.any { it.isSink && it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP } == true
        return if (hasA2dp || audioRoutePreference == AudioRoutePreference.BLUETOOTH) {
            AudioRouteKind.BLUETOOTH
        } else {
            AudioRouteKind.PHONE
        }
    }

    private fun builtInMicrophone(audioManager: AudioManager): AudioDeviceInfo? =
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.isSource && it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }

    private fun builtInSpeaker(audioManager: AudioManager): AudioDeviceInfo? =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.isSink && it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }

    /**
     * The phone route must not silently capture from a Bluetooth headset: that
     * would make the per-turn route label and the echo guard wrong.
     */
    private fun awaitPhoneInputRoute(record: AudioRecord): AudioDeviceInfo {
        val deadlineNs = System.nanoTime() + MIC_ROUTE_TIMEOUT_MS * 1_000_000L
        var routed = record.routedDevice
        while (running.get() && routed == null && System.nanoTime() < deadlineNs) {
            Thread.sleep(ROUTE_POLL_MS)
            routed = record.routedDevice
        }
        check(routed != null && !isBluetoothCommunicationType(routed.type)) {
            "Phone capture did not open a built-in microphone: routed=${describeDevice(routed)}"
        }
        return routed
    }

    private fun awaitBluetoothInputRoute(
        record: AudioRecord,
        requested: AudioDeviceInfo,
    ): AudioDeviceInfo {
        val deadlineNs = System.nanoTime() + MIC_ROUTE_TIMEOUT_MS * 1_000_000L
        var routed = record.routedDevice
        while (running.get() &&
            (routed == null || !isBluetoothCaptureMatch(requested, routed)) &&
            System.nanoTime() < deadlineNs
        ) {
            Thread.sleep(ROUTE_POLL_MS)
            routed = record.routedDevice
        }
        check(routed != null && isBluetoothCaptureMatch(requested, routed)) {
            "AudioRecord route did not match requested Bluetooth headset: " +
                "requested=${describeDevice(requested)} routed=${describeDevice(routed)}"
        }
        return routed
    }

    private fun readBoundedUtterance(
        capture: TurnCapture,
        detector: SpeechActivityDetector,
        guardSamples: Int = 0,
    ): BoundedUtterance? {
        val frame = ShortArray(FRAME_SAMPLES)
        val segmenter = UtteranceSegmenter(detector, guardSamples, log = { Log.i(TAG, it) })
        val vadLog = VadDebugLog(detector)
        while (running.get()) {
            val count = capture.read(frame)
            if (count < 0) return null
            if (count == 0) continue
            val live = !segmenter.guarding
            val utterance = segmenter.accept(frame, count)
            vadLog.onFrame(live, segmenter.lastFrameSpeech)
            if (utterance != null) return utterance
        }
        return null
    }

    /** One line per second of capture: peak detector score and speech frames. Debug builds only. */
    private class VadDebugLog(private val detector: SpeechActivityDetector) {
        private var frames = 0
        private var speechFrames = 0
        private var peakScore = 0f

        fun onFrame(live: Boolean, speech: Boolean) {
            if (!BuildConfig.DEBUG || !live) return
            peakScore = maxOf(peakScore, detector.lastScore)
            if (speech) speechFrames++
            if (++frames == VAD_LOG_FRAMES) {
                Log.d(
                    VAD_TAG,
                    "${detector.name} peak=${"%.3f".format(peakScore)} speechFrames=$speechFrames/$frames",
                )
                frames = 0
                speechFrames = 0
                peakScore = 0f
            }
        }
    }

    /**
     * Assemble one benchmark row.
     *
     * TTFA equals total compute here, not a first-chunk time: Kokoro runs
     * whole-utterance ([NpuPipeline.synthesize]) and playback only starts once
     * the full waveform exists, so nothing reaches the speaker earlier. e2e then
     * adds the audio's own duration.
     *
     * The 300 ms echo guard and 1 s hangover are reported beside e2e rather
     * than inside it. They are fixed VAD policy; folding them in would make a
     * genuine model speed-up look like no change at all.
     */
    private fun recordBenchRow(
        captured: CapturedSegment,
        direction: TranslationDirection,
        routing: RoutingDiagnostics,
        textResult: HybridTextResult,
        translation: String,
        ttsMs: Long,
        queueWaitMs: Long,
        audioSamples: Int,
    ) {
        val bench = telemetry ?: return
        if (!bench.enabled) return
        val (underruns, requested, written) = bench.audioCounters()
        val ttfaMs = textResult.asrMs + textResult.mtMs + ttsMs
        val audioSeconds = audioSamples.toDouble() / TTS_SAMPLE_RATE
        bench.record(
            UtteranceMetrics(
                direction = direction.manifestId,
                // Decision reason (explicit/margin/prior/lid) so a routed bench
                // run can be scored for misroutes against the fixture language.
                routeSource = routing.reason.logValue,
                speakerLanguage = when (direction) {
                    TranslationDirection.VI_TO_EN -> "vi"
                    TranslationDirection.EN_TO_VI -> "en"
                },
                speechDurationS = captured.samples.size.toDouble() / SAMPLE_RATE,
                forcedBoundary = captured.forcedBoundary,
                executionPath = textResult.executionPath.name,
                asrMs = textResult.asrMs,
                mtMs = textResult.mtMs,
                ttsMs = ttsMs,
                queueWaitMs = queueWaitMs,
                ttfaMs = ttfaMs,
                e2eMs = ttfaMs + (audioSeconds * 1000.0).toLong(),
                hangoverMs = END_SILENCE_FRAMES * 20L,
                echoGuardMs = PRE_ROLL_SAMPLES * 1000L / SAMPLE_RATE,
                mtOutputTokens = pipeline?.lastMtOutputTokens(direction) ?: 0,
                ttsOutputChars = translation.length,
                ttsOutputAudioS = audioSeconds,
                thermalStatus = runCatching { OneVoiceNative.nativeThermalStatus() }
                    .getOrDefault(-1),
                powerMode = runCatching { OneVoiceNative.nativeActivePowerMode() }
                    .getOrDefault("unavailable"),
                underruns = underruns,
                framesRequested = requested,
                framesWritten = written,
            ),
        )
    }

    private fun pipelineLoop() {
        try {
            while (running.get()) {
                val captured = segments.take()
                val processingStartedNs = System.nanoTime()
                val queueWaitMs =
                    (processingStartedNs - captured.enqueuedNs) / 1_000_000L
                var routing: RoutingDiagnostics? = null
                var direction: TranslationDirection? = null
                var textResult: HybridTextResult? = null
                var ttsMs = 0L
                var ttsAudioMs: Long? = null
                var ttsAudio: FloatArray? = null
                var playback: PlaybackMetrics? = null
                var turnError: String? = null
                try {
                    val active = checkNotNull(pipeline) { "Hybrid pipeline was released" }
                    lastError = null
                    if (captured.benchmarkDirection != null) {
                        direction = captured.benchmarkDirection
                        val explicitText = active.recognizeAndTranslate(
                            captured.samples,
                            checkNotNull(direction),
                        )
                        textResult = explicitText
                        routing = RoutingDiagnostics(
                            mode = active.routingMode,
                            dualAsrWallMs = explicitText.asrMs,
                            decision = direction,
                            reason = RoutingDecisionReason.EXPLICIT,
                        )
                    } else {
                        val routed = active.routeAndTranslate(
                            captured.samples,
                            captured.forcedBoundary,
                        )
                        routing = routed.routing
                        direction = routed.direction
                        textResult = routed.text
                    }

                    val selectedDirection = direction
                    val selectedText = textResult
                    val selectedRouting = checkNotNull(routing)
                    if (selectedDirection == null || selectedText == null) {
                        turnError =
                            "Routing rejected the utterance (${selectedRouting.reason.logValue})" +
                                (selectedRouting.error?.let { ": $it" } ?: "")
                        notifyPipelineError(turnError)
                        continue
                    }
                    val transcript = selectedText.transcript
                    if (transcript.isEmpty()) {
                        turnError = "ASR produced an empty transcript."
                        notifyPipelineError(turnError)
                        continue
                    }
                    notifySegment(
                        transcript,
                        null,
                        selectedDirection,
                        selectedText.executionPath,
                    )
                    val translation = selectedText.translation
                    notifySegment(
                        transcript,
                        translation,
                        selectedDirection,
                        selectedText.executionPath,
                    )
                    if (translation.isEmpty()) {
                        turnError = "Translation produced an empty result."
                        notifyPipelineError(turnError)
                        continue
                    }
                    val ttsStartedNs = System.nanoTime()
                    val audio = active.synthesize(translation, selectedDirection)
                    ttsMs = (System.nanoTime() - ttsStartedNs) / 1_000_000L
                    if (audio.isEmpty()) {
                        turnError = "Kokoro returned an empty waveform."
                        notifyPipelineError(turnError)
                        continue
                    }
                    val audioMs = audio.size * 1_000L / TTS_SAMPLE_RATE
                    ttsAudioMs = audioMs
                    ttsAudio = audio
                    Log.i(
                        TAG,
                        "Pipeline direction=$selectedDirection transcript=\"$transcript\" " +
                            "translation=\"$translation\" audioSamples=${audio.size} " +
                            "audioMs=$audioMs routing=${selectedRouting.mode} " +
                            "queueWaitMs=$queueWaitMs " +
                            "path=${selectedText.executionPath} asrMs=${selectedText.asrMs} " +
                            "mtMs=${selectedText.mtMs} ttsMs=$ttsMs",
                    )
                    lastAudio = ReplayAudio(
                        mono = audio,
                        sampleRate = TTS_SAMPLE_RATE,
                        direction = selectedDirection,
                        route = captured.route,
                        communicationAddress = captured.communicationAddress,
                        communicationName = captured.communicationName,
                    )
                    runCatching { pipelineListener?.onAudioReady() }
                    if (running.get()) {
                        playback = playStereo(
                            mono = audio,
                            sampleRate = TTS_SAMPLE_RATE,
                            direction = selectedDirection,
                            route = captured.route,
                            communicationAddress = captured.communicationAddress,
                            communicationName = captured.communicationName,
                            scoClosedNs = captured.scoClosedNs,
                            turnId = captured.id,
                            coordinated = !captured.benchmark,
                            allowSoleFallback = captured.benchmark || captured.replayTag != null,
                        )
                    }
                    recordBenchRow(
                        captured = captured,
                        direction = selectedDirection,
                        routing = selectedRouting,
                        textResult = selectedText,
                        translation = translation,
                        ttsMs = ttsMs,
                        queueWaitMs = queueWaitMs,
                        audioSamples = audio.size,
                    )
                } catch (_: InterruptedException) {
                    throw InterruptedException()
                } catch (failure: Throwable) {
                    turnError = failure.message ?: failure.javaClass.simpleName
                    Log.e(TAG, "Hybrid turn ${captured.id} failed closed", failure)
                    notifyPipelineError("Hybrid turn failed closed: $turnError")
                } finally {
                    val totalMs = (System.nanoTime() - processingStartedNs) / 1_000_000L
                    val firstAudioMs = playback?.let {
                        (it.firstAudioNs - captured.enqueuedNs).coerceAtLeast(0L) / 1_000_000L
                    }
                    val row = logTuningRow(
                        captured = captured,
                        routing = routing,
                        direction = direction,
                        text = textResult,
                        ttsMs = ttsMs,
                        queueWaitMs = queueWaitMs,
                        playback = playback,
                        firstAudioMs = firstAudioMs,
                        totalMs = totalMs,
                        error = turnError,
                    )
                    // After every timing above is final, so the disk write is
                    // never inside a measured interval.
                    captured.benchmarkTag?.let { tag ->
                        recordTaggedFixture(tag, row, textResult, ttsAudioMs, ttsAudio)
                    }
                    if (!captured.benchmark) {
                        reportTurn(
                            row = row,
                            captured = captured,
                            routing = routing,
                            direction = direction,
                            text = textResult,
                            ttsMs = ttsMs,
                            ttsAudioMs = ttsAudioMs,
                            queueWaitMs = queueWaitMs,
                            firstAudioMs = firstAudioMs,
                            totalMs = totalMs,
                            error = turnError ?: routing?.error,
                        )
                        halfDuplex.finishTurn(captured.id, turnError ?: "turn_complete")
                    }
                }
            }
        } catch (_: InterruptedException) {
            // stop requested
        } catch (failure: Throwable) {
            Log.e(TAG, "NPU pipeline failed", failure)
            if (running.getAndSet(false)) {
                notifyPipelineStopped("NPU pipeline failed: ${failure.message ?: failure.javaClass.simpleName}")
                captureThread?.interrupt()
            }
        }
    }

    private data class PlaybackMetrics(
        val a2dpRestoreMs: Long,
        val routePollMs: Long,
        val playbackMs: Long,
        /** When the first translated sample was handed to the proven route. */
        val firstAudioNs: Long,
    )

    /** A playing AudioTrack whose actual output route has been proven. */
    private class ProvenTrack(
        val track: AudioTrack,
        /** Stream frame where content starts: the route-proof silence already written. */
        val contentStartFrame: Long,
        val a2dpRestoreMs: Long,
        val routePollMs: Long,
    )

    /**
     * Build a stereo float track, feed it route-proof silence, start it and
     * poll the routed device. No translated sample is written before this
     * returns; on failure the track is released here.
     */
    private fun openProvenTrack(
        sampleRate: Int,
        bufferFrames: Int,
        route: AudioRouteKind,
        communicationAddress: String?,
        communicationName: String,
        scoClosedNs: Long,
        turnId: Long,
        coordinated: Boolean,
        allowSoleFallback: Boolean,
    ): ProvenTrack {
        var track: AudioTrack? = null
        try {
            val audioManager = checkNotNull(
                appContext.getSystemService(AudioManager::class.java),
            ) { "Android AudioManager is unavailable" }
            val routeDeadlineNs = System.nanoTime() + A2DP_ROUTE_TIMEOUT_MS * 1_000_000L
            val discoveryStartedNs = System.nanoTime()
            val mediaDevice = when (route) {
                AudioRouteKind.PHONE -> checkNotNull(builtInSpeaker(audioManager)) {
                    "No built-in loudspeaker output is available."
                }
                AudioRouteKind.BLUETOOTH -> try {
                    awaitMediaOutput(
                        audioManager = audioManager,
                        communicationAddress = communicationAddress,
                        communicationName = communicationName,
                        deadlineNs = routeDeadlineNs,
                        allowSoleFallback = allowSoleFallback,
                    )
                } catch (failure: Throwable) {
                    Log.w(
                        HALF_DUPLEX_TAG,
                        "turn=$turnId a2dpDiscoveryMs=" +
                            "${(System.nanoTime() - discoveryStartedNs) / 1_000_000L} " +
                            "result=failed reason=${failure.message}",
                    )
                    throw failure
                }
            }
            fun routeConfirmed(actual: AudioDeviceInfo?): Boolean = when (route) {
                AudioRouteKind.PHONE -> actual?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                AudioRouteKind.BLUETOOTH -> sameMediaDevice(mediaDevice, actual)
            }
            val minimum = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_FLOAT,
            )
            check(minimum > 0) {
                "AudioTrack does not support $sampleRate Hz stereo float PCM"
            }
            val requestedBufferFrames = maxOf(ROUTE_PRIME_FRAMES, bufferFrames)
            val activeTrack = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(
                    maxOf(minimum, requestedBufferFrames * 2 * Float.SIZE_BYTES),
                )
                .build()
            track = activeTrack
            activePlaybackTrack = activeTrack
            check(activeTrack.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack init failed" }
            check(activeTrack.setPreferredDevice(mediaDevice)) {
                "AudioTrack rejected preferred output ${describeDevice(mediaDevice)}"
            }

            // AudioTrack has no routed device until it is active. Feed silence,
            // start it, and poll the actual route. No translated sample is
            // submitted until this proof succeeds.
            val routePrimeFrames = minOf(
                activeTrack.bufferSizeInFrames.coerceAtLeast(1),
                ROUTE_PRIME_FRAMES,
            )
            val routeSilence = FloatArray(routePrimeFrames * 2)
            val routePrimed = activeTrack.write(
                routeSilence,
                0,
                routeSilence.size,
                AudioTrack.WRITE_BLOCKING,
            )
            check(routePrimed == routeSilence.size) {
                "AudioTrack route prime accepted $routePrimed/${routeSilence.size} floats"
            }
            val routePollStartedNs = System.nanoTime()
            activeTrack.play()
            var routed = activeTrack.routedDevice
            while (runningOrStandalone(coordinated) &&
                !routeConfirmed(routed) &&
                System.nanoTime() < routeDeadlineNs
            ) {
                Thread.sleep(ROUTE_POLL_MS)
                routed = activeTrack.routedDevice
            }
            val routePollMs = (System.nanoTime() - routePollStartedNs) / 1_000_000L
            if (!routeConfirmed(routed)) {
                Log.w(
                    HALF_DUPLEX_TAG,
                    "turn=$turnId route=${route.name.lowercase(Locale.ROOT)} " +
                        "routePollMs=$routePollMs result=timeout " +
                        "expected=${describeDevice(mediaDevice)} routed=${describeDevice(routed)}",
                )
            }
            // Only the Bluetooth route is a privacy/route-proof contract. On the
            // phone route the text is also on screen, so an unexpected output
            // (e.g. a wired headset) is logged rather than dropping the turn.
            check(route == AudioRouteKind.PHONE || routeConfirmed(routed)) {
                "A2DP route was not restored within ${A2DP_ROUTE_TIMEOUT_MS} ms: " +
                    "expected=${describeDevice(mediaDevice)} routed=${describeDevice(routed)}"
            }
            val a2dpRestoreMs =
                (System.nanoTime() - scoClosedNs).coerceAtLeast(0L) / 1_000_000L
            val routeSet = if (Build.VERSION.SDK_INT >= 36) {
                activeTrack.routedDevices.joinToString(prefix = "[", postfix = "]") {
                    describeDevice(it)
                }
            } else {
                describeDevice(routed)
            }
            Log.i(
                HALF_DUPLEX_TAG,
                "turn=$turnId route=${route.name.lowercase(Locale.ROOT)} " +
                    "a2dpRestoreMs=$a2dpRestoreMs routePollMs=$routePollMs " +
                    "preferred=${describeDevice(mediaDevice)} routed=$routeSet",
            )

            return ProvenTrack(activeTrack, routePrimed / 2L, a2dpRestoreMs, routePollMs)
        } catch (failure: Throwable) {
            track?.let { runCatching { it.stop() }; runCatching { it.release() } }
            if (activePlaybackTrack === track) activePlaybackTrack = null
            throw failure
        }
    }

    private fun playStereo(
        mono: FloatArray,
        sampleRate: Int,
        direction: TranslationDirection,
        route: AudioRouteKind,
        communicationAddress: String?,
        communicationName: String,
        scoClosedNs: Long,
        turnId: Long,
        coordinated: Boolean,
        allowSoleFallback: Boolean = !coordinated,
    ): PlaybackMetrics {
        var locked = false
        var track: AudioTrack? = null
        var playbackStartedNs = 0L
        try {
            playbackLock.lockInterruptibly(); locked = true
            val proven = openProvenTrack(
                sampleRate = sampleRate,
                bufferFrames = minOf(mono.size, sampleRate),
                route = route,
                communicationAddress = communicationAddress,
                communicationName = communicationName,
                scoClosedNs = scoClosedNs,
                turnId = turnId,
                coordinated = coordinated,
                allowSoleFallback = allowSoleFallback,
            )
            val activeTrack = proven.track
            track = activeTrack
            // Content is appended behind the proof silence on the same playing
            // track. pause()+flush() used to discard that silence, but on the
            // loudspeaker path the flush is applied late and left frames
            // queued. The stream position where content starts is exactly the
            // number of silence frames already written, so drain accounting
            // needs no flush at all.
            val stereo = when (route) {
                AudioRouteKind.BLUETOOTH -> interleaveTargetStereo(mono, direction)
                AudioRouteKind.PHONE -> duplicateMonoStereo(mono)
            }
            val contentStartFrame = proven.contentStartFrame
            val baselineUnderruns = activeTrack.underrunCount
            if (coordinated) {
                halfDuplex.transition(
                    turnId,
                    HalfDuplexState.PROCESSING,
                    HalfDuplexState.PLAYING,
                    if (route == AudioRouteKind.PHONE) "speaker_route_ready" else "a2dp_route_confirmed",
                )
            }
            playbackStartedNs = System.nanoTime()
            var writtenFloats = 0
            while (writtenFloats < stereo.size) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val count = activeTrack.write(
                    stereo,
                    writtenFloats,
                    stereo.size - writtenFloats,
                    AudioTrack.WRITE_BLOCKING,
                )
                check(count > 0) { "AudioTrack.write failed with code $count" }
                writtenFloats += count
            }

            fun deliveredFrames(): Long =
                ((activeTrack.playbackHeadPosition.toLong() and 0xffff_ffffL) - contentStartFrame)
                    .coerceAtLeast(0L)

            var delivered = deliveredFrames()
            val remainingMs = (mono.size.toLong() - delivered).coerceAtLeast(0L) *
                1_000L / sampleRate
            val deadlineNs = System.nanoTime() +
                (remainingMs + PLAYBACK_DRAIN_GRACE_MS) * 1_000_000L
            while (delivered < mono.size &&
                activeTrack.playState == AudioTrack.PLAYSTATE_PLAYING &&
                System.nanoTime() < deadlineNs) {
                Thread.sleep(PLAYBACK_DRAIN_POLL_MS)
                delivered = deliveredFrames()
            }
            val writtenFrames = writtenFloats / 2L
            val contentUnderruns = activeTrack.underrunCount - baselineUnderruns
            telemetry?.noteAudio(
                requested = mono.size.toLong(),
                written = writtenFrames,
                underrun = contentUnderruns > 0,
            )
            if (delivered < mono.size) {
                Log.w(TAG, "AudioTrack drain timed out: delivered=$delivered/${mono.size}")
            } else {
                val targetChannel = when {
                    route == AudioRouteKind.PHONE -> "both_speaker"
                    direction == TranslationDirection.EN_TO_VI -> "left_vi"
                    else -> "right_en"
                }
                Log.i(
                    TAG,
                    "AudioTrack stereo stop delivered=$delivered/${mono.size} " +
                        "writtenFrames=$writtenFrames contentUnderruns=$contentUnderruns " +
                        "targetChannel=$targetChannel",
                )
            }
            return PlaybackMetrics(
                a2dpRestoreMs = proven.a2dpRestoreMs,
                routePollMs = proven.routePollMs,
                playbackMs = (System.nanoTime() - playbackStartedNs) / 1_000_000L,
                firstAudioNs = playbackStartedNs,
            )
        } catch (_: InterruptedException) {
            throw InterruptedException()
        } finally {
            track?.let { runCatching { it.stop() }; runCatching { it.release() } }
            if (activePlaybackTrack === track) activePlaybackTrack = null
            if (locked) playbackLock.unlock()
        }
    }

    /**
     * One routing row per long turn (schema 2): the legacy fields keep their
     * meaning so existing scorers still read it, and `longTurn` adds windows,
     * units and the endpoint-relative timeline. Window and unit rows are
     * logged separately (OneVoiceLongTurn) so they never inflate per-turn counts.
     */
    private fun reportLongTurn(
        turn: LongTurn,
        ended: SegmenterEvent.TurnEnded,
        capture: TurnCapture,
        playback: LongTurnPlayback?,
        provider: CaptureProvider,
    ) {
        val routing = turn.routing
        val units = synchronized(turn.units) { turn.units.filter { it.revision == turn.revision } }
        val chunks = synchronized(turn.chunks) { turn.chunks.filter { it.revision == turn.revision } }
        val endpointNs = turn.turnEndDetectedNs
        fun sinceEndpointMs(ns: Long): Any =
            if (ns == 0L || endpointNs == 0L) JSONObject.NULL else (ns - endpointNs) / 1_000_000L
        val realtime = provider.inputMode != "replay_fast"
        // Live audio arrives in real time, so the last speech sample was
        // captured this long before the endpoint fired (the hangover).
        val speechEndNs = if (realtime && endpointNs != 0L) {
            endpointNs - (ended.archivedSamples - ended.lastSpeechSample).toLong() * 1_000_000_000L / SAMPLE_RATE
        } else {
            0L
        }
        val firstContent = turn.firstContentWriteNs
        val endpointToFirstContentMs =
            if (firstContent != 0L && endpointNs != 0L) (firstContent - endpointNs) / 1_000_000L else null
        val totalMs = (System.nanoTime() - (endpointNs.takeIf { it != 0L } ?: turn.openedNs)) / 1_000_000L
        val asrMs = chunks.sumOf { it.asrMs }
        val mtMs = units.sumOf { it.mtMs }
        val ttsMs = units.sumOf { it.ttsMs }
        val outputAudioMs = units.sumOf { it.audioMs }
        val error = turn.error

        val row = JSONObject().apply {
            put("schema", 2)
            put("timestamp", System.currentTimeMillis())
            put("turnId", turn.turnId)
            put("inputMode", provider.inputMode)
            put("audioMs", ended.archivedSamples * 1_000L / SAMPLE_RATE)
            put("audioRoute", capture.route.name.lowercase(Locale.ROOT))
            put("routingMode", routing?.mode?.name ?: routingConfig.mode.name)
            put("marginThreshold", routingConfig.marginThreshold)
            put("floorThreshold", routingConfig.floorThreshold)
            put("priorWeight", routingConfig.priorWeight)
            put("enScoreOffset", routingConfig.enScoreOffset)
            put("vi", branchJson(routing?.vi))
            put("en", branchJson(routing?.en))
            put("lid", if (routing?.mode == RoutingMode.LID) {
                JSONObject().apply {
                    put("language", routing.lidLanguage ?: JSONObject.NULL)
                    put("confidence", routing.lidConfidence ?: JSONObject.NULL)
                    put("margin", routing.lidMargin ?: JSONObject.NULL)
                    put("latencyMs", routing.lidMs ?: JSONObject.NULL)
                }
            } else {
                JSONObject.NULL
            })
            put("prior", routing?.priorExpected?.manifestId ?: JSONObject.NULL)
            put("decision", turn.direction?.manifestId ?: JSONObject.NULL)
            put("reason", routing?.reason?.logValue ?: "pipeline_error")
            put("normalizedMargin", routing?.normalizedMargin?.takeIf(Double::isFinite) ?: JSONObject.NULL)
            put("executionPath", turn.executionPath?.name ?: JSONObject.NULL)
            put("error", error ?: routing?.error ?: JSONObject.NULL)
            put("latencyMs", JSONObject().apply {
                put("captureSetup", capture.setupMs)
                put("scoClose", capture.scoCloseMs)
                put("queueWait", chunks.maxOfOrNull { it.queueAgeMs } ?: 0L)
                put("frontend", routing?.frontendMs ?: 0L)
                put("dualAsrWall", routing?.dualAsrWallMs ?: 0L)
                // Stage sums overlap capture and each other; never add them up as wall time.
                put("selectedAsr", asrMs)
                put("mt", mtMs)
                put("tts", ttsMs)
                put("a2dpRestore", playback?.a2dpRestoreMs ?: JSONObject.NULL)
                put("routePoll", playback?.routePollMs ?: JSONObject.NULL)
                put("playback", playback?.playbackMs ?: JSONObject.NULL)
                // Endpoint (speech end + hangover) -> first translated sample written.
                put("firstAudio", endpointToFirstContentMs ?: JSONObject.NULL)
                put("postBoundTotal", totalMs)
            })
            put("longTurn", JSONObject().apply {
                put("mode", turn.config.mode.name)
                put("splitPolicy", turn.config.splitPolicy.name)
                put("backgroundTranslation", turn.config.backgroundTranslation)
                put("endReason", ended.reason.logValue)
                put("windows", chunks.size)
                put("forcedCuts", chunks.count { it.chunk.cut == ChunkCut.FORCED })
                put("units", units.size)
                put("emptyUnits", turn.emptyUnits)
                put("revision", turn.revision)
                put("partialFailure", turn.partialFailure)
                put("acceptedSamples", ended.archivedSamples)
                put("lastSpeechSample", ended.lastSpeechSample)
                put("droppedAtCap", ended.droppedAtCap)
                put("speechEndToFirstContentMs",
                    if (speechEndNs != 0L && firstContent != 0L) (firstContent - speechEndNs) / 1_000_000L else JSONObject.NULL)
                put("endpointToFirstContentMs", endpointToFirstContentMs ?: JSONObject.NULL)
                put("captureClosedMs", sinceEndpointMs(turn.captureClosedNs))
                put("firstPreparedMs", sinceEndpointMs(turn.firstPreparedNs))
                put("routeConfirmedMs", sinceEndpointMs(turn.routeConfirmedNs))
                put("maxBacklogMs", chunks.maxOfOrNull { it.backlogMs } ?: 0L)
                put("maxQueueAgeMs", chunks.maxOfOrNull { it.queueAgeMs } ?: 0L)
                put("captureReadLagMaxMs", capture.maxReadLagMs)
                put("captureOverrunSuspected", capture.overrunSuspected)
                put("archiveBytes", turn.archive.bytes)
                put("ttsQueueHighWaterBytes", turn.audio.highWaterBytes)
                put("unitsPlayed", playback?.unitsPlayed ?: 0)
                put("playbackOutcome", playback?.outcome ?: JSONObject.NULL)
                put("underruns", playback?.underruns ?: JSONObject.NULL)
                put("starvedMs", playback?.starvedMs ?: JSONObject.NULL)
                put("outputAudioMs", outputAudioMs)
            })
        }
        Log.i(TUNING_TAG, row.toString())

        val sessionRow = JSONObject(row.toString()).apply {
            put("type", "turn")
            put("transcript", turn.transcript.ifEmpty { null } ?: JSONObject.NULL)
            put("translation", turn.translation.ifEmpty { null } ?: JSONObject.NULL)
            put("ttsAudioMs", outputAudioMs)
            put("units", org.json.JSONArray().apply {
                units.forEach { unit ->
                    put(JSONObject().apply {
                        put("index", unit.unit.index)
                        put("reason", unit.unit.reason.logValue)
                        put("source", unit.unit.text)
                        put("translation", unit.translation)
                    })
                }
            })
        }
        val tag = provider.replayTag
        if (tag == null) {
            sessionRecorder.append(sessionRow)
        } else {
            val turnTag = "${tag}_t${turn.turnId}"
            benchRecorder.append(sessionRow.put("tag", turnTag).put("replayTag", tag))
            turn.replayAudio?.let { audio ->
                runCatching {
                    val dir = File(appContext.filesDir, "bench/tts").apply { mkdirs() }
                    val bytes = java.nio.ByteBuffer.allocate(audio.size * Float.SIZE_BYTES)
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    audio.forEach(bytes::putFloat)
                    File(dir, "$turnTag.f32").writeBytes(bytes.array())
                }.onFailure { Log.w(TAG, "replay waveform $turnTag not saved", it) }
            }
        }
        val report = TurnReport(
            turnId = turn.turnId,
            timestampMs = System.currentTimeMillis(),
            audioMs = ended.archivedSamples * 1_000L / SAMPLE_RATE,
            route = capture.route.name.lowercase(Locale.ROOT),
            direction = turn.direction,
            reason = routing?.reason?.logValue ?: "pipeline_error",
            executionPath = turn.executionPath,
            transcript = turn.transcript.ifEmpty { null },
            translation = turn.translation.ifEmpty { null },
            error = error,
            viScore = routing?.vi?.hypothesis?.normalizedLogProb?.takeIf(Double::isFinite),
            enScore = routing?.en?.hypothesis?.normalizedLogProb?.takeIf(Double::isFinite),
            frontendMs = routing?.frontendMs ?: 0L,
            dualAsrWallMs = routing?.dualAsrWallMs ?: 0L,
            mtMs = mtMs,
            ttsMs = ttsMs,
            queueWaitMs = chunks.maxOfOrNull { it.queueAgeMs } ?: 0L,
            ttsAudioMs = outputAudioMs.takeIf { it > 0L },
            firstAudioMs = endpointToFirstContentMs,
            postBoundTotalMs = totalMs,
        )
        if (isCurrent(turn)) runCatching { pipelineListener?.onTurnReport(report) }
    }

    private fun branchJson(value: TimedAsrHypothesis?): Any = if (value == null) {
        JSONObject.NULL
    } else {
        JSONObject().apply {
            put("rawLogProb", value.hypothesis.rawLogProb)
            put("tokenCount", value.hypothesis.tokenCount)
            put(
                "normalizedLogProb",
                value.hypothesis.normalizedLogProb.takeIf(Double::isFinite) ?: JSONObject.NULL,
            )
            put("latencyMs", value.elapsedMs)
        }
    }

    private fun isCurrent(turn: LongTurn): Boolean = turn.generation == sessionGeneration.get()

    /** Coordinator hooks: UI updates for the current session, and per-window/unit log rows. */
    private val longTurnListener = object : LongTurnListener {
        override fun onTranscript(turn: LongTurn, transcript: String, pending: String, translation: String?) {
            if (!isCurrent(turn)) return
            val direction = turn.direction ?: return
            val shown = listOf(transcript, pending).filter(String::isNotEmpty).joinToString(" ")
            if (shown.isEmpty()) return
            notifySegment(shown, translation, direction, turn.executionPath ?: ExecutionPath.HYBRID_NPU)
        }

        override fun onChunk(turn: LongTurn, record: ChunkRecord) {
            Log.i(LONG_TURN_TAG, JSONObject().apply {
                put("type", "window")
                put("turnId", turn.turnId)
                put("index", record.chunk.index)
                put("start", record.chunk.start)
                put("end", record.chunk.end)
                put("coreStart", record.chunk.coreStart)
                put("coreEnd", record.chunk.coreEnd)
                put("cut", record.chunk.cut.logValue)
                put("path", record.path?.name ?: JSONObject.NULL)
                put("asrMs", record.asrMs)
                put("queueAgeMs", record.queueAgeMs)
                put("backlogMs", record.backlogMs)
                put("words", record.words)
                put("committedWords", record.committedWords)
                put("revision", record.revision)
            }.toString())
        }

        override fun onUnit(turn: LongTurn, record: UnitRecord) {
            Log.i(LONG_TURN_TAG, JSONObject().apply {
                put("type", "unit")
                put("turnId", turn.turnId)
                put("index", record.unit.index)
                put("reason", record.unit.reason.logValue)
                put("words", record.unit.wordCount)
                put("sourceTokens", record.unit.sourceTokens)
                put("firstWordSample", record.unit.firstWordSample)
                put("lastWordSample", record.unit.lastWordSample)
                put("path", record.path.name)
                put("mtMs", record.mtMs)
                put("outputTokens", record.outputTokens)
                put("truncationSplits", record.truncationSplits)
                put("revision", record.revision)
            }.toString())
        }

        override fun onUnitAudio(turn: LongTurn, record: UnitRecord) {
            Log.i(
                LONG_TURN_TAG,
                "{\"type\":\"unit_audio\",\"turnId\":${turn.turnId},\"index\":${record.unit.index}," +
                    "\"ttsMs\":${record.ttsMs},\"audioMs\":${record.audioMs},\"revision\":${record.revision}}",
            )
        }

        override fun onTurnFailed(turn: LongTurn, message: String) {
            Log.w(TAG, "Long turn ${turn.turnId}: $message")
            if (isCurrent(turn)) notifyPipelineError(message)
        }
    }

    /**
     * Replay a 16 kHz mono recording through the production capture path:
     * segmenter, coordinator, TTS and playback exactly as live, with a file
     * instead of the microphone. [baseline] (B0..B3) selects the long-turn
     * policy for this replay only. The receiver only dispatches; the file is
     * read on the replay worker.
     */
    @Synchronized
    internal fun benchReplay(file: File, paced: Boolean, baseline: String?, tag: String?): Boolean {
        if (telemetry?.enabled != true) return false
        if (pipeline == null) {
            lastError = "NPU pipeline is not initialized."
            return false
        }
        val busy = listOfNotNull(captureThread, pipelineThread, ttsThread, replayThread).any(Thread::isAlive)
        if (running.get() || busy) {
            lastError = "benchReplay refused: a live session or worker owns the engine."
            return false
        }
        val config = runCatching {
            baseline?.let(buildLongTurnConfig::forBaseline) ?: buildLongTurnConfig
        }.getOrElse {
            lastError = it.message
            return false
        }
        running.set(true)
        lastError = null
        longTurnConfig = config
        segments.clear()
        halfDuplex.reset()
        startInferenceWorkers(config)
        captureThread = Thread({ replayCaptureMain(file, paced, tag, config) }, "onevoice-replay-capture")
            .apply { start() }
        return true
    }

    private fun replayCaptureMain(file: File, paced: Boolean, tag: String?, config: LongTurnConfig) {
        val startedNs = System.nanoTime()
        var provider: ReplayCaptureProvider? = null
        try {
            val pcm = readReplayPcm(file)
            val replay = ReplayCaptureProvider(
                pcm = pcm,
                paced = paced,
                replayTag = tag ?: file.nameWithoutExtension.replace(Regex("[^A-Za-z0-9_-]"), "_"),
                route = standalonePlaybackRoute(),
                tailSilenceSamples = config.endSilenceSamples + LongTurnConfig.ms(200),
            )
            provider = replay
            Log.i(
                TAG,
                "replay start file=${file.name} samples=${pcm.size} durationMs=${pcm.size * 1_000L / SAMPLE_RATE} " +
                    "input=${replay.inputMode} longTurn=${config.mode} split=${config.splitPolicy} " +
                    "background=${config.backgroundTranslation} tag=${replay.replayTag}",
            )
            captureLoop(replay)
        } catch (failure: Throwable) {
            Log.e(TAG, "replay ${file.name} failed", failure)
            lastError = "Replay failed: ${failure.message ?: failure.javaClass.simpleName}"
        } finally {
            Log.i(
                TAG,
                "replay done file=${file.name} turns=${provider?.turnsOpened ?: 0} " +
                    "skippedWhileClosedMs=${(provider?.skippedWhileClosed ?: 0L) * 1_000L / SAMPLE_RATE} " +
                    "wallMs=${(System.nanoTime() - startedNs) / 1_000_000L}",
            )
            running.set(false)
            halfDuplex.cancel("replay_done")
            pipelineThread?.interrupt()
            ttsThread?.interrupt()
            longTurnConfig = buildLongTurnConfig
        }
    }

    private fun routeLabel(route: AudioRouteKind, inputName: String): String = when (route) {
        AudioRouteKind.PHONE -> "Phone mic + loudspeaker"
        AudioRouteKind.BLUETOOTH -> "$inputName (Bluetooth)"
    }

    private fun runningOrStandalone(coordinated: Boolean): Boolean =
        !Thread.currentThread().isInterrupted && (!coordinated || running.get())

    private fun awaitMediaOutput(
        audioManager: AudioManager,
        communicationAddress: String?,
        communicationName: String,
        deadlineNs: Long,
        allowSoleFallback: Boolean,
    ): AudioDeviceInfo {
        var candidates = emptyList<AudioDeviceInfo>()
        while (runningOrStandalone(!allowSoleFallback) && System.nanoTime() < deadlineNs) {
            candidates = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .filter { it.isSink && it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
            val matched = candidates.firstOrNull { device ->
                val mediaAddress = visibleAddress(device.address)
                when {
                    communicationAddress != null && mediaAddress != null ->
                        communicationAddress == mediaAddress
                    communicationName.isNotBlank() ->
                        communicationName.equals(
                            device.productName.toString().trim(),
                            ignoreCase = true,
                        )
                    else -> false
                }
            }
            if (matched != null) return matched
            if (allowSoleFallback && candidates.size == 1) return candidates.single()
            Thread.sleep(ROUTE_POLL_MS)
        }
        error(
            "No matching A2DP media route appeared after SCO closed; " +
                "communicationAddress=${communicationAddress ?: "hidden"} " +
                "communicationName=$communicationName candidates=" +
                candidates.joinToString { describeDevice(it) },
        )
    }

    private fun sameMediaDevice(expected: AudioDeviceInfo, actual: AudioDeviceInfo?): Boolean {
        if (actual == null || actual.type != AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) return false
        if (expected.id == actual.id) return true
        val expectedAddress = visibleAddress(expected.address)
        val actualAddress = visibleAddress(actual.address)
        if (expectedAddress != null && actualAddress != null) {
            return expectedAddress == actualAddress
        }
        return expected.productName.toString().trim().equals(
            actual.productName.toString().trim(),
            ignoreCase = true,
        )
    }

    private fun isBluetoothCaptureMatch(
        requestedOutput: AudioDeviceInfo,
        routedInput: AudioDeviceInfo,
    ): Boolean {
        if (!routedInput.isSource || !isBluetoothCommunicationType(routedInput.type)) return false
        val requestedAddress = visibleAddress(requestedOutput.address)
        val routedAddress = visibleAddress(routedInput.address)
        if (requestedAddress != null && routedAddress != null) {
            return requestedAddress == routedAddress
        }
        return requestedOutput.productName.toString().trim().equals(
            routedInput.productName.toString().trim(),
            ignoreCase = true,
        )
    }

    private fun isBluetoothCommunicationType(type: Int): Boolean = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        -> true
        else -> false
    }

    private fun visibleAddress(raw: String?): String? {
        val address = raw?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return address.takeUnless {
            it.isBlank() || it == "00:00:00:00:00:00" || it == "02:00:00:00:00:00"
        }
    }

    private fun describeDevice(device: AudioDeviceInfo?): String = device?.let {
        "id=${it.id},type=${audioTypeName(it.type)}(${it.type})," +
            "name=${it.productName.toString().ifBlank { "<blank>" }}," +
            "address=${visibleAddress(it.address) ?: "hidden"}," +
            "sink=${it.isSink},source=${it.isSource}"
    } ?: "<null>"

    private fun audioTypeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BLUETOOTH_A2DP"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE_HEADSET"
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BLE_SPEAKER"
        AudioDeviceInfo.TYPE_BLE_BROADCAST -> "BLE_BROADCAST"
        else -> "TYPE_$type"
    }

    private fun logTuningRow(
        captured: CapturedSegment,
        routing: RoutingDiagnostics?,
        direction: TranslationDirection?,
        text: HybridTextResult?,
        ttsMs: Long,
        queueWaitMs: Long,
        playback: PlaybackMetrics?,
        firstAudioMs: Long?,
        totalMs: Long,
        error: String?,
    ): JSONObject {
        fun branch(value: TimedAsrHypothesis?): Any = if (value == null) {
            JSONObject.NULL
        } else {
            JSONObject().apply {
                put("rawLogProb", value.hypothesis.rawLogProb)
                put("tokenCount", value.hypothesis.tokenCount)
                put(
                    "normalizedLogProb",
                    value.hypothesis.normalizedLogProb.takeIf(Double::isFinite)
                        ?: JSONObject.NULL,
                )
                put("latencyMs", value.elapsedMs)
            }
        }

        val row = JSONObject().apply {
            put("timestamp", System.currentTimeMillis())
            put("turnId", captured.id)
            put("inputMode", captured.inputMode)
            put("audioMs", captured.samples.size * 1_000L / SAMPLE_RATE)
            put("audioRoute", captured.route.name.lowercase(Locale.ROOT))
            put("routingMode", routing?.mode?.name ?: routingConfig.mode.name)
            put("marginThreshold", routingConfig.marginThreshold)
            put("floorThreshold", routingConfig.floorThreshold)
            put("priorWeight", routingConfig.priorWeight)
            put("enScoreOffset", routingConfig.enScoreOffset)
            put("vi", branch(routing?.vi))
            put("en", branch(routing?.en))
            put("lid", if (routing?.mode == RoutingMode.LID) {
                JSONObject().apply {
                    put("language", routing.lidLanguage ?: JSONObject.NULL)
                    put("confidence", routing.lidConfidence ?: JSONObject.NULL)
                    put("margin", routing.lidMargin ?: JSONObject.NULL)
                    put("latencyMs", routing.lidMs ?: JSONObject.NULL)
                }
            } else {
                JSONObject.NULL
            })
            put("prior", routing?.priorExpected?.manifestId ?: JSONObject.NULL)
            put("decision", direction?.manifestId ?: JSONObject.NULL)
            put("reason", routing?.reason?.logValue ?: "pipeline_error")
            put(
                "normalizedMargin",
                routing?.normalizedMargin?.takeIf(Double::isFinite) ?: JSONObject.NULL,
            )
            put("executionPath", text?.executionPath?.name ?: JSONObject.NULL)
            put("error", error ?: routing?.error ?: JSONObject.NULL)
            put("latencyMs", JSONObject().apply {
                put("captureSetup", captured.captureSetupMs)
                put("scoClose", captured.scoCloseMs)
                put("queueWait", queueWaitMs)
                put("frontend", routing?.frontendMs ?: 0L)
                put("dualAsrWall", routing?.dualAsrWallMs ?: 0L)
                put("selectedAsr", text?.asrMs ?: 0L)
                put("mt", text?.mtMs ?: 0L)
                put("tts", ttsMs)
                put("a2dpRestore", playback?.a2dpRestoreMs ?: JSONObject.NULL)
                put("routePoll", playback?.routePollMs ?: JSONObject.NULL)
                put("playback", playback?.playbackMs ?: JSONObject.NULL)
                // Bound -> first translated sample written; what the listener waits.
                put("firstAudio", firstAudioMs ?: JSONObject.NULL)
                put("postBoundTotal", totalMs)
            })
        }
        Log.i(TUNING_TAG, row.toString())
        return row
    }

    /**
     * Publish one live turn to the demo screen and the session log. Text stays
     * out of the logcat routing row; the session file is app-private until the
     * user explicitly exports it.
     */
    private fun reportTurn(
        row: JSONObject,
        captured: CapturedSegment,
        routing: RoutingDiagnostics?,
        direction: TranslationDirection?,
        text: HybridTextResult?,
        ttsMs: Long,
        ttsAudioMs: Long?,
        queueWaitMs: Long,
        firstAudioMs: Long?,
        totalMs: Long,
        error: String?,
    ) {
        val report = TurnReport(
            turnId = captured.id,
            timestampMs = System.currentTimeMillis(),
            audioMs = captured.samples.size * 1_000L / SAMPLE_RATE,
            route = captured.route.name.lowercase(Locale.ROOT),
            direction = direction,
            reason = routing?.reason?.logValue ?: "pipeline_error",
            executionPath = text?.executionPath,
            transcript = text?.transcript,
            translation = text?.translation,
            error = error,
            viScore = routing?.vi?.hypothesis?.normalizedLogProb?.takeIf(Double::isFinite),
            enScore = routing?.en?.hypothesis?.normalizedLogProb?.takeIf(Double::isFinite),
            frontendMs = routing?.frontendMs ?: 0L,
            dualAsrWallMs = routing?.dualAsrWallMs ?: 0L,
            mtMs = text?.mtMs ?: 0L,
            ttsMs = ttsMs,
            queueWaitMs = queueWaitMs,
            ttsAudioMs = ttsAudioMs,
            firstAudioMs = firstAudioMs,
            postBoundTotalMs = totalMs,
        )
        val sessionRow = turnRow(row, text, ttsAudioMs)
        val tag = captured.replayTag
        if (tag == null) {
            sessionRecorder.append(sessionRow)
        } else {
            benchRecorder.append(sessionRow.put("tag", tag))
        }
        runCatching { pipelineListener?.onTurnReport(report) }
    }

    /** The routing row plus what was said and translated: the session-log shape. */
    private fun turnRow(row: JSONObject, text: HybridTextResult?, ttsAudioMs: Long?): JSONObject =
        JSONObject(row.toString()).apply {
            put("type", "turn")
            put("transcript", text?.transcript ?: JSONObject.NULL)
            put("translation", text?.translation ?: JSONObject.NULL)
            put("ttsAudioMs", ttsAudioMs ?: JSONObject.NULL)
        }

    /**
     * Dataset evaluation output: one JSONL row per tagged fixture, plus the
     * 24 kHz target waveform under `bench/tts/<tag>.f32` so intelligibility
     * can be scored by an independent recognizer on the workstation.
     */
    private fun recordTaggedFixture(
        tag: String,
        row: JSONObject,
        text: HybridTextResult?,
        ttsAudioMs: Long?,
        audio: FloatArray?,
    ) {
        benchRecorder.append(turnRow(row, text, ttsAudioMs).put("tag", tag))
        if (audio == null) return
        runCatching {
            val dir = File(appContext.filesDir, "bench/tts").apply { mkdirs() }
            val bytes = java.nio.ByteBuffer.allocate(audio.size * Float.SIZE_BYTES)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            audio.forEach(bytes::putFloat)
            File(dir, "$tag.f32").writeBytes(bytes.array())
        }.onFailure { Log.w(TAG, "tagged fixture $tag: waveform not saved", it) }
    }

    private fun notifySegment(
        transcript: String,
        translation: String?,
        direction: TranslationDirection,
        executionPath: ExecutionPath,
    ) = runCatching {
        pipelineListener?.onSegment(
            PipelineSegment(
                transcript = transcript,
                translation = translation,
                direction = direction,
                executionPath = executionPath,
            ),
        )
    }.let { Unit }

    private fun notifyPipelineError(message: String) {
        lastError = message
        runCatching { pipelineListener?.onPipelineError(message) }
    }

    private fun notifyPipelineStopped(message: String) {
        lastError = message
        runCatching { pipelineListener?.onPipelineStopped(message) }
    }

    private fun joinWorker(thread: Thread?, timeout: Long) {
        if (thread == null || thread === Thread.currentThread()) return
        try { thread.join(timeout) } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun releasePipeline(loaded: AutoHybridPipeline?) {
        runCatching { loaded?.close() }
        if (OneVoiceNative.isLoaded) runCatching { OneVoiceNative.nativeDestroyEngine() }
        runtimeUp = false
        stages = emptyList()
    }

    /** How many contexts the native side reports as resident right now. */
    fun loadedModelCount(): Int =
        if (runtimeUp) runCatching { OneVoiceNative.nativeLoadedModelCount() }.getOrDefault(0)
        else 0

    /** Device-only graph smoke used by the connected instrumentation test. */
    internal fun runGraphSmokeTest(sourceText: String, playAudio: Boolean = false): NpuSmokeResult {
        val active = checkNotNull(pipeline) { "NPU pipeline is not initialized" }
        // Silence is intentional: the assertion is graph execution/finite
        // buffers, not transcription quality for a synthetic test vector.
        val direction = TranslationDirection.VI_TO_EN
        // Exercise the primary VI ASR with silence, then use a deterministic
        // source string for MT/TTS (silence is not expected to transcribe).
        val silenceResult = active.recognizeAndTranslate(FloatArray(SAMPLE_RATE), direction)
        val silenceTranscript = silenceResult.transcript
        val translated = if (sourceText.isBlank()) "" else {
        // The smoke path has no physical headset route, so it selects VI→EN
        // explicitly while exercising the same QNN MT graph.
            active.translateForSmoke(sourceText, direction)
        }
        check(translated.isNotBlank()) { "MT produced an empty result" }
        val audio = active.synthesize(translated, direction)
        check(audio.isNotEmpty() && audio.all(Float::isFinite)) {
            "TTS produced an empty or non-finite waveform"
        }
        Log.i(
            TAG,
            "NPU smoke source=\"$sourceText\" translation=\"$translated\" " +
                "silenceAsr=\"$silenceTranscript\" audioSamples=${audio.size}",
        )
        if (playAudio) {
            playStereo(
                mono = audio,
                sampleRate = TTS_SAMPLE_RATE,
                direction = direction,
                route = standalonePlaybackRoute(),
                communicationAddress = null,
                communicationName = "smoke fixture",
                scoClosedNs = System.nanoTime(),
                turnId = turnIds.incrementAndGet(),
                coordinated = false,
            )
        }
        return NpuSmokeResult(silenceTranscript, translated, audio.size)
    }

    private fun publish() {
        listener?.invoke(stages)
    }

    internal companion object {
        /** Set by every constructed engine; read by [BenchControlReceiver]. */
        private var activeEngine: WeakReference<NpuEngine>? = null

        /** The engine an Activity currently owns, or null. */
        internal fun current(): NpuEngine? = activeEngine?.get()

        /** Hexagon v73 = Snapdragon 8 Gen 2; matches the manifest's target SoC. */
        const val DSP_SKEL = "libQnnHtpV73Skel.so"
        const val SIGNED_ASSET_DIR = "dsp/signed"

        const val SAMPLE_RATE = 16_000
        const val TTS_SAMPLE_RATE = 24_000
        const val FRAME_SAMPLES = 320 // 20 ms: responsive VAD without 100 ms edge loss
        const val VAD_RMS_THRESHOLD = 0.008
        const val VAD_LOG_FRAMES = 50 // one tuning line per second of capture

        // Whole-utterance floor, sitting between the noise and speech clusters
        // measured on this device (noise rms ≤0.0071 / peak ≤0.039; speech rms
        // ≥0.013 / peak ≥0.09). Both must be cleared: a brief bump passes the
        // peak test, and a loud steady hum passes the RMS one.
        const val SEGMENT_MIN_RMS = 0.010f
        const val SEGMENT_MIN_PEAK = 0.060f
        const val MIN_SPEECH_FRAMES = 6 // 120 ms
        const val END_SILENCE_FRAMES = 50 // 1 second pause before finalizing
        const val MAX_SEGMENT_SAMPLES = SAMPLE_RATE * 10
        const val PRE_ROLL_SAMPLES = SAMPLE_RATE * 3 / 10 // retain 300 ms before trigger
        // Strict half-duplex permits exactly one bounded turn in flight.
        const val MAX_QUEUED_SEGMENTS = 1
        const val EXPECTED_HYBRID_CONTEXTS = 6
        // Phone route: for this long after IDLE the loudspeaker's decaying
        // translation (output latency + room reverb) cannot open a turn. The
        // audio still warms the VAD and fills pre-roll ([UtteranceSegmenter]).
        const val PHONE_ECHO_GUARD_SAMPLES = SAMPLE_RATE * 3 / 10 // 300 ms
        const val PHONE_ROUTE_NAME = "Phone microphone"
        const val MIC_ROUTE_TIMEOUT_MS = 2_000L
        const val A2DP_ROUTE_TIMEOUT_MS = 3_000L
        const val ROUTE_POLL_MS = 20L
        // Silence that starts the track so its route can be proven; it is also
        // the only gap before content now that it is no longer flushed.
        const val ROUTE_PRIME_FRAMES = TTS_SAMPLE_RATE / 10 // 100 ms
        const val PLAYBACK_DRAIN_GRACE_MS = 1_500L
        const val PLAYBACK_DRAIN_POLL_MS = 20L
        const val CAPTURE_JOIN_MS = 2_000L
        const val PIPELINE_JOIN_MS = 4_000L
        const val REPLAY_JOIN_MS = 2_000L
        /** AudioRecord buffer for long turns: 2 s of 16 kHz PCM16 absorbs CPU contention. */
        const val LONG_TURN_RECORD_BUFFER_BYTES = SAMPLE_RATE * 2 * 2
        /** Read-lag sample interval: once per second of 20 ms reads. */
        const val READ_LAG_CHECK_READS = 50
        const val LONG_TURN_PLAYBACK_POLL_MS = 50L
        const val LONG_TURN_WORKER_POLL_MS = 100L
        /** Stereo staging block for long-turn playback: 100 ms at 24 kHz. */
        const val PLAYBACK_BLOCK_FRAMES = TTS_SAMPLE_RATE / 10

        val HYBRID_QNN_STAGES = setOf("asr", "mt_encoder", "mt_decoder")

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

internal data class NpuSmokeResult(
    val silenceTranscript: String,
    val translation: String,
    val audioSamples: Int,
)
