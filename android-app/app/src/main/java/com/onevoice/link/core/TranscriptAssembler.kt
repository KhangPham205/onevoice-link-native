package com.onevoice.link.core

import java.util.Locale
import kotlin.math.abs

/**
 * One emitted ASR token and where, inside its window, the model emitted it.
 *
 * The offset is an emission-time approximation (CTC peak run start, RNN-T
 * encoder frame), not a word timestamp: transducers emit late (FastEmit). It
 * is only compared between two windows that decoded the same audio on the same
 * frame grid, which is what the merge below needs.
 */
internal data class AsrToken(val piece: String, val sampleOffset: Int)

/**
 * A whole word and the turn-absolute sample at which its first token was
 * emitted. [pauseBefore]: a silence of at least the pause-comma length lies
 * between the previous word and this one; [sentenceBefore]: one long enough
 * to end a sentence ([PauseCommas]; VI only).
 */
internal data class AsrWord(
    val text: String,
    val startSample: Int,
    val pauseBefore: Boolean = false,
    val sentenceBefore: Boolean = false,
)

private const val WORD_MARK = '▁'

/** SentencePiece pieces -> words; a piece starting with `▁` (or a space) opens a word. */
internal fun wordsFromTokens(tokens: List<AsrToken>, windowStart: Int): List<AsrWord> {
    val words = ArrayList<AsrWord>()
    var text = StringBuilder()
    var start = -1
    fun close() {
        val value = text.toString().trim()
        if (value.isNotEmpty()) words += AsrWord(value, start)
        text = StringBuilder()
        start = -1
    }
    for (token in tokens) {
        val opensWord = token.piece.startsWith(WORD_MARK) || token.piece.startsWith(' ')
        if (opensWord) close()
        if (start < 0) start = windowStart + token.sampleOffset
        text.append(token.piece.replace(WORD_MARK, ' ').trim())
    }
    close()
    return words
}

/**
 * The words as text, with a comma where [AsrWord.pauseBefore] marks a pause
 * and a semicolon where [AsrWord.sentenceBefore] marks a sentence-length one.
 * The semicolon is a boundary mark, not output: [VietnamesePunctuation] turns
 * it into "." or "?" (NpuPipeline turns it into "," when that is off). Not
 * right after the first word nor before the last: a pause there is a
 * hesitation ("em... em sẽ"), not the end of a clause.
 */
internal fun transcriptOf(words: List<AsrWord>): String = buildString {
    words.forEachIndexed { i, word ->
        if (i > 0) {
            if (word.pauseBefore && i >= 2 && i < words.lastIndex && last() !in ",.?!;:") {
                append(if (word.sentenceBefore) ';' else ',')
            }
            append(' ')
        }
        append(word.text)
    }
}

/**
 * Stitches per-window ASR words into one turn transcript.
 *
 * A pause cut has no overlap, so its words are concatenated. A forced cut
 * shares audio with the next window; each window keeps only the words whose
 * first token falls in its core (`[coreStart, coreEnd)`, split at the overlap
 * midpoint), the same idea as stride chunking for CTC. Words the previous
 * window decoded past its core stay pending until the next window decides
 * them, which is the unstable suffix of the transcript.
 *
 * The only reconciliation is local, at the one boundary word on each side:
 * a word both windows kept (duplicate) or both dropped (lost) is resolved only
 * when the text matches and the two start times are within [toleranceSamples].
 * There is no transcript-wide dedup, so genuine repeats ("có, có", "rất rất")
 * a syllable apart survive.
 */
internal class TranscriptAssembler(private val toleranceSamples: Int) {
    private val committed = ArrayList<AsrWord>()
    private var pendingTail: List<AsrWord> = emptyList()

    val committedWords: List<AsrWord> get() = committed
    val pendingWords: List<AsrWord> get() = pendingTail

    /** Accept one window's words; returns the words this call committed, in order. */
    fun accept(chunk: AudioChunk, words: List<AsrWord>): List<AsrWord> {
        // Left context (after either cut kind) and a forced cut's shared tail
        // are both outside the core; only a forced cut leaves a pending tail.
        val overlapBefore = chunk.coreStart > chunk.start
        val overlapAfter = chunk.coreEnd < chunk.end
        val head = if (overlapBefore) words.filter { it.startSample < chunk.coreStart } else emptyList()
        var kept = words.filter { word ->
            (!overlapBefore || word.startSample >= chunk.coreStart) &&
                (!overlapAfter || word.startSample < chunk.coreEnd)
        }
        val tail = if (overlapAfter) words.filter { it.startSample >= chunk.coreEnd } else emptyList()

        val fresh = ArrayList<AsrWord>(kept.size + 1)
        if (overlapBefore && chunk.previousCut == ChunkCut.FORCED) {
            val previousLast = committed.lastOrNull()
            val keptFirst = kept.firstOrNull()
            if (previousLast != null && keptFirst != null && sameWord(previousLast, keptFirst)) {
                // Both cores claimed one word whose start sits on the midpoint.
                kept = kept.drop(1)
            } else {
                val lostByPrevious = pendingTail.firstOrNull()
                val lostByThis = head.lastOrNull()
                if (lostByPrevious != null && lostByThis != null &&
                    sameWord(lostByPrevious, lostByThis) &&
                    (previousLast == null || !sameWord(previousLast, lostByThis))
                ) {
                    // Each window placed it just on the other side of the midpoint.
                    fresh += lostByThis
                }
            }
        } else if (pendingTail.isNotEmpty()) {
            // Not expected (only a forced cut leaves a tail), but never drop words.
            fresh += pendingTail
        }
        pendingTail = tail
        fresh += kept
        committed += fresh
        return fresh
    }

    /** End of turn: whatever is still pending is the best decode available. */
    fun flush(): List<AsrWord> {
        val flushed = pendingTail
        committed += flushed
        pendingTail = emptyList()
        return flushed
    }

    private fun sameWord(a: AsrWord, b: AsrWord): Boolean =
        abs(a.startSample - b.startSample) <= toleranceSamples &&
            normalizedWord(a.text) == normalizedWord(b.text)
}

/** Case and edge punctuation do not make two decodes of one word different. */
internal fun normalizedWord(text: String): String =
    text.lowercase(Locale.ROOT).trim { it.isWhitespace() || it in ".,?!;:\"'" }
