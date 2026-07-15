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
import kotlin.math.sqrt

private const val TAG = "OneVoiceHf"

/**
 * Emulator/dev backend: the full **bidirectional** S2ST cascade on CPU —
 *
 *   AudioRecord (16 kHz mono) → energy-VAD segmentation
 *     VI→EN: Parakeet-CTC-vi → opus-mt-vi-en → Kokoro-EN
 *     EN→VI: Conformer-small-en → opus-mt-en-vi → Kokoro-VI
 *   → AudioTrack
 *
 * Both directions stay resident after initialize() so a 2-person conversation
 * can flip direction instantly between turns (needs an AVD with >= 4 GB RAM).
 * The translation is ALWAYS spoken by the TTS matching the target language.
 *
 * Half-duplex: while the speaker is playing TTS output, the mic capture loop
 * drops frames and resets the in-progress segment, so the synthesized speech
 * can't feed back into ASR — essential when both people share one device.
 *
 * Models live under `<externalFilesDir>/models/hf/` (pushed by
 * tools/models/push-to-device.ps1):
 *   asr-vi/ asr-en/ mt-vi-en/ mt-en-vi/ tts-vi/ tts-en/ espeak-ng-data/
 */
class HfEngineBackend(context: Context) : EngineBackend {

    override val displayName = "HuggingFace / CPU (emulator)"

    override var lastError: String? = null
        private set

    private val modelRoot = File(context.getExternalFilesDir(null), "models/hf")

    /** Per-direction model bundle. */
    private class DirectionBundle(
        val recognizer: OfflineRecognizer,
        val translator: MarianTranslator,
        val tts: OfflineTts,
        val ttsSpeakerId: Int,
    )

    private var bundles: Map<TranslationDirection, DirectionBundle> = emptyMap()
    private var listener: EngineBackend.PipelineListener? = null

    @Volatile
    private var direction = TranslationDirection.VI_TO_EN

    private val running = AtomicBoolean(false)

    /** Half-duplex gate: true while TTS audio is being played back. */
    @Volatile
    private var playing = false

    /** Last synthesized audio, for the replay button. */
    @Volatile
    private var lastAudio: Pair<FloatArray, Int>? = null

    private var captureThread: Thread? = null
    private var pipelineThread: Thread? = null
    private val segments = LinkedBlockingQueue<FloatArray>()

    override fun setPipelineListener(listener: EngineBackend.PipelineListener?) {
        this.listener = listener
    }

    override fun setDirection(direction: TranslationDirection) {
        this.direction = direction
    }

    override fun replayLastAudio(): Boolean {
        val (samples, rate) = lastAudio ?: return false
        Thread({ play(samples, rate) }, "hf-replay").start()
        return true
    }

    // ------------------------------------------------------------------ init
    override fun initialize(): Boolean {
        if (bundles.isNotEmpty()) return true

        val espeakData = File(modelRoot, "espeak-ng-data")
        val missing = buildList {
            for (dir in listOf("asr-vi", "asr-en")) {
                if (asrModel(dir) == null) add("$dir/model(.int8).onnx")
                if (!File(modelRoot, "$dir/tokens.txt").exists()) add("$dir/tokens.txt")
            }
            for (dir in listOf("mt-vi-en", "mt-en-vi")) {
                if (!File(modelRoot, "$dir/encoder_model.onnx").exists()) add("$dir/encoder_model.onnx")
            }
            for (dir in listOf("tts-vi", "tts-en")) {
                if (ttsModel(dir) == null) add("$dir/model(.int8).onnx")
                if (!File(modelRoot, "$dir/voices.bin").exists()) add("$dir/voices.bin")
                if (!File(modelRoot, "$dir/tokens.txt").exists()) add("$dir/tokens.txt")
            }
            if (!espeakData.isDirectory) add("espeak-ng-data/")
        }
        if (missing.isNotEmpty()) {
            lastError = "Missing under ${modelRoot.absolutePath}: ${missing.joinToString()}. " +
                "Run tools/models/push-to-device.ps1."
            return false
        }

        return try {
            bundles = mapOf(
                TranslationDirection.VI_TO_EN to DirectionBundle(
                    recognizer = loadRecognizer("asr-vi"),
                    translator = MarianTranslator.load(File(modelRoot, "mt-vi-en")),
                    tts = loadKokoro("tts-en", espeakData),
                    ttsSpeakerId = 0, // kokoro-en v0.19 sid 0 = "af"
                ),
                TranslationDirection.EN_TO_VI to DirectionBundle(
                    recognizer = loadRecognizer("asr-en"),
                    translator = MarianTranslator.load(File(modelRoot, "mt-en-vi")),
                    tts = loadKokoro("tts-vi", espeakData),
                    ttsSpeakerId = 0,
                ),
            )
            true
        } catch (t: Throwable) {
            Log.e(TAG, "HF init failed", t)
            lastError = "Model load failed: ${t.message ?: t.javaClass.simpleName}"
            destroy()
            false
        }
    }

    private fun loadRecognizer(dir: String) = OfflineRecognizer(
        config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                nemo = OfflineNemoEncDecCtcModelConfig(model = asrModel(dir)!!.absolutePath),
                tokens = File(modelRoot, "$dir/tokens.txt").absolutePath,
                numThreads = 2,
                modelType = "nemo_ctc",
            ),
        ),
    )

    private fun loadKokoro(dir: String, espeakData: File) = OfflineTts(
        config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = ttsModel(dir)!!.absolutePath,
                    voices = File(modelRoot, "$dir/voices.bin").absolutePath,
                    tokens = File(modelRoot, "$dir/tokens.txt").absolutePath,
                    dataDir = espeakData.absolutePath,
                ),
                numThreads = 2,
            ),
            maxNumSentences = 1,
        ),
    )

    private fun asrModel(dir: String): File? =
        firstExisting("$dir/model.int8.onnx", "$dir/model.onnx")

    private fun ttsModel(dir: String): File? =
        firstExisting("$dir/model.int8.onnx", "$dir/model.onnx")

    // ----------------------------------------------------------------- start
    @SuppressLint("MissingPermission") // RECORD_AUDIO is granted before start()
    override fun start(): Boolean {
        if (bundles.isEmpty()) return false
        if (!running.compareAndSet(false, true)) return true

        captureThread = Thread(::captureLoop, "hf-capture").apply { start() }
        pipelineThread = Thread(::pipelineLoop, "hf-pipeline").apply { start() }
        return true
    }

    override fun stop() {
        running.set(false)
        captureThread?.join(2000); captureThread = null
        pipelineThread?.interrupt()
        pipelineThread?.join(4000); pipelineThread = null
        segments.clear()
    }

    override fun destroy() {
        stop()
        for (b in bundles.values) {
            runCatching { b.recognizer.release() }
            runCatching { b.tts.release() }
            runCatching { b.translator.close() }
        }
        bundles = emptyMap()
        lastAudio = null
    }

    // -------------------------------------------------------------- capture
    /** Mic → energy-VAD segmentation → [segments] queue (gated by [playing]). */
    @SuppressLint("MissingPermission")
    private fun captureLoop() {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, SAMPLE_RATE / 2),
        )
        try {
            record.startRecording()
            val frame = ShortArray(FRAME_SAMPLES)
            val current = ArrayList<Float>(SAMPLE_RATE * 4)
            var speechFrames = 0
            var silenceFrames = 0

            while (running.get()) {
                val n = record.read(frame, 0, frame.size)
                if (n <= 0) continue

                // Half-duplex: while the speaker plays TTS, drop mic input and
                // abandon any partial segment so playback can't loop into ASR.
                if (playing) {
                    current.clear(); speechFrames = 0; silenceFrames = 0
                    continue
                }

                var sumSq = 0.0
                for (i in 0 until n) sumSq += frame[i] * frame[i].toDouble()
                val rms = sqrt(sumSq / n) / 32768.0
                val isSpeech = rms > VAD_RMS_THRESHOLD

                if (isSpeech || speechFrames > 0) {
                    for (i in 0 until n) current.add(frame[i] / 32768f)
                }
                if (isSpeech) { speechFrames++; silenceFrames = 0 } else silenceFrames++

                val endBySilence = speechFrames >= MIN_SPEECH_FRAMES &&
                    silenceFrames >= END_SILENCE_FRAMES
                val endByLength = current.size >= MAX_SEGMENT_SAMPLES
                if ((endBySilence || endByLength) && current.isNotEmpty()) {
                    if (speechFrames >= MIN_SPEECH_FRAMES) {
                        segments.offer(current.toFloatArray())
                    }
                    current.clear(); speechFrames = 0; silenceFrames = 0
                }
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }

    // ------------------------------------------------------------- pipeline
    /** Segments → ASR → MT → TTS → speaker, strictly serial, per direction. */
    private fun pipelineLoop() {
        try {
            while (running.get()) {
                val samples = segments.take() // interrupted by stop()
                // Snapshot the direction once per segment so a mid-segment
                // toggle can't mix models from different directions.
                val bundle = bundles[direction] ?: continue

                // 1) ASR (source language)
                val stream = bundle.recognizer.createStream()
                val transcript = try {
                    stream.acceptWaveform(samples, SAMPLE_RATE)
                    bundle.recognizer.decode(stream)
                    bundle.recognizer.getResult(stream).text.trim()
                } finally {
                    stream.release()
                }
                if (transcript.isEmpty()) continue
                listener?.onSegment(transcript, null)

                // 2) MT (source → target)
                val translation = runCatching { bundle.translator.translate(transcript) }
                    .onFailure { Log.e(TAG, "MT failed", it) }
                    .getOrNull().orEmpty()
                listener?.onSegment(transcript, translation)
                if (translation.isBlank()) continue

                // 3) TTS — always the target-language voice speaking the translation.
                val audio = runCatching {
                    bundle.tts.generate(text = translation, sid = bundle.ttsSpeakerId, speed = 1.0f)
                }.onFailure { Log.e(TAG, "TTS failed", it) }.getOrNull() ?: continue
                if (audio.samples.isEmpty()) {
                    Log.w(TAG, "TTS produced no audio for: $translation")
                    continue
                }
                lastAudio = audio.samples to audio.sampleRate
                if (running.get()) play(audio.samples, audio.sampleRate)
            }
        } catch (_: InterruptedException) {
            // stop() requested
        }
    }

    private fun play(samples: FloatArray, sampleRate: Int) {
        val track = AudioTrack.Builder()
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
            .setBufferSizeInBytes(samples.size * 4)
            .build()
        playing = true
        try {
            track.play()
            // WRITE_BLOCKING only blocks until the data is QUEUED into the
            // track buffer (which holds the whole clip), not until it is heard.
            // Tear down immediately and the clip is cut after ~1-2 words — so
            // wait for the playback head to actually reach the end of the clip.
            track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            val totalFrames = samples.size // mono: 1 sample = 1 frame
            val deadline = System.currentTimeMillis() +
                totalFrames * 1000L / sampleRate + PLAYBACK_STALL_GRACE_MS
            while (track.playbackHeadPosition < totalFrames &&
                track.playState == AudioTrack.PLAYSTATE_PLAYING &&
                System.currentTimeMillis() < deadline
            ) {
                Thread.sleep(30)
            }
            // Let the hardware tail drain before reopening the mic gate.
            Thread.sleep(PLAYBACK_TAIL_MS)
        } catch (_: InterruptedException) {
            // stop()/replay teardown
        } finally {
            playing = false
            runCatching { track.stop() }
            track.release()
        }
    }

    private fun firstExisting(vararg rel: String): File? =
        rel.map { File(modelRoot, it) }.firstOrNull { it.exists() }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_SAMPLES = 1600            // 100 ms frames
        const val VAD_RMS_THRESHOLD = 0.015       // normalized RMS speech gate
        const val MIN_SPEECH_FRAMES = 3           // >= 300 ms of speech
        const val END_SILENCE_FRAMES = 7          // 700 ms silence ends a segment
        const val MAX_SEGMENT_SAMPLES = SAMPLE_RATE * 10 // 10 s hard cap
        const val PLAYBACK_TAIL_MS = 250L         // half-duplex gate tail
        const val PLAYBACK_STALL_GRACE_MS = 2000L // safety cap past clip length
    }
}
