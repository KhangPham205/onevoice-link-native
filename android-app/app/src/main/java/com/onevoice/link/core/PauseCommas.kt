package com.onevoice.link.core

import kotlin.math.sqrt

/**
 * Marks the VI ASR words that follow a pause, so [transcriptOf] can put a
 * comma there.
 *
 * Grammar rules ([VietnamesePunctuation]) cannot see a clause boundary that no
 * word announces: "có ba mươi hai công nhân, vắng hai người nghỉ phép" has no
 * conjunction, only the speaker's pause, and Marian read it run-on as
 * "workers missing their leave". On the 45 synthetic Vietnamese demo turns,
 * 41 of the script's 43 commas had a silence of at least 250 ms, with 11
 * silences elsewhere.
 *
 * Pauses are found in the audio, not between token times: GIPFormer's
 * emission offsets run 70-350 ms ahead of the speech, unevenly ("dạ@30
 * cổ@670" for "dạ" spoken at 380-580 ms and "cổ" from 940 ms), so the span
 * between two emissions straddles the pause. Each silence of at least
 * [mark]'s length between the first and last speech frame goes before the
 * first word emitted at or after the silence starts. "Quiet" is below a tenth
 * of the window's 90th-percentile 20 ms frame RMS, so it follows the
 * speaker's level and the mic gain.
 */
internal object PauseCommas {
    private const val FRAME_SAMPLES = 320 // 20 ms at 16 kHz
    private const val SAMPLES_PER_MS = 16
    private const val QUIET_FRACTION = 0.1f

    /**
     * [words] with [AsrWord.pauseBefore] set after each pause of at least
     * [minPauseMs], and [AsrWord.sentenceBefore] too when it lasts at least
     * [sentencePauseMs] (0 = never). [samples] is the window that was decoded,
     * starting at turn sample [windowStart]. [minPauseMs] <= 0 = off.
     */
    fun mark(
        words: List<AsrWord>,
        samples: FloatArray,
        windowStart: Int,
        minPauseMs: Int,
        sentencePauseMs: Int = 0,
    ): List<AsrWord> = measure(words, samples, windowStart, minPauseMs, sentencePauseMs).first

    /** [mark] plus "pauses=start+length ms words=word@ms" for the log. */
    fun measure(
        words: List<AsrWord>,
        samples: FloatArray,
        windowStart: Int,
        minPauseMs: Int,
        sentencePauseMs: Int = 0,
    ): Pair<List<AsrWord>, String> {
        if (minPauseMs <= 0 || words.size < 2) return words to ""
        val frames = samples.size / FRAME_SAMPLES
        if (frames == 0) return words to ""
        val rms = FloatArray(frames) { f ->
            var sum = 0.0
            for (k in f * FRAME_SAMPLES until (f + 1) * FRAME_SAMPLES) sum += samples[k] * samples[k]
            sqrt(sum / FRAME_SAMPLES).toFloat()
        }
        val loud = rms.sortedArray()[((frames - 1) * 0.9).toInt()]
        if (loud <= 0f) return words to ""
        val quiet = QUIET_FRACTION * loud
        val first = rms.indexOfFirst { it >= quiet }
        val last = rms.indexOfLast { it >= quiet }
        val minFrames = (minPauseMs * SAMPLES_PER_MS + FRAME_SAMPLES - 1) / FRAME_SAMPLES

        // Silences inside the speech: start frame and length.
        val pauses = ArrayList<Pair<Int, Int>>()
        var run = 0
        for (f in first..last) {
            if (rms[f] < quiet) {
                run++
            } else {
                if (run >= minFrames) pauses += (f - run) to run
                run = 0
            }
        }

        val emittedMs = words.map { (it.startSample - windowStart) / SAMPLES_PER_MS }
        val after = HashMap<Int, Int>() // word index -> pause ms before it
        for ((start, length) in pauses) {
            val startMs = start * FRAME_SAMPLES / SAMPLES_PER_MS
            val i = emittedMs.indexOfFirst { it >= startMs }
            if (i >= 1) after[i] = maxOf(after[i] ?: 0, length * FRAME_SAMPLES / SAMPLES_PER_MS)
        }
        val marked = words.mapIndexed { i, word ->
            val ms = after[i] ?: return@mapIndexed word
            word.copy(pauseBefore = true, sentenceBefore = sentencePauseMs in 1..ms)
        }
        val log = buildString {
            append("pauses=")
            append(pauses.joinToString(",") { (s, n) -> "${s * 20}+${n * 20}" }.ifEmpty { "-" })
            append(" words=")
            append(words.indices.joinToString(" ") { "${words[it].text}@${emittedMs[it]}" })
        }
        return marked to log
    }
}
