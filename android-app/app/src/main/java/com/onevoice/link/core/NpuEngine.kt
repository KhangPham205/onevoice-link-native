package com.onevoice.link.core

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.system.Os
import android.util.Log
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
    }

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
        val staleWorker = listOfNotNull(captureThread, pipelineThread)
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
        segments.clear()
        halfDuplex.reset()
        pipelineThread = Thread(::pipelineLoop, "onevoice-npu-pipeline").apply { start() }
        captureThread = Thread(::captureLoop, "onevoice-npu-capture").apply { start() }
        return true
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
        if (!running.get() && pipelineThread?.isAlive == true) {
            lastError = "benchFeed refused: previous pipeline worker is still stopping."
            return false
        }
        if (running.compareAndSet(false, true)) {
            // No capture thread: the fixture is the only producer.
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
        val active = pipeline ?: return null
        return runCatching { active.synthesize(text, direction) }.getOrNull()
    }

    @Synchronized
    override fun stop() {
        running.set(false)
        halfDuplex.cancel("engine_stop")
        activeRecord?.let { runCatching { it.stop() } }
        activePlaybackTrack?.let { active ->
            runCatching { active.pause() }
            runCatching { active.stop() }
        }
        val capture = captureThread
        val inference = pipelineThread
        val replay = replayThread
        capture?.interrupt()
        inference?.interrupt()
        replay?.interrupt()
        joinWorker(capture, CAPTURE_JOIN_MS)
        joinWorker(inference, PIPELINE_JOIN_MS)
        joinWorker(replay, REPLAY_JOIN_MS)
        if (capture?.isAlive != true && captureThread === capture) captureThread = null
        if (inference?.isAlive != true && pipelineThread === inference) pipelineThread = null
        if (replay?.isAlive != true && replayThread === replay) replayThread = null
        segments.clear()
    }

    @Synchronized
    override fun destroy() {
        if (!destroyed.compareAndSet(false, true)) return
        stop()
        val loaded = pipeline
        pipeline = null
        lastAudio = null
        val worker = pipelineThread?.takeIf(Thread::isAlive)
        if (worker == null) {
            releasePipeline(loaded)
        } else if (releasePending.compareAndSet(false, true)) {
            Thread({
                try {
                    worker.join()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                } finally {
                    releasePipeline(loaded)
                    releasePending.set(false)
                    if (pipelineThread === worker) pipelineThread = null
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
        if (captureThread?.isAlive == true || pipelineThread?.isAlive == true) {
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

    @SuppressLint("MissingPermission")
    private fun captureLoop() {
        val audioManager = appContext.getSystemService(AudioManager::class.java)
        if (audioManager == null) {
            running.set(false)
            notifyPipelineStopped("Android AudioManager is unavailable.")
            pipelineThread?.interrupt()
            return
        }
        var activeTurnId: Long? = null
        // One detector per session: Silero's recurrent state is reset per turn,
        // but loading the graph per turn would add latency to every capture.
        val detector = createSpeechDetector(vadMode, appContext.assets, VAD_RMS_THRESHOLD)
        Log.i(TAG, "capture VAD=${detector.name} (configured ${vadMode.name})")
        try {
            while (running.get()) {
                val turnId = turnIds.incrementAndGet()
                activeTurnId = turnId
                if (!halfDuplex.awaitIdleThenCapture(turnId)) break
                val captured = captureOneTurn(audioManager, turnId, detector)
                if (captured == null) {
                    halfDuplex.finishTurn(turnId, "capture_cancelled_or_noise")
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
            }
        } finally {
            activeTurnId?.let { halfDuplex.finishTurn(it, "capture_worker_exit") }
            runCatching { detector.close() }
        }
    }

    @SuppressLint("MissingPermission")
    private fun captureOneTurn(
        audioManager: AudioManager,
        turnId: Long,
        detector: SpeechActivityDetector,
    ): CapturedSegment? {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            "Communication-device routing requires Android 12 or newer."
        }
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
        var bounded: BoundedUtterance? = null
        var setupMs = 0L
        var scoCloseMs = 0L
        var scoClosedNs = 0L
        try {
            val minimumBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            check(minimumBuffer > 0) { "AudioRecord does not support 16 kHz mono PCM." }
            record = AudioRecord(
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
                maxOf(minimumBuffer, SAMPLE_RATE / 2),
            )
            check(record.state == AudioRecord.STATE_INITIALIZED) {
                "AudioRecord initialization failed"
            }
            activeRecord = record
            val builtInMic = if (communication == null) builtInMicrophone(audioManager) else null
            builtInMic?.let(record::setPreferredDevice)
            record.startRecording()
            val routedInput = if (communication != null) {
                awaitBluetoothInputRoute(record, communication)
            } else {
                awaitPhoneInputRoute(record)
            }
            setupMs = (System.nanoTime() - setupStartedNs) / 1_000_000L
            lastRouteLabel = routeLabel(
                route,
                communication?.productName?.toString()?.trim().orEmpty()
                    .ifEmpty { "Bluetooth headset" },
            )
            Log.i(
                HALF_DUPLEX_TAG,
                "turn=$turnId route=${route.name.lowercase(Locale.ROOT)} captureSetupMs=$setupMs " +
                    "requested=${describeDevice(communication ?: builtInMic)} " +
                    "routed=${describeDevice(routedInput)} sampleRate=${record.sampleRate}",
            )
            bounded = readBoundedUtterance(
                record,
                detector,
                // The loudspeaker tail of the previous translation reaches the
                // built-in mic after AudioTrack reports drained; a headset does
                // not. Guarded audio cannot open a turn but is kept as pre-roll.
                guardSamples = if (communication == null) PHONE_ECHO_GUARD_SAMPLES else 0,
            )
        } finally {
            val closeStartedNs = System.nanoTime()
            record?.let { active ->
                runCatching {
                    if (active.recordingState == AudioRecord.RECORDSTATE_RECORDING) active.stop()
                }
                runCatching { active.release() }
            }
            if (activeRecord === record) activeRecord = null
            // Always release the app-owned communication route. Keeping it set
            // leaves the TWS in SCO/call mode even if AudioRecord is gone. The
            // phone route never claimed one, so it must not clear another app's.
            if (communication != null) runCatching { audioManager.clearCommunicationDevice() }
            scoClosedNs = System.nanoTime()
            scoCloseMs = (scoClosedNs - closeStartedNs) / 1_000_000L
            Log.i(HALF_DUPLEX_TAG, "turn=$turnId scoCloseMs=$scoCloseMs")
        }
        val utterance = bounded ?: return null
        return CapturedSegment(
            id = turnId,
            samples = utterance.samples,
            enqueuedNs = System.nanoTime(),
            forcedBoundary = utterance.forcedBoundary,
            route = route,
            communicationAddress = communication?.let { visibleAddress(it.address) },
            communicationName = communication?.productName?.toString()?.trim()
                ?.ifEmpty { "Bluetooth headset" }
                ?: PHONE_ROUTE_NAME,
            captureSetupMs = setupMs,
            scoCloseMs = scoCloseMs,
            scoClosedNs = scoClosedNs,
        )
    }

    @SuppressLint("MissingPermission")
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
        record: AudioRecord,
        detector: SpeechActivityDetector,
        guardSamples: Int = 0,
    ): BoundedUtterance? {
        val frame = ShortArray(FRAME_SAMPLES)
        val segmenter = UtteranceSegmenter(detector, guardSamples, log = { Log.i(TAG, it) })
        var vadFrames = 0
        var vadSpeechFrames = 0
        var vadPeakScore = 0f
        while (running.get()) {
            val count = record.read(frame, 0, frame.size)
            if (count < 0) error("AudioRecord.read failed with code $count")
            if (count == 0) continue
            val live = !segmenter.guarding
            val utterance = segmenter.accept(frame, count)
            if (BuildConfig.DEBUG && live) {
                // One line per second of capture: peak detector score and how
                // many 20 ms frames it called speech. Debug builds only.
                vadPeakScore = maxOf(vadPeakScore, detector.lastScore)
                if (segmenter.lastFrameSpeech) vadSpeechFrames++
                if (++vadFrames == VAD_LOG_FRAMES) {
                    Log.d(
                        VAD_TAG,
                        "${detector.name} peak=${"%.3f".format(vadPeakScore)} " +
                            "speechFrames=$vadSpeechFrames/$vadFrames",
                    )
                    vadFrames = 0
                    vadSpeechFrames = 0
                    vadPeakScore = 0f
                }
            }
            if (utterance != null) return utterance
        }
        return null
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
                    logTuningRow(
                        captured = captured,
                        routing = routing,
                        direction = direction,
                        text = textResult,
                        ttsMs = ttsMs,
                        queueWaitMs = queueWaitMs,
                        playback = playback,
                        totalMs = (System.nanoTime() - processingStartedNs) / 1_000_000L,
                        error = turnError,
                    )
                    if (!captured.benchmark) {
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
    )

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
    ): PlaybackMetrics {
        var locked = false
        var track: AudioTrack? = null
        var playbackStartedNs = 0L
        try {
            playbackLock.lockInterruptibly(); locked = true
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
                        allowSoleFallback = !coordinated,
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
            val requestedBufferFrames = maxOf(
                ROUTE_PRIME_FRAMES,
                minOf(mono.size, sampleRate),
            )
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
            val contentStartFrame = routePrimed / 2L
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
                a2dpRestoreMs = a2dpRestoreMs,
                routePollMs = routePollMs,
                playbackMs = (System.nanoTime() - playbackStartedNs) / 1_000_000L,
            )
        } catch (_: InterruptedException) {
            throw InterruptedException()
        } finally {
            track?.let { runCatching { it.stop() }; runCatching { it.release() } }
            if (activePlaybackTrack === track) activePlaybackTrack = null
            if (locked) playbackLock.unlock()
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
        totalMs: Long,
        error: String?,
    ) {
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
                put("postBoundTotal", totalMs)
            })
        }
        Log.i(TUNING_TAG, row.toString())
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
