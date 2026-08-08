package com.onevoice.link.hf

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.onevoice.link.core.EngineBackend
import com.onevoice.link.core.TranslationDirection
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.sqrt

private const val TAG = "OneVoiceHf"

/**
 * Complete offline speech-to-speech fallback:
 *
 * AudioRecord -> energy VAD -> sherpa NeMo CTC -> MarianMT/ORT ->
 * sherpa Kokoro -> AudioTrack.
 *
 * Only the selected direction is kept resident. This matters on an 8 GB phone:
 * loading both Parakeet/Kokoro pairs would duplicate more than a gigabyte of
 * weights. Direction therefore changes only while the backend is uninitialized.
 */
class HfEngineBackend(context: Context) : EngineBackend {
    override val displayName = "Offline CPU · complete ASR → MT → TTS"

    @Volatile
    override var lastError: String? = null
        private set

    private val candidateRoots = listOfNotNull(
        File(context.filesDir, "models/hf"),
        context.getExternalFilesDir(null)?.let { File(it, "models/hf") },
    )

    private val modelRoot: File
        get() = candidateRoots.firstOrNull { root ->
            root.isDirectory && root.list()?.isNotEmpty() == true
        } ?: candidateRoots.first()

    @Volatile
    private var direction = TranslationDirection.VI_TO_EN

    private class Bundle(
        val recognizer: OfflineRecognizer,
        val translator: MarianTranslator,
        val tts: OfflineTts,
        val ttsSpeakerId: Int,
    )

    @Volatile
    private var bundle: Bundle? = null

    @Volatile
    private var listener: EngineBackend.PipelineListener? = null
    private val running = AtomicBoolean(false)
    private val releasePending = AtomicBoolean(false)
    private val playbackLock = ReentrantLock()

    @Volatile
    private var playing = false

    @Volatile
    private var lastAudio: Pair<FloatArray, Int>? = null

    private var captureThread: Thread? = null
    private var pipelineThread: Thread? = null

    @Volatile
    private var replayThread: Thread? = null

    private val segments = LinkedBlockingQueue<FloatArray>(MAX_QUEUED_SEGMENTS)

    override fun setPipelineListener(listener: EngineBackend.PipelineListener?) {
        this.listener = listener
    }

    override fun setDirection(direction: TranslationDirection) {
        if (bundle != null && this.direction != direction) {
            lastError = "Stop and release the CPU models before changing direction."
            return
        }
        this.direction = direction
    }

    override fun initialize(): Boolean {
        if (bundle != null) return true
        if (releasePending.get()) {
            lastError = "Previous offline inference is still shutting down."
            return false
        }
        lastError = null

        val paths = DirectionPaths.forDirection(direction)
        val missing = requiredFiles(paths).filterNot { it.exists() }
        if (missing.isNotEmpty()) {
            lastError = buildString {
                append("Missing offline model files under ${modelRoot.absolutePath}: ")
                append(missing.joinToString { it.relativeTo(modelRoot).path })
                append(". Run tools/models/push-hf-models.sh ")
                append(if (direction == TranslationDirection.VI_TO_EN) "vi-en" else "en-vi")
                append(" R5CW72TP4EP.")
            }
            return false
        }

        return try {
            val espeakData = File(modelRoot, "espeak-ng-data")
            val loadedRecognizer = loadRecognizer(paths.asrDirectory)
            val loadedTranslator = try {
                MarianTranslator.load(File(modelRoot, paths.mtDirectory))
            } catch (failure: Throwable) {
                loadedRecognizer.release()
                throw failure
            }
            val loadedTts = try {
                loadKokoro(paths.ttsDirectory, espeakData)
            } catch (failure: Throwable) {
                loadedTranslator.close()
                loadedRecognizer.release()
                throw failure
            }
            bundle = Bundle(loadedRecognizer, loadedTranslator, loadedTts, ttsSpeakerId = 0)
            Log.i(TAG, "Offline pipeline ready direction=$direction root=${modelRoot.absolutePath}")
            true
        } catch (failure: Throwable) {
            Log.e(TAG, "Offline pipeline init failed", failure)
            lastError = "Offline model load failed: ${failure.message ?: failure.javaClass.simpleName}"
            destroy()
            false
        }
    }

    private fun requiredFiles(paths: DirectionPaths): List<File> = buildList {
        val asrDirectory = File(modelRoot, paths.asrDirectory)
        add(asrModel(asrDirectory) ?: File(asrDirectory, "model.int8.onnx"))
        add(File(asrDirectory, "tokens.txt"))

        val mtDirectory = File(modelRoot, paths.mtDirectory)
        listOf(
            "encoder_model.onnx", "decoder_model.onnx", "source.spm",
            "vocab.json", "mt_config.json",
        ).forEach { add(File(mtDirectory, it)) }

        val ttsDirectory = File(modelRoot, paths.ttsDirectory)
        add(ttsModel(ttsDirectory) ?: File(ttsDirectory, "model.onnx"))
        add(File(ttsDirectory, "voices.bin"))
        add(File(ttsDirectory, "tokens.txt"))

        add(File(modelRoot, "espeak-ng-data/phontab"))
        add(File(modelRoot, "espeak-ng-data/phondata"))
        add(File(modelRoot, "espeak-ng-data/phonindex"))
    }

    private fun loadRecognizer(relativeDirectory: String): OfflineRecognizer {
        val directory = File(modelRoot, relativeDirectory)
        return OfflineRecognizer(
            config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    nemo = OfflineNemoEncDecCtcModelConfig(
                        model = requireNotNull(asrModel(directory)).absolutePath,
                    ),
                    tokens = File(directory, "tokens.txt").absolutePath,
                    numThreads = 2,
                    modelType = "nemo_ctc",
                ),
            ),
        )
    }

    private fun loadKokoro(relativeDirectory: String, espeakData: File): OfflineTts {
        val directory = File(modelRoot, relativeDirectory)
        return OfflineTts(
            config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    kokoro = OfflineTtsKokoroModelConfig(
                        model = requireNotNull(ttsModel(directory)).absolutePath,
                        voices = File(directory, "voices.bin").absolutePath,
                        tokens = File(directory, "tokens.txt").absolutePath,
                        dataDir = espeakData.absolutePath,
                    ),
                    numThreads = 2,
                ),
                maxNumSentences = 1,
            ),
        )
    }

    private fun asrModel(directory: File): File? = firstExisting(
        File(directory, "model.int8.onnx"),
        File(directory, "model.onnx"),
    )

    private fun ttsModel(directory: File): File? = firstExisting(
        File(directory, "model.int8.onnx"),
        File(directory, "model.onnx"),
    )

    @SuppressLint("MissingPermission")
    override fun start(): Boolean {
        if (bundle == null) {
            lastError = "Offline pipeline is not initialized."
            return false
        }
        if (!running.compareAndSet(false, true)) return true
        lastError = null
        segments.clear()
        pipelineThread = Thread(::pipelineLoop, "onevoice-hf-pipeline").apply { start() }
        captureThread = Thread(::captureLoop, "onevoice-hf-capture").apply { start() }
        return true
    }

    override fun stop() {
        running.set(false)
        val capture = captureThread
        val pipeline = pipelineThread
        val replay = replayThread
        capture?.interrupt()
        pipeline?.interrupt()
        replay?.interrupt()
        joinWorker(capture, CAPTURE_JOIN_MS)
        joinWorker(pipeline, PIPELINE_JOIN_MS)
        joinWorker(replay, REPLAY_JOIN_MS)
        if (capture?.isAlive != true && captureThread === capture) captureThread = null
        if (pipeline?.isAlive != true && pipelineThread === pipeline) pipelineThread = null
        if (replay?.isAlive != true && replayThread === replay) replayThread = null
        segments.clear()
    }

    override fun destroy() {
        stop()
        val loaded = bundle
        bundle = null
        lastAudio = null
        if (loaded == null) return

        val worker = pipelineThread?.takeIf { it.isAlive }
        if (worker == null) {
            releaseBundle(loaded)
        } else {
            // ORT/sherpa native calls are not guaranteed to honor Java thread
            // interruption. Never release a session out from under such a call;
            // wait off the UI thread and free it immediately after the worker exits.
            releasePending.set(true)
            Thread(
                {
                    try {
                        worker.join()
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    } finally {
                        releaseBundle(loaded)
                        releasePending.set(false)
                        if (pipelineThread === worker) pipelineThread = null
                    }
                },
                "onevoice-hf-release",
            ).start()
        }
    }

    @Synchronized
    override fun replayLastAudio(): Boolean {
        val (samples, rate) = lastAudio ?: return false
        if (replayThread?.isAlive == true) return false
        val thread = Thread(
            {
                try {
                    play(samples, rate)
                } catch (failure: Throwable) {
                    Log.e(TAG, "Replay failed", failure)
                    notifyPipelineError(
                        "Audio replay failed: ${failure.message ?: failure.javaClass.simpleName}",
                    )
                } finally {
                    synchronized(this) {
                        if (replayThread === Thread.currentThread()) replayThread = null
                    }
                }
            },
            "onevoice-hf-replay",
        )
        replayThread = thread
        thread.start()
        return true
    }

    @SuppressLint("MissingPermission")
    private fun captureLoop() {
        val minimumBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minimumBuffer <= 0) {
            running.set(false)
            notifyPipelineStopped("AudioRecord does not support 16 kHz mono PCM on this route.")
            pipelineThread?.interrupt()
            return
        }
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minimumBuffer, SAMPLE_RATE / 2),
            )
        } catch (failure: Throwable) {
            Log.e(TAG, "AudioRecord creation failed", failure)
            running.set(false)
            notifyPipelineStopped(
                "Microphone initialization failed: ${failure.message ?: failure.javaClass.simpleName}",
            )
            pipelineThread?.interrupt()
            return
        }
        try {
            check(record.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord initialization failed" }
            record.startRecording()
            val frame = ShortArray(FRAME_SAMPLES)
            // Primitive and strictly capped: ArrayList<Float> boxes every sample
            // and creates substantial GC pressure during long utterances.
            val current = FloatArray(MAX_SEGMENT_SAMPLES + FRAME_SAMPLES)
            var currentSize = 0
            var speechFrames = 0
            var silenceFrames = 0

            while (running.get()) {
                val count = record.read(frame, 0, frame.size)
                if (count < 0) error("AudioRecord.read failed with code $count")
                if (count == 0) continue
                if (playing) {
                    currentSize = 0
                    speechFrames = 0
                    silenceFrames = 0
                    continue
                }

                var sumSquares = 0.0
                for (index in 0 until count) {
                    val value = frame[index].toDouble()
                    sumSquares += value * value
                }
                val speech = sqrt(sumSquares / count) / 32_768.0 > VAD_RMS_THRESHOLD
                if (speech || speechFrames > 0) {
                    val appendCount = minOf(count, current.size - currentSize)
                    for (index in 0 until appendCount) {
                        current[currentSize + index] = frame[index] / 32_768f
                    }
                    currentSize += appendCount
                }
                if (speech) {
                    speechFrames++
                    silenceFrames = 0
                } else {
                    silenceFrames++
                }

                val endedBySilence = speechFrames >= MIN_SPEECH_FRAMES &&
                    silenceFrames >= END_SILENCE_FRAMES
                val endedByLength = currentSize >= MAX_SEGMENT_SAMPLES
                if ((endedBySilence || endedByLength) && currentSize > 0) {
                    if (speechFrames >= MIN_SPEECH_FRAMES) {
                        // Backpressure is deliberate: keep the newest capture
                        // thread realtime instead of accumulating unbounded RAM.
                        if (
                            segments.remainingCapacity() == 0 ||
                            !segments.offer(current.copyOf(currentSize))
                        ) {
                            Log.w(TAG, "Dropping speech segment: inference queue is full")
                        }
                    }
                    currentSize = 0
                    speechFrames = 0
                    silenceFrames = 0
                }
            }
        } catch (failure: Throwable) {
            if (running.get()) {
                Log.e(TAG, "Capture loop failed", failure)
                running.set(false)
                notifyPipelineStopped(
                    "Microphone capture failed: ${failure.message ?: failure.javaClass.simpleName}",
                )
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
                val loaded = bundle ?: break

                val stream = loaded.recognizer.createStream()
                val transcript = try {
                    stream.acceptWaveform(samples, SAMPLE_RATE)
                    loaded.recognizer.decode(stream)
                    loaded.recognizer.getResult(stream).text.trim()
                } finally {
                    stream.release()
                }
                if (transcript.isEmpty()) continue
                notifySegment(transcript, null)

                val translation = try {
                    loaded.translator.translate(transcript)
                } catch (failure: Throwable) {
                    Log.e(TAG, "Translation failed", failure)
                    notifyPipelineError(
                        "Translation failed: ${failure.message ?: failure.javaClass.simpleName}",
                    )
                    continue
                }
                notifySegment(transcript, translation)
                if (translation.isBlank()) continue

                val audio = try {
                    loaded.tts.generate(translation, loaded.ttsSpeakerId, 1.0f)
                } catch (failure: Throwable) {
                    Log.e(TAG, "TTS failed", failure)
                    notifyPipelineError(
                        "Speech synthesis failed: ${failure.message ?: failure.javaClass.simpleName}",
                    )
                    continue
                }
                if (audio.samples.isEmpty()) {
                    Log.w(TAG, "TTS returned an empty waveform")
                    notifyPipelineError("Speech synthesis returned an empty waveform.")
                    continue
                }
                lastAudio = audio.samples to audio.sampleRate
                notifyAudioReady()
                if (running.get()) play(audio.samples, audio.sampleRate)
            }
        } catch (_: InterruptedException) {
            // stop() requested
        } catch (failure: Throwable) {
            Log.e(TAG, "Pipeline failed", failure)
            running.set(false)
            notifyPipelineStopped(
                "Pipeline failed: ${failure.message ?: failure.javaClass.simpleName}",
            )
            captureThread?.interrupt()
        }
    }

    private fun play(samples: FloatArray, sampleRate: Int) {
        require(samples.isNotEmpty()) { "Cannot play an empty waveform" }
        require(sampleRate in MIN_PLAYBACK_SAMPLE_RATE..MAX_PLAYBACK_SAMPLE_RATE) {
            "Invalid playback sample rate: $sampleRate"
        }

        var locked = false
        var track: AudioTrack? = null
        try {
            playbackLock.lockInterruptibly()
            locked = true

            val minimumBuffer = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_FLOAT,
            )
            check(minimumBuffer > 0) {
                "AudioTrack does not support ${sampleRate} Hz mono float PCM"
            }
            val desiredFrames = minOf(samples.size, maxOf(1, sampleRate / 4))
            val bufferBytes = maxOf(minimumBuffer, desiredFrames * Float.SIZE_BYTES)
            val activeTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferBytes)
                .build()
            track = activeTrack
            check(activeTrack.state == AudioTrack.STATE_INITIALIZED) {
                "AudioTrack initialization failed"
            }

            playing = true
            activeTrack.play()
            var written = 0
            val writeChunk = maxOf(1, sampleRate / 4)
            while (written < samples.size) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val count = activeTrack.write(
                    samples,
                    written,
                    minOf(writeChunk, samples.size - written),
                    AudioTrack.WRITE_BLOCKING,
                )
                check(count > 0) { "AudioTrack.write failed with code $count" }
                written += count
            }

            val deadline = System.currentTimeMillis() +
                samples.size * 1_000L / sampleRate + PLAYBACK_STALL_GRACE_MS
            while (
                (activeTrack.playbackHeadPosition.toLong() and 0xffff_ffffL) <
                samples.size.toLong() &&
                activeTrack.playState == AudioTrack.PLAYSTATE_PLAYING &&
                System.currentTimeMillis() < deadline
            ) {
                Thread.sleep(30)
            }
            Thread.sleep(PLAYBACK_TAIL_MS)
        } catch (_: InterruptedException) {
            // stop/replay teardown
        } finally {
            track?.let { activeTrack ->
                runCatching { activeTrack.stop() }
                runCatching { activeTrack.release() }
            }
            playing = false
            if (locked) playbackLock.unlock()
        }
    }

    private fun notifySegment(transcript: String, translation: String?) {
        runCatching { listener?.onSegment(transcript, translation) }
            .onFailure { Log.e(TAG, "Pipeline listener rejected a segment", it) }
    }

    private fun notifyAudioReady() {
        runCatching { listener?.onAudioReady() }
            .onFailure { Log.e(TAG, "Pipeline listener rejected audio-ready event", it) }
    }

    private fun notifyPipelineError(message: String) {
        lastError = message
        runCatching { listener?.onPipelineError(message) }
            .onFailure { Log.e(TAG, "Pipeline listener rejected an error", it) }
    }

    private fun notifyPipelineStopped(message: String) {
        lastError = message
        runCatching { listener?.onPipelineStopped(message) }
            .onFailure { Log.e(TAG, "Pipeline listener rejected a stopped event", it) }
    }

    private fun joinWorker(thread: Thread?, timeoutMs: Long) {
        if (thread == null || thread === Thread.currentThread()) return
        try {
            thread.join(timeoutMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun releaseBundle(loaded: Bundle) {
        // Reverse construction order.
        runCatching { loaded.tts.release() }
            .onFailure { Log.e(TAG, "Failed to release offline TTS", it) }
        runCatching { loaded.translator.close() }
            .onFailure { Log.e(TAG, "Failed to release Marian translator", it) }
        runCatching { loaded.recognizer.release() }
            .onFailure { Log.e(TAG, "Failed to release offline recognizer", it) }
    }

    private fun firstExisting(vararg files: File): File? = files.firstOrNull { it.isFile }

    private data class DirectionPaths(
        val asrDirectory: String,
        val mtDirectory: String,
        val ttsDirectory: String,
    ) {
        companion object {
            fun forDirection(direction: TranslationDirection): DirectionPaths =
                if (direction == TranslationDirection.VI_TO_EN) {
                    DirectionPaths("asr-vi", "mt-vi-en", "tts-en")
                } else {
                    DirectionPaths("asr-en", "mt-en-vi", "tts-vi")
                }
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_SAMPLES = 1_600
        const val VAD_RMS_THRESHOLD = 0.015
        const val MIN_SPEECH_FRAMES = 3
        const val END_SILENCE_FRAMES = 7
        const val MAX_SEGMENT_SAMPLES = SAMPLE_RATE * 10
        const val MAX_QUEUED_SEGMENTS = 2
        const val PLAYBACK_TAIL_MS = 250L
        const val PLAYBACK_STALL_GRACE_MS = 2_000L
        const val CAPTURE_JOIN_MS = 2_000L
        const val PIPELINE_JOIN_MS = 4_000L
        const val REPLAY_JOIN_MS = 2_000L
        const val MIN_PLAYBACK_SAMPLE_RATE = 8_000
        const val MAX_PLAYBACK_SAMPLE_RATE = 192_000
    }
}
