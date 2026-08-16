package com.onevoice.link.core

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.system.Os
import android.util.Log
import com.onevoice.link.BuildConfig
import java.io.File
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.sqrt

private const val TAG = "OneVoiceNpu"
private const val LID_DUMP_LIMIT = 8

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
 * Auto-direction Hybrid pipeline. Audio/VAD and deterministic tensor transforms
 * use bounded CPU buffers. SpeechFlow LID classifies each completed utterance,
 * both ASR/MT branches execute on HTP, and Kokoro renders one continuous target
 * utterance on CPU to preserve temporal context.
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
        "Hybrid Auto · SpeechFlow LID · QNN ASR + MT · CPU Kokoro"
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
    private val lidDumpCount = AtomicInteger(0)

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
        val samples: FloatArray,
        val enqueuedNs: Long,
        /** True when the 10 s ASR ceiling cut the utterance, not silence. */
        val forcedBoundary: Boolean,
    )

    private val segments = LinkedBlockingQueue<CapturedSegment>(MAX_QUEUED_SEGMENTS)
    @Volatile private var playing = false
    @Volatile private var lastAudio: Pair<FloatArray, Int>? = null
    private var captureThread: Thread? = null
    private var pipelineThread: Thread? = null
    @Volatile private var replayThread: Thread? = null

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
        return runCatching {
            pipeline = AutoHybridPipeline.load(modelRoot, manifest)
            lastError = null
            Log.i(TAG, "Auto Hybrid pipeline ready directions=vi-en,en-vi root=$modelRootPath")
            true
        }.getOrElse { failure ->
            Log.e(TAG, "NPU frontend initialization failed", failure)
            lastError = "NPU pipeline init failed: ${failure.message ?: failure.javaClass.simpleName}"
            runCatching { OneVoiceNative.nativeReleaseModels() }
            false
        }
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
        if (!running.compareAndSet(false, true)) return true
        lastError = null
        segments.clear()
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
     * the capture loop would have written to, so LID, routing, both HTP
     * branches, TTS and playback all execute exactly as in production. Only the
     * source of the samples differs.
     *
     * Playback stays enabled on purpose: AudioTrack contention and underruns
     * are part of what the gates measure.
     *
     * Returns false when the pipeline is not loaded or a live session already
     * owns the worker.
     */
    fun benchFeed(samples: FloatArray, forcedBoundary: Boolean = false): Boolean {
        if (telemetry?.enabled != true) return false
        if (pipeline == null) {
            lastError = "NPU pipeline is not initialized."
            return false
        }
        if (captureThread?.isAlive == true) {
            lastError = "benchFeed refused: microphone capture is running."
            return false
        }
        if (running.compareAndSet(false, true)) {
            // No capture thread: the fixture is the only producer.
            segments.clear()
            pipelineThread = Thread(::pipelineLoop, "onevoice-npu-pipeline").apply { start() }
        }
        val queued = CapturedSegment(
            samples = samples,
            enqueuedNs = System.nanoTime(),
            forcedBoundary = forcedBoundary,
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
        val (samples, rate) = lastAudio ?: return false
        if (replayThread?.isAlive == true) return false
        val thread = Thread({
            try {
                play(samples, rate)
            } catch (failure: Throwable) {
                notifyPipelineError("Audio replay failed: ${failure.message ?: failure.javaClass.simpleName}")
            } finally {
                synchronized(this) {
                    if (replayThread === Thread.currentThread()) replayThread = null
                }
            }
        }, "onevoice-npu-replay")
        replayThread = thread
        thread.start()
        return true
    }

    @SuppressLint("MissingPermission")
    private fun captureLoop() {
        val minimumBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minimumBuffer <= 0) {
            running.set(false)
            notifyPipelineStopped("AudioRecord does not support 16 kHz mono PCM.")
            pipelineThread?.interrupt()
            return
        }
        val record = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minimumBuffer, SAMPLE_RATE / 2),
            )
        }.getOrElse {
            running.set(false)
            notifyPipelineStopped("Microphone initialization failed: ${it.message}")
            pipelineThread?.interrupt()
            return
        }
        try {
            check(record.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord initialization failed" }
            record.startRecording()
            val frame = ShortArray(FRAME_SAMPLES)
            val current = FloatArray(MAX_SEGMENT_SAMPLES)
            val preRoll = FloatArray(PRE_ROLL_SAMPLES)
            var currentSize = 0
            var speechFrames = 0
            var silenceFrames = 0
            var trailingSilenceSamples = 0
            var preRollSize = 0
            var preRollWrite = 0

            fun appendFrame(destination: FloatArray, offset: Int, count: Int): Int {
                val append = minOf(count, destination.size - offset)
                for (index in 0 until append) {
                    destination[offset + index] = frame[index] / 32_768f
                }
                return append
            }

            fun rememberPreRoll(count: Int) {
                for (index in 0 until count) {
                    preRoll[preRollWrite] = frame[index] / 32_768f
                    preRollWrite = (preRollWrite + 1) % preRoll.size
                    preRollSize = minOf(preRoll.size, preRollSize + 1)
                }
            }

            fun beginFromPreRoll() {
                val start = (preRollWrite - preRollSize + preRoll.size) % preRoll.size
                repeat(preRollSize) { index ->
                    current[index] = preRoll[(start + index) % preRoll.size]
                }
                currentSize = preRollSize
            }

            fun offerCurrent(size: Int, forcedBoundary: Boolean = false) {
                if (size <= 0) return
                val segment = current.copyOf(size)
                var sumSquares = 0.0
                var peak = 0f
                segment.forEach { sample ->
                    sumSquares += sample.toDouble() * sample
                    peak = maxOf(peak, kotlin.math.abs(sample))
                }
                val rms = sqrt(sumSquares / size).toFloat()
                val crest = peak / (rms + 1e-9f)
                Log.i(
                    TAG,
                    "Captured segment samples=$size durationMs=${size * 1_000L / SAMPLE_RATE} " +
                        "rms=${"%.5f".format(rms)} peak=${"%.5f".format(peak)} " +
                        "crest=${"%.1f".format(crest)}",
                )
                // Per-frame VAD only decides where an utterance starts and ends;
                // steady room noise sits just under VAD_RMS_THRESHOLD, so a few
                // transient peaks open a segment that the one-second hangover
                // then keeps alive. Measured on this device, such segments came
                // back at rms 0.0066-0.0071 / peak 0.029-0.039 with a crest
                // factor near 5, while real speech reached rms 0.013-0.030 and
                // peaks of 0.09-0.31. Judging the completed utterance as a whole
                // is what separates the two: speech is peaky because syllables
                // are separated by near-silence, noise is not.
                if (rms < SEGMENT_MIN_RMS || peak < SEGMENT_MIN_PEAK) {
                    Log.i(
                        TAG,
                        "Discarding segment below the speech floor " +
                            "(rms=${"%.5f".format(rms)} peak=${"%.5f".format(peak)})",
                    )
                    return
                }
                val queued = CapturedSegment(
                    samples = segment,
                    enqueuedNs = System.nanoTime(),
                    forcedBoundary = forcedBoundary,
                )
                if (segments.remainingCapacity() == 0 || !segments.offer(queued)) {
                    Log.w(TAG, "Dropping speech segment: NPU inference queue is full")
                }
            }

            while (running.get()) {
                val count = record.read(frame, 0, frame.size)
                if (count < 0) error("AudioRecord.read failed with code $count")
                if (count == 0) continue
                if (playing) {
                    currentSize = 0; speechFrames = 0; silenceFrames = 0
                    trailingSilenceSamples = 0
                    preRollSize = 0; preRollWrite = 0
                    continue
                }
                var sumSquares = 0.0
                for (i in 0 until count) sumSquares += frame[i].toDouble() * frame[i]
                val speech = sqrt(sumSquares / count) / 32_768.0 > VAD_RMS_THRESHOLD
                if (speechFrames == 0) {
                    rememberPreRoll(count)
                    if (speech) {
                        beginFromPreRoll()
                        speechFrames = 1
                        silenceFrames = 0
                    }
                    continue
                }

                currentSize += appendFrame(current, currentSize, count)
                if (speech) {
                    speechFrames++
                    silenceFrames = 0
                    trailingSilenceSamples = 0
                } else {
                    silenceFrames++
                    trailingSilenceSamples += count
                }
                val endedBySilence = speechFrames >= MIN_SPEECH_FRAMES &&
                    silenceFrames >= END_SILENCE_FRAMES
                val rejectedBySilence = speechFrames < MIN_SPEECH_FRAMES &&
                    silenceFrames >= END_SILENCE_FRAMES
                val endedByLength = currentSize >= MAX_SEGMENT_SAMPLES
                if (endedBySilence) {
                    // END_SILENCE_FRAMES is a VAD hangover, not speech. Keeping
                    // the whole second makes short utterances silence-dominated
                    // for direction routing and also wastes the fixed ASR window.
                    offerCurrent((currentSize - trailingSilenceSamples).coerceAtLeast(1))
                    currentSize = 0
                    speechFrames = 0
                    silenceFrames = 0
                    trailingSilenceSamples = 0
                    preRollSize = 0
                    preRollWrite = 0
                } else if (rejectedBySilence) {
                    currentSize = 0
                    speechFrames = 0
                    silenceFrames = 0
                    trailingSilenceSamples = 0
                    preRollSize = 0
                    preRollWrite = 0
                } else if (endedByLength) {
                    offerCurrent(MAX_SEGMENT_SAMPLES, forcedBoundary = true)
                    // The ASR graph is statically capped at 10 seconds. Carry a
                    // short overlap into the next window so a word crossing the
                    // boundary is not lost entirely.
                    val carry = minOf(ASR_WINDOW_OVERLAP_SAMPLES, currentSize)
                    current.copyInto(current, 0, currentSize - carry, currentSize)
                    currentSize = carry
                    speechFrames = 1
                    trailingSilenceSamples = minOf(trailingSilenceSamples, carry)
                }
            }
        } catch (failure: Throwable) {
            if (running.get()) {
                running.set(false)
                notifyPipelineStopped("Microphone capture failed: ${failure.message}")
                pipelineThread?.interrupt()
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }

    /**
     * Debug aid for validating the LID frontend against its Python reference.
     *
     * Writes the exact float PCM the classifier saw, so the host can replay the
     * identical samples instead of a re-encoded recording — the whole point is
     * to compare decisions, not audio files. Off unless the marker file exists,
     * and capped so a long session cannot fill internal storage.
     */
    private fun dumpLidSegment(samples: FloatArray, decision: LanguageDecision) {
        val root = File(modelRootPath).parentFile?.let { File(it, "lid-dump") } ?: return
        if (!root.isDirectory) return
        if ((root.list()?.size ?: 0) >= 2 * LID_DUMP_LIMIT) return
        val index = lidDumpCount.getAndIncrement()
        if (index >= LID_DUMP_LIMIT) return
        runCatching {
            val bytes = ByteBuffer.allocate(samples.size * Float.SIZE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
            samples.forEach(bytes::putFloat)
            File(root, "seg%02d.f32".format(index)).writeBytes(bytes.array())
            File(root, "seg%02d.json".format(index)).writeText(
                """{"samples":${samples.size},"language":"${decision.language}",""" +
                    """"accepted":${decision.accepted},"confidence":${decision.confidence},""" +
                    """"margin":${decision.margin},"inferenceMs":${decision.inferenceMs}}""",
            )
            Log.i(TAG, "LID dump seg$index written (${samples.size} samples)")
        }.onFailure { Log.w(TAG, "LID dump failed", it) }
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
        decision: LanguageDecision,
        direction: TranslationDirection,
        textResult: HybridTextResult,
        translation: String,
        lidMs: Long,
        ttsMs: Long,
        queueWaitMs: Long,
        audioSamples: Int,
    ) {
        val bench = telemetry ?: return
        if (!bench.enabled) return
        val (underruns, requested, written) = bench.audioCounters()
        val ttfaMs = lidMs + textResult.asrMs + textResult.mtMs + ttsMs
        val audioSeconds = audioSamples.toDouble() / TTS_SAMPLE_RATE
        bench.record(
            UtteranceMetrics(
                direction = direction.manifestId,
                lidDecision = decision.language,
                lidConfidence = decision.confidence,
                speechDurationS = captured.samples.size.toDouble() / SAMPLE_RATE,
                forcedBoundary = captured.forcedBoundary,
                executionPath = textResult.executionPath.name,
                lidMs = lidMs,
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
                val samples = captured.samples
                val active = pipeline ?: break
                val startedNs = System.nanoTime()
                val queueWaitMs = (startedNs - captured.enqueuedNs) / 1_000_000L
                val decision = active.detect(samples)
                val lidNs = System.nanoTime()
                dumpLidSegment(samples, decision)
                val direction = decision.direction
                Log.i(
                    TAG,
                    "LID language=${decision.language} accepted=${decision.accepted} " +
                        "confidence=${"%.4f".format(decision.confidence)} " +
                        "margin=${"%.4f".format(decision.margin)} " +
                        "inferenceMs=${decision.inferenceMs}",
                )
                if (direction == null) {
                    notifyPipelineError(
                        "Language not confidently identified as Vietnamese or English " +
                            "(top=${decision.language}, " +
                            "confidence=${"%.2f".format(decision.confidence)}, " +
                            "margin=${"%.2f".format(decision.margin)}). Please speak again.",
                    )
                    continue
                }
                lastError = null
                val textResult = active.recognizeAndTranslate(samples, direction)
                val transcript = textResult.transcript
                if (transcript.isEmpty()) continue
                notifySegment(
                    transcript,
                    null,
                    direction,
                    decision.confidence,
                    textResult.executionPath,
                )
                val translation = textResult.translation
                notifySegment(
                    transcript,
                    translation,
                    direction,
                    decision.confidence,
                    textResult.executionPath,
                )
                if (translation.isEmpty()) continue
                val ttsStartedNs = System.nanoTime()
                val audio = active.synthesize(translation, direction)
                val ttsNs = System.nanoTime()
                if (audio.isEmpty()) {
                    notifyPipelineError("Kokoro returned an empty waveform.")
                    continue
                }
                val lidMs = (lidNs - startedNs) / 1_000_000L
                val ttsMs = (ttsNs - ttsStartedNs) / 1_000_000L
                val audioMs = audio.size * 1_000L / TTS_SAMPLE_RATE
                Log.i(
                    TAG,
                    "Pipeline direction=$direction transcript=\"$transcript\" " +
                        "translation=\"$translation\" " +
                        "audioSamples=${audio.size} audioMs=$audioMs " +
                        "lidMs=$lidMs queueWaitMs=$queueWaitMs " +
                        "path=${textResult.executionPath} " +
                        "asrMs=${textResult.asrMs} mtMs=${textResult.mtMs} " +
                        "ttsMs=$ttsMs",
                )
                recordBenchRow(
                    captured = captured,
                    decision = decision,
                    direction = direction,
                    textResult = textResult,
                    translation = translation,
                    lidMs = lidMs,
                    ttsMs = ttsMs,
                    queueWaitMs = queueWaitMs,
                    audioSamples = audio.size,
                )
                lastAudio = audio to TTS_SAMPLE_RATE
                runCatching { pipelineListener?.onAudioReady() }
                if (running.get()) play(audio, TTS_SAMPLE_RATE)
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

    private fun play(samples: FloatArray, sampleRate: Int) {
        var locked = false
        var track: AudioTrack? = null
        try {
            playbackLock.lockInterruptibly(); locked = true
            val minimum = AudioTrack.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT,
            )
            check(minimum > 0) { "AudioTrack does not support $sampleRate Hz float PCM" }
            val activeTrack = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minimum, samples.size * Float.SIZE_BYTES))
                .build()
            track = activeTrack
            check(activeTrack.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack init failed" }
            playing = true
            // Request a stream buffer large enough for the bounded waveform,
            // then fill the actual capacity before play(). Samsung's audio HAL
            // rejects MODE_STATIC + PCM_FLOAT, while this gives the same
            // underrun protection whenever it honors the requested capacity.
            val primeFrames = minOf(samples.size, activeTrack.bufferSizeInFrames)
            val primed = activeTrack.write(
                samples, 0, primeFrames, AudioTrack.WRITE_BLOCKING,
            )
            check(primed == primeFrames) {
                "AudioTrack initial write accepted $primed/$primeFrames frames"
            }
            Log.i(
                TAG,
                "AudioTrack start mode=stream frames=${samples.size} " +
                    "durationMs=${samples.size * 1_000L / sampleRate} " +
                    "bufferFrames=${activeTrack.bufferSizeInFrames} primed=$primed",
            )
            activeTrack.play()
            var written = primed
            while (written < samples.size) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val count = activeTrack.write(
                    samples,
                    written,
                    samples.size - written,
                    AudioTrack.WRITE_BLOCKING,
                )
                check(count > 0) { "AudioTrack.write failed with code $count" }
                written += count
            }

            // Do not stop/release until the hardware head has consumed the
            // final buffered syllable.
            var delivered = activeTrack.playbackHeadPosition.toLong() and 0xffff_ffffL
            val remainingMs = (samples.size.toLong() - delivered).coerceAtLeast(0L) *
                1_000L / sampleRate
            val deadlineNs = System.nanoTime() +
                (remainingMs + PLAYBACK_DRAIN_GRACE_MS) * 1_000_000L
            while (delivered < samples.size &&
                activeTrack.playState == AudioTrack.PLAYSTATE_PLAYING &&
                System.nanoTime() < deadlineNs) {
                Thread.sleep(PLAYBACK_DRAIN_POLL_MS)
                delivered = activeTrack.playbackHeadPosition.toLong() and 0xffff_ffffL
            }
            // Ask the framework rather than inferring from the drain loop:
            // getUnderrunCount() is the HAL's own tally of buffer starvation,
            // which a write-side heuristic cannot see.
            telemetry?.noteAudio(
                requested = samples.size.toLong(),
                written = written.toLong(),
                underrun = activeTrack.underrunCount > 0,
            )
            if (delivered < samples.size) {
                Log.w(TAG, "AudioTrack drain timed out: delivered=$delivered/${samples.size}")
            } else {
                Log.i(
                    TAG,
                    "AudioTrack stop delivered=$delivered/${samples.size} " +
                        "underruns=${activeTrack.underrunCount}",
                )
            }
        } catch (_: InterruptedException) {
            // stop/replay teardown
        } finally {
            track?.let { runCatching { it.stop() }; runCatching { it.release() } }
            playing = false
            if (locked) playbackLock.unlock()
        }
    }

    private fun notifySegment(
        transcript: String,
        translation: String?,
        direction: TranslationDirection,
        lidConfidence: Float,
        executionPath: ExecutionPath,
    ) = runCatching {
        pipelineListener?.onSegment(
            PipelineSegment(
                transcript = transcript,
                translation = translation,
                direction = direction,
                lidConfidence = lidConfidence,
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
            // The smoke path intentionally bypasses LID but still exercises the
            // same VI QNN MT graph via a one-utterance helper.
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
        if (playAudio) play(audio, TTS_SAMPLE_RATE)
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
        const val ASR_WINDOW_OVERLAP_SAMPLES = SAMPLE_RATE / 2 // 500 ms across 10 s split
        const val MAX_QUEUED_SEGMENTS = 2
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
