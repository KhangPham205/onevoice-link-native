package com.onevoice.link.core

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "OneVoiceBench"

/**
 * One fully-explained utterance.
 *
 * Every latency column here is paired with the variable that makes it readable,
 * because on this pipeline the raw milliseconds are ambiguous on their own:
 *
 *  * `mtMs` without [mtOutputTokens] means nothing — the Marian decoder is
 *    autoregressive, so a slow row may simply be a longer sentence.
 *  * `ttsMs` without [ttsOutputAudioS] means nothing — Kokoro synthesises the
 *    whole utterance in one call, so cost scales with the audio produced.
 *  * `asrEncMs` is nearly constant regardless of speech length, because the
 *    encoder graph takes a static `[1,1000,80]` window. Comparing it against
 *    [speechDurationS] is what exposes that.
 *
 * The guard/hangover fields are split out rather than folded into `e2eMs`: they
 * are fixed VAD policy, not model cost, and leaving them inside would make any
 * model-side optimisation look like it changed nothing.
 */
internal data class UtteranceMetrics(
    val direction: String,
    val lidDecision: String,
    val lidConfidence: Float,
    val speechDurationS: Double,
    val forcedBoundary: Boolean,
    val executionPath: String,
    val lidMs: Long,
    val asrMs: Long,
    val mtMs: Long,
    val ttsMs: Long,
    val queueWaitMs: Long,
    /** Compute only: LID + ASR + MT + TTS. Whole-utterance TTS makes this TTFA. */
    val ttfaMs: Long,
    /** TTFA plus the synthesized audio's own duration. */
    val e2eMs: Long,
    val hangoverMs: Long,
    val echoGuardMs: Long,
    val mtOutputTokens: Int,
    val ttsOutputChars: Int,
    val ttsOutputAudioS: Double,
    val thermalStatus: Int,
    val powerMode: String,
    val underruns: Long,
    val framesRequested: Long,
    val framesWritten: Long,
) {
    /**
     * RTF against real speech duration, not the static 10 s ASR window.
     *
     * Declared once here and used everywhere so the two possible definitions
     * cannot be mixed: window-relative RTF would make the ASR encoder pass
     * trivially (350 ms / 10 s = 0.035) by describing the graph shape rather
     * than what a speaker waits for.
     */
    fun asrRtf(): Double =
        if (speechDurationS > 0.0) asrMs / (speechDurationS * 1000.0) else Double.NaN

    /** TTS cost per second of audio it produced. */
    fun ttsRtf(): Double =
        if (ttsOutputAudioS > 0.0) ttsMs / (ttsOutputAudioS * 1000.0) else Double.NaN

    fun toCsvRow(): String = listOf(
        direction, lidDecision, "%.4f".format(lidConfidence),
        "%.3f".format(speechDurationS), forcedBoundary, executionPath,
        lidMs, asrMs, mtMs, ttsMs, queueWaitMs, ttfaMs, e2eMs,
        hangoverMs, echoGuardMs, e2eMs - hangoverMs - echoGuardMs,
        mtOutputTokens, ttsOutputChars, "%.3f".format(ttsOutputAudioS),
        "%.4f".format(asrRtf()), "%.4f".format(ttsRtf()),
        thermalStatus, powerMode, underruns, framesRequested, framesWritten,
    ).joinToString(",")

    companion object {
        val CSV_HEADER = listOf(
            "direction", "lid_decision", "lid_confidence",
            "speech_duration_s", "forced_boundary", "execution_path",
            "lid_ms", "asr_ms", "mt_ms", "tts_ms", "queue_wait_ms",
            "ttfa_ms", "e2e_ms",
            "hangover_ms", "echo_guard_ms", "e2e_minus_hangover_ms",
            "mt_output_tokens", "tts_output_chars", "tts_output_audio_s",
            "asr_rtf", "tts_rtf",
            "thermal_status", "power_mode",
            "underruns", "frames_requested", "frames_written",
        ).joinToString(",")
    }
}

/** One HTP stage execution, as reported by the QNN profile handle. */
internal data class QnnStageSample(
    val stage: String,
    val wallUs: Long,
    val executeUs: Long,
    val acceleratorUs: Long,
    val queueWaitUs: Long,
    val profiled: Boolean,
) {
    /**
     * Time spent outside the accelerator: buffer binding, FastRPC transport and
     * the driver round trip. This is the part the app can actually shrink, so
     * it is the most actionable number the profile produces.
     */
    val hostOverheadUs: Long get() = (wallUs - acceleratorUs).coerceAtLeast(0L)
}

/**
 * Collects benchmark rows in memory and writes them out on demand.
 *
 * Not wired into the audio read loop: [record] is called from the inference
 * worker after an utterance is already finished, and the queue is lock-free so
 * a slow writer can never stall the caller.
 */
internal class BenchTelemetry(private val context: Context) {
    private val rows = ConcurrentLinkedQueue<UtteranceMetrics>()
    private val underruns = AtomicLong(0)
    private val framesRequested = AtomicLong(0)
    private val framesWritten = AtomicLong(0)

    /** Present only when the harness created the marker directory. */
    val enabled: Boolean get() = outputDir.isDirectory

    private val outputDir: File
        get() = File(context.filesDir, "bench")

    fun noteAudio(requested: Long, written: Long, underrun: Boolean) {
        framesRequested.addAndGet(requested)
        framesWritten.addAndGet(written)
        if (underrun) underruns.incrementAndGet()
    }

    fun audioCounters(): Triple<Long, Long, Long> =
        Triple(underruns.get(), framesRequested.get(), framesWritten.get())

    fun record(metrics: UtteranceMetrics) {
        if (!enabled) return
        rows.add(metrics)
        Log.i(TAG, "bench row: ${metrics.toCsvRow()}")
    }

    /**
     * Pull the QNN profile in the two-call order the native side requires:
     * stages first (which takes the snapshot), then values (which consume it).
     */
    fun drainQnnSamples(): List<QnnStageSample> {
        val stages = runCatching { OneVoiceNative.nativeDrainQnnProfileStages() }
            .getOrElse { return emptyList() }
        val values = runCatching { OneVoiceNative.nativeDrainQnnProfile() }
            .getOrElse { return emptyList() }
        val fields = 5
        if (stages.size * fields != values.size) {
            Log.w(TAG, "profile drain mismatch: ${stages.size} stages, ${values.size} values")
            return emptyList()
        }
        return stages.mapIndexed { index, stage ->
            val base = index * fields
            QnnStageSample(
                stage = stage,
                wallUs = values[base],
                executeUs = values[base + 1],
                acceleratorUs = values[base + 2],
                queueWaitUs = values[base + 3],
                profiled = values[base + 4] != 0L,
            )
        }
    }

    /**
     * Native heap, PSS and RSS for this process.
     *
     * RSS comes from /proc/self/status rather than Debug.getMemoryInfo because
     * the harness watches its slope over a soak; PSS alone hides growth that is
     * shared with another process.
     */
    fun memorySnapshot(): Map<String, Long> {
        val out = LinkedHashMap<String, Long>()
        val activityManager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val pid = android.os.Process.myPid()
        activityManager?.getProcessMemoryInfo(intArrayOf(pid))?.firstOrNull()?.let { info ->
            out["pss_kb"] = info.totalPss.toLong()
            out["private_dirty_kb"] = info.totalPrivateDirty.toLong()
        }
        runCatching {
            File("/proc/self/status").forEachLine { line ->
                when {
                    line.startsWith("VmRSS:") -> out["rss_kb"] = line.numericField()
                    line.startsWith("VmHWM:") -> out["peak_rss_kb"] = line.numericField()
                }
            }
        }
        // System-wide headroom: the §5.1 gate is about the whole device staying
        // healthy, not just this process's footprint.
        runCatching {
            var total = 0L
            var available = 0L
            File("/proc/meminfo").forEachLine { line ->
                when {
                    line.startsWith("MemTotal:") -> total = line.numericField()
                    line.startsWith("MemAvailable:") -> available = line.numericField()
                }
            }
            if (total > 0) {
                out["mem_total_kb"] = total
                out["mem_available_kb"] = available
                out["mem_headroom_pct"] = available * 100 / total
            }
        }
        return out
    }

    /**
     * True when Android killed a previous instance for memory pressure.
     *
     * Checked at start-up rather than trapped live: by the time the kill lands
     * this process is already gone, so the evidence is only visible to whatever
     * runs next.
     */
    fun wasKilledForMemory(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val activityManager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return false
        val reasons = runCatching {
            activityManager.getHistoricalProcessExitReasons(context.packageName, 0, 5)
        }.getOrNull().orEmpty()
        val killed = reasons.any { it.reason == ApplicationExitInfo.REASON_LOW_MEMORY }
        if (killed) Log.w(TAG, "previous instance was killed: REASON_LOW_MEMORY")
        return killed
    }

    /** Write everything collected so far; returns the file, or null if disabled. */
    fun flush(name: String = "bench.csv"): File? {
        if (!enabled) return null
        val target = File(outputDir, name)
        return runCatching {
            target.bufferedWriter().use { writer ->
                writer.appendLine(UtteranceMetrics.CSV_HEADER)
                rows.forEach { writer.appendLine(it.toCsvRow()) }
            }
            Log.i(TAG, "wrote ${rows.size} bench rows to ${target.absolutePath}")
            target
        }.getOrElse {
            Log.w(TAG, "bench flush failed", it)
            null
        }
    }

    private fun String.numericField(): Long =
        filter(Char::isDigit).toLongOrNull() ?: 0L
}
