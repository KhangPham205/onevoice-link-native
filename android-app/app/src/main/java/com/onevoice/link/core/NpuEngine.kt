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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.sqrt

private const val TAG = "OneVoiceNpu"

/** Selects whether speech synthesis stays on QNN or uses whole-utterance CPU Kokoro. */
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
 * Live VI -> EN pipeline. Audio/VAD and deterministic tensor transforms run on
 * bounded CPU buffers. Full-QNN mode executes all six learned stages on HTP;
 * hybrid mode executes ASR/MT on HTP and renders Kokoro once for the whole
 * translated utterance on CPU to preserve temporal context.
 *
 * Models normally live under internal `<filesDir>/models/npu/<direction>/`;
 * the deploy script stages them with `run-as` because direct external-storage
 * pushes are not visible to this app on the target Android 16 build.
 */
class NpuEngine(
    context: Context,
    private val ttsMode: NpuTtsMode = NpuTtsMode.QNN_BUCKETED,
) : EngineBackend {

    override val displayName: String = when (ttsMode) {
        NpuTtsMode.QNN_BUCKETED -> "QNN / Hexagon HTP · ASR → MT → TTS"
        NpuTtsMode.CPU_WHOLE_UTTERANCE ->
            "Hybrid · QNN ASR + MT · CPU Kokoro TTS"
    }
    override val supportsLiveTranslation: Boolean
        get() = direction == TranslationDirection.VI_TO_EN

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

    @Volatile
    private var pipelineListener: EngineBackend.PipelineListener? = null
    @Volatile
    private var pipeline: NpuPipeline? = null
    private val running = AtomicBoolean(false)
    private val releasePending = AtomicBoolean(false)
    private val playbackLock = ReentrantLock()
    private val segments = LinkedBlockingQueue<FloatArray>(MAX_QUEUED_SEGMENTS)
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

    /** The stages the manifest declares for [direction], before any load. */
    fun plannedStages(d: TranslationDirection = direction): List<StageStatus> =
        manifest.forDirection(d)?.let(::selectedStages)?.map { StageStatus(it) } ?: emptyList()

    fun directionSpec(d: TranslationDirection = direction): DirectionSpec? =
        manifest.forDirection(d)

    // ------------------------------------------------------------------ init
    /**
     * Brings up the QNN backend, then loads every stage of the current
     * direction. Returns true only when all stages loaded.
     */
    override fun initialize(): Boolean {
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

        if (!loadStagesFor(spec)) return false
        return runCatching {
            val directionRoot = File(modelRoot, direction.manifestId)
            val useCpuTts = ttsMode == NpuTtsMode.CPU_WHOLE_UTTERANCE
            val cpuTtsModel = if (useCpuTts) {
                listOf(
                    File(directionRoot, "tts_cpu.onnx"),
                    File(appContext.filesDir, "models/hf/tts-en/model.int8.onnx"),
                    File(appContext.filesDir, "models/hf/tts-en/model.onnx"),
                ).firstOrNull(File::isFile)
            } else {
                null
            }
            pipeline = NpuPipeline.load(
                directionRoot = directionRoot,
                asrStage = spec.stages.single { it.stage == "asr" },
                useCpuTts = useCpuTts,
                cpuTtsModel = cpuTtsModel,
            )
            lastError = null
            Log.i(TAG, "NPU live pipeline ready direction=$direction root=$modelRootPath")
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

    /** Load every stage of [spec], publishing progress as each one completes. */
    private fun loadStagesFor(spec: DirectionSpec): Boolean {
        val stagesToLoad = selectedStages(spec)
        var working = stagesToLoad.map { StageStatus(it) }
        stages = working
        publish()

        candidateRoots.forEach {
            Log.i(TAG, "candidate ${it.absolutePath} isDir=${it.isDirectory} " +
                "children=${it.list()?.size ?: -1}")
        }
        Log.i(TAG, "using modelRoot=$modelRootPath")

        var allOk = true
        stagesToLoad.forEachIndexed { i, stageSpec ->
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

            val contractError = if (report.ok) validateContract(stageSpec, report) else null
            val stageOk = report.ok && contractError == null

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
                    state = if (stageOk) StageState.LOADED else StageState.FAILED,
                    report = report,
                    error = report.error ?: contractError ?: socWarning,
                ),
            )
            stages = working
            publish()
            if (!stageOk) {
                allOk = false
                Log.e(TAG, "stage ${stageSpec.stage} failed: ${report.error ?: contractError}")
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
        if (ttsMode == NpuTtsMode.CPU_WHOLE_UTTERANCE) {
            spec.stages.filter { it.stage in HYBRID_QNN_STAGES }
        } else {
            spec.stages
        }

    private fun validateContract(spec: StageSpec, report: ModelLoadReport): String? {
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

    // ------------------------------------------------------------- direction
    /**
     * Switch direction. Contexts for the previous direction are released first:
     * the two cascades together are ~2 GB of weights, which is not worth keeping
     * resident. Callers must re-run [initialize] afterwards.
     */
    override fun setDirection(direction: TranslationDirection) {
        if (direction == this.direction) return
        stop()
        pipeline?.close()
        pipeline = null
        this.direction = direction
        if (runtimeUp) runCatching { OneVoiceNative.nativeReleaseModels() }
        stages = plannedStages(direction)
        lastError = null
        publish()
    }

    // ------------------------------------------------------------- lifecycle
    @SuppressLint("MissingPermission")
    override fun start(): Boolean {
        if (pipeline == null) {
            lastError = "NPU pipeline is not initialized."
            return false
        }
        if (!supportsLiveTranslation) {
            lastError = "EN → VI needs an English ASR QNN .bin, which is not present."
            return false
        }
        if (!running.compareAndSet(false, true)) return true
        lastError = null
        segments.clear()
        pipelineThread = Thread(::pipelineLoop, "onevoice-npu-pipeline").apply { start() }
        captureThread = Thread(::captureLoop, "onevoice-npu-capture").apply { start() }
        return true
    }

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

    override fun destroy() {
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

            fun offerCurrent(size: Int) {
                if (size <= 0) return
                val segment = current.copyOf(size)
                var sumSquares = 0.0
                var peak = 0f
                segment.forEach { sample ->
                    sumSquares += sample.toDouble() * sample
                    peak = maxOf(peak, kotlin.math.abs(sample))
                }
                Log.i(
                    TAG,
                    "Captured segment samples=$size durationMs=${size * 1_000L / SAMPLE_RATE} " +
                        "rms=${"%.5f".format(sqrt(sumSquares / size))} " +
                        "peak=${"%.5f".format(peak)}",
                )
                if (segments.remainingCapacity() == 0 ||
                    !segments.offer(segment)) {
                    Log.w(TAG, "Dropping speech segment: NPU inference queue is full")
                }
            }

            while (running.get()) {
                val count = record.read(frame, 0, frame.size)
                if (count < 0) error("AudioRecord.read failed with code $count")
                if (count == 0) continue
                if (playing) {
                    currentSize = 0; speechFrames = 0; silenceFrames = 0
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
                } else {
                    silenceFrames++
                }
                val endedBySilence = speechFrames >= MIN_SPEECH_FRAMES &&
                    silenceFrames >= END_SILENCE_FRAMES
                val rejectedBySilence = speechFrames < MIN_SPEECH_FRAMES &&
                    silenceFrames >= END_SILENCE_FRAMES
                val endedByLength = currentSize >= MAX_SEGMENT_SAMPLES
                if (endedBySilence) {
                    offerCurrent(currentSize)
                    currentSize = 0
                    speechFrames = 0
                    silenceFrames = 0
                    preRollSize = 0
                    preRollWrite = 0
                } else if (rejectedBySilence) {
                    currentSize = 0
                    speechFrames = 0
                    silenceFrames = 0
                    preRollSize = 0
                    preRollWrite = 0
                } else if (endedByLength) {
                    offerCurrent(MAX_SEGMENT_SAMPLES)
                    // The ASR graph is statically capped at 10 seconds. Carry a
                    // short overlap into the next window so a word crossing the
                    // boundary is not lost entirely.
                    val carry = minOf(ASR_WINDOW_OVERLAP_SAMPLES, currentSize)
                    current.copyInto(current, 0, currentSize - carry, currentSize)
                    currentSize = carry
                    speechFrames = 1
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

    private fun pipelineLoop() {
        try {
            while (running.get()) {
                val samples = segments.take()
                val active = pipeline ?: break
                val startedNs = System.nanoTime()
                val transcript = active.recognize(samples).trim()
                val asrNs = System.nanoTime()
                if (transcript.isEmpty()) continue
                notifySegment(transcript, null)
                val translation = active.translate(transcript).trim()
                val mtNs = System.nanoTime()
                notifySegment(transcript, translation)
                if (translation.isEmpty()) continue
                val audio = active.synthesize(translation)
                val ttsNs = System.nanoTime()
                if (audio.isEmpty()) {
                    notifyPipelineError("NPU TTS returned an empty waveform.")
                    continue
                }
                Log.i(
                    TAG,
                    "Pipeline transcript=\"$transcript\" translation=\"$translation\" " +
                        "audioSamples=${audio.size} audioMs=${audio.size * 1_000L / TTS_SAMPLE_RATE} " +
                        "asrMs=${(asrNs - startedNs) / 1_000_000L} " +
                        "mtMs=${(mtNs - asrNs) / 1_000_000L} " +
                        "ttsMs=${(ttsNs - mtNs) / 1_000_000L}",
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

    private fun notifySegment(transcript: String, translation: String?) =
        runCatching { pipelineListener?.onSegment(transcript, translation) }.let { Unit }

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

    private fun releasePipeline(loaded: NpuPipeline?) {
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
        val silenceTranscript = active.recognize(FloatArray(SAMPLE_RATE))
        val translated = active.translate(sourceText)
        check(translated.isNotBlank()) { "MT produced an empty result" }
        val audio = active.synthesize(translated)
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

    private companion object {
        /** Hexagon v73 = Snapdragon 8 Gen 2; matches the manifest's target SoC. */
        const val DSP_SKEL = "libQnnHtpV73Skel.so"
        const val SIGNED_ASSET_DIR = "dsp/signed"

        const val SAMPLE_RATE = 16_000
        const val TTS_SAMPLE_RATE = 24_000
        const val FRAME_SAMPLES = 320 // 20 ms: responsive VAD without 100 ms edge loss
        const val VAD_RMS_THRESHOLD = 0.008
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
