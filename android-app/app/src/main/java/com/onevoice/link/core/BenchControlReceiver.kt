package com.onevoice.link.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "OneVoiceBenchCtl"

/**
 * adb-driven control surface for the benchmark skill.
 *
 * A receiver rather than an exported Activity or a socket: the harness already
 * talks to the device over adb, and `am broadcast` needs no port, no foreground
 * window and no extra permission. It must be exported (this Android build drops
 * shell broadcasts to non-exported receivers; see the manifest), so the guard
 * is the bench directory below, which only run-as on a debuggable build can
 * create.
 *
 * Nothing here touches the audio path. Every action either flips a flag read
 * between utterances or writes a file, and all of it is inert unless the
 * harness has created `<filesDir>/bench`.
 *
 * ```
 * adb shell am broadcast -a com.onevoice.link.BENCH \
 *   --es cmd profiling --ez on true -n com.onevoice.link/.core.BenchControlReceiver
 * ```
 */
class BenchControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val telemetry = runCatching { BenchTelemetry(context.applicationContext) }
            .getOrNull() ?: return
        if (!telemetry.enabled) {
            Log.i(TAG, "ignoring ${intent.getStringExtra(EXTRA_CMD)}: bench dir absent")
            return
        }
        when (val command = intent.getStringExtra(EXTRA_CMD)) {
            "profiling" -> {
                val on = intent.getBooleanExtra(EXTRA_ON, false)
                val ok = runCatching { OneVoiceNative.nativeSetQnnProfiling(on) }
                    .getOrDefault(false)
                Log.i(TAG, "profiling=$on applied=$ok")
            }

            "power" -> {
                val mode = intent.getStringExtra(EXTRA_MODE) ?: "burst"
                val latency = intent.getIntExtra(EXTRA_LATENCY_US, 100)
                val polling = intent.getIntExtra(EXTRA_POLLING_US, 9999)
                val error = runCatching {
                    OneVoiceNative.nativeApplyHtpPowerConfig(mode, latency, polling)
                }.getOrElse { it.message }
                Log.i(TAG, "power mode=$mode latency=$latency polling=$polling " +
                    "result=${error ?: "ok"}")
            }

            "memory" -> {
                // Logged as one line so the harness can pair a snapshot with the
                // adb-side `dumpsys meminfo -d` taken at the same moment; the
                // dmabuf delta only shows up on the dumpsys side, because DSP
                // allocations never appear in this process's PSS.
                val snapshot = telemetry.memorySnapshot()
                Log.i(TAG, "memory ${snapshot.entries.joinToString(" ") { "${it.key}=${it.value}" }}")
            }

            "flush" -> {
                val name = intent.getStringExtra(EXTRA_NAME) ?: "bench.csv"
                val file = telemetry.flush(name)
                Log.i(TAG, "flush -> ${file?.absolutePath ?: "failed"}")
            }

            "qnn" -> {
                // host_overhead_us is the actionable half of the profile: wall
                // time minus accelerator time is what the app itself can shrink.
                val samples = telemetry.drainQnnSamples()
                samples.forEach { sample ->
                    Log.i(
                        TAG,
                        "qnn stage=${sample.stage} wall_us=${sample.wallUs} " +
                            "execute_us=${sample.executeUs} " +
                            "accel_us=${sample.acceleratorUs} " +
                            "queue_wait_us=${sample.queueWaitUs} " +
                            "host_overhead_us=${sample.hostOverheadUs} " +
                            "profiled=${sample.profiled}",
                    )
                }
                Log.i(TAG, "qnn drained=${samples.size}")
            }

            "lowmem" -> {
                Log.i(TAG, "lowmem killed_previously=${telemetry.wasKilledForMemory()}")
            }

            "synth" -> {
                // Build a fixture by running the on-device TTS, then decimating
                // 24 kHz -> 16 kHz so the result can be fed back in as if it
                // were captured audio.
                //
                // Using the app's own Kokoro rather than a host tool keeps the
                // fixture reproducible from the repo (same model, same eSpeak
                // data) and avoids needing a phonemizer on the workstation. It
                // is synthetic speech, which is the point: identical every run.
                val text = intent.getStringExtra(EXTRA_TEXT)
                val dirId = intent.getStringExtra(EXTRA_DIRECTION) ?: "vi-en"
                val out = intent.getStringExtra(EXTRA_PATH)
                val engine = NpuEngine.current()
                if (text == null || out == null || engine == null) {
                    Log.w(TAG, "synth ignored: text=$text out=$out engine=${engine != null}")
                    return
                }
                val direction = when (dirId) {
                    "vi-en" -> TranslationDirection.VI_TO_EN   // speaks English
                    "en-vi" -> TranslationDirection.EN_TO_VI   // speaks Vietnamese
                    else -> {
                        Log.w(TAG, "synth: unknown direction $dirId")
                        return
                    }
                }
                val result = runCatching { engine.benchSynthesize(text, direction) }
                    .getOrElse {
                        Log.w(TAG, "synth failed", it)
                        return
                    }
                if (result == null || result.isEmpty()) {
                    Log.w(TAG, "synth produced no audio")
                    return
                }
                // Same band-limited resampler that brings Supertonic to 24 kHz,
                // so Kokoro and Supertonic fixtures share one filter.
                val decimated = resamplePcm(result, NpuEngine.TTS_SAMPLE_RATE, NpuEngine.SAMPLE_RATE)
                // Optional untouched 24 kHz copy for listening tests.
                intent.getStringExtra(EXTRA_RAW_PATH)?.let { rawPath ->
                    runCatching { File(rawPath).writeBytes(littleEndianFloats(result)) }
                        .onFailure { Log.w(TAG, "synth raw write failed", it) }
                }
                runCatching { File(out).writeBytes(littleEndianFloats(decimated)) }
                    .onSuccess {
                        Log.i(TAG, "synth wrote $out samples=${decimated.size} " +
                            "seconds=${"%.2f".format(decimated.size / 16000.0)}")
                    }
                    .onFailure { Log.w(TAG, "synth write failed", it) }
            }

            "vadprobe" -> {
                // Run the capture VAD over a fixture, bypassing the microphone,
                // so a weak live result can be split into "detector" versus
                // "acoustic path" causes. Logs the per-second peak score.
                val path = intent.getStringExtra(EXTRA_PATH) ?: return
                val bytes = runCatching { File(path).readBytes() }.getOrElse {
                    Log.w(TAG, "vadprobe failed to read $path", it)
                    return
                }
                val samples = FloatArray(bytes.size / Float.SIZE_BYTES)
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(samples)
                val mode = VadMode.parse(intent.getStringExtra(EXTRA_MODE) ?: "SILERO")
                createSpeechDetector(mode, context.assets, NpuEngine.VAD_RMS_THRESHOLD).use { vad ->
                    val frame = ShortArray(NpuEngine.FRAME_SAMPLES)
                    var offset = 0
                    var second = 0
                    while (offset < samples.size) {
                        var peak = 0f
                        var speech = 0
                        repeat(50) {
                            val count = minOf(frame.size, samples.size - offset)
                            if (count <= 0) return@repeat
                            for (i in 0 until count) {
                                frame[i] = (samples[offset + i].coerceIn(-1f, 1f) * 32_767f).toInt().toShort()
                            }
                            offset += count
                            if (vad.isSpeech(frame, count)) speech++
                            peak = maxOf(peak, vad.lastScore)
                        }
                        Log.i(TAG, "vadprobe ${vad.name} t=${second++}s peak=${"%.3f".format(peak)} speechFrames=$speech")
                    }
                }
            }

            "feed" -> {
                // Fixture PCM instead of the microphone: every iteration must
                // see the identical utterance or the percentiles describe the
                // room, not the pipeline. The file holds little-endian float32
                // at 16 kHz — the same representation the capture loop hands to
                // the inference worker, so no conversion sits in between.
                val path = intent.getStringExtra(EXTRA_PATH)
                val engine = NpuEngine.current()
                if (path == null || engine == null) {
                    Log.w(TAG, "feed ignored: path=$path engine=${engine != null}")
                    return
                }
                val file = File(path)
                if (!file.isFile) {
                    Log.w(TAG, "feed ignored: $path is not a file")
                    return
                }
                val bytes = runCatching { file.readBytes() }.getOrElse {
                    Log.w(TAG, "feed failed to read $path", it)
                    return
                }
                val samples = FloatArray(bytes.size / Float.SIZE_BYTES)
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    .asFloatBuffer().get(samples)
                val direction = when (intent.getStringExtra(EXTRA_DIRECTION) ?: "vi-en") {
                    "vi-en" -> TranslationDirection.VI_TO_EN
                    "en-vi" -> TranslationDirection.EN_TO_VI
                    // Production routing: the router, not the harness, picks the branch.
                    "auto" -> null
                    else -> {
                        Log.w(TAG, "feed ignored: invalid direction")
                        return
                    }
                }
                // The tag names a file under bench/tts, so it must stay a plain id.
                val tag = intent.getStringExtra(EXTRA_TAG)
                if (tag != null && !TAG_PATTERN.matches(tag)) {
                    Log.w(TAG, "feed ignored: invalid tag")
                    return
                }
                val accepted = engine.benchFeed(
                    samples,
                    direction = direction,
                    forcedBoundary = intent.getBooleanExtra(EXTRA_FORCED, false),
                    tag = tag,
                )
                Log.i(TAG, "feed ${file.name} samples=${samples.size} tag=$tag accepted=$accepted")
            }

            "replay" -> {
                // Long-turn harness: a 16 kHz mono WAV (or .f32 fixture) runs
                // through the live capture path (segmenter, coordinator, TTS,
                // half-duplex playback) instead of being pushed past it like
                // "feed". mode=paced follows the wall clock; mode=fast is for
                // coverage only. baseline=B0..B3 picks the policy for this run.
                val path = intent.getStringExtra(EXTRA_PATH)
                val engine = NpuEngine.current()
                if (path == null || engine == null) {
                    Log.w(TAG, "replay ignored: path=$path engine=${engine != null}")
                    return
                }
                val file = File(path)
                if (!file.isFile) {
                    Log.w(TAG, "replay ignored: $path is not a file")
                    return
                }
                val tag = intent.getStringExtra(EXTRA_TAG)
                if (tag != null && !TAG_PATTERN.matches(tag)) {
                    Log.w(TAG, "replay ignored: invalid tag")
                    return
                }
                val paced = when (val mode = intent.getStringExtra(EXTRA_MODE) ?: "paced") {
                    "paced" -> true
                    "fast" -> false
                    else -> {
                        Log.w(TAG, "replay ignored: mode must be paced or fast (got $mode)")
                        return
                    }
                }
                val baseline = intent.getStringExtra(EXTRA_BASELINE)
                val softSplitMs = intent.getStringExtra(EXTRA_SOFT_SPLIT_MS)?.toIntOrNull()
                val accepted = engine.benchReplay(file, paced, baseline, tag, softSplitMs)
                Log.i(
                    TAG,
                    "replay ${file.name} paced=$paced baseline=$baseline tag=$tag accepted=$accepted" +
                        (if (accepted) "" else " error=${engine.lastError}"),
                )
            }

            "stop" -> {
                // End a replay or live session from the harness. stop() joins
                // workers for seconds, so it runs off the receiver's main thread.
                val engine = NpuEngine.current() ?: return
                Thread({ engine.stop() }, "onevoice-bench-stop").start()
                Log.i(TAG, "stop requested")
            }

            "newlog" -> {
                NpuEngine.current()?.rotateBenchLog()
                Log.i(TAG, "newlog rotated tagged-fixture log")
            }

            else -> Log.w(TAG, "unknown bench command: $command")
        }
    }

    private companion object {
        const val EXTRA_CMD = "cmd"
        const val EXTRA_ON = "on"
        const val EXTRA_MODE = "mode"
        const val EXTRA_LATENCY_US = "latency_us"
        const val EXTRA_POLLING_US = "polling_us"
        const val EXTRA_NAME = "name"
        const val EXTRA_PATH = "path"
        const val EXTRA_FORCED = "forced"
        const val EXTRA_TEXT = "text"
        const val EXTRA_DIRECTION = "direction"
        const val EXTRA_RAW_PATH = "raw"
        const val EXTRA_TAG = "tag"
        const val EXTRA_BASELINE = "baseline"
        const val EXTRA_SOFT_SPLIT_MS = "softSplitMs"
        val TAG_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")

        private fun littleEndianFloats(samples: FloatArray): ByteArray {
            val buffer = ByteBuffer.allocate(samples.size * Float.SIZE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
            samples.forEach(buffer::putFloat)
            return buffer.array()
        }
    }
}
