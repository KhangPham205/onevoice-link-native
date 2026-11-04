package com.onevoice.link.core

/** Why a stretch of committed source text became its own translation unit. */
internal enum class UnitReason(val logValue: String) {
    /** Ends with sentence punctuation the ASR actually emitted (Parakeet EN). */
    SENTENCE("sentence"),
    /** Ends at a window cut placed in an acoustic pause (no punctuation available). */
    PAUSE("pause"),
    /** Split at a word boundary because the next word would exceed the Marian budget. */
    TOKEN_BUDGET("token_budget"),
    /** Whatever remained when the turn ended. */
    TURN_END("turn_end"),
}

/** Source text with enough meaning to translate on its own, plus its provenance. */
internal data class TranslationUnit(
    val index: Int,
    val text: String,
    /** Turn sample where the first and last word of the unit start. */
    val firstWordSample: Int,
    val lastWordSample: Int,
    val wordCount: Int,
    /** Largest Marian input of the unit (per sentence), prefix and EOS included. */
    val sourceTokens: Int,
    val reason: UnitReason,
)

/**
 * Turns committed ASR words into translation units.
 *
 * GIPFormer (VI) emits no punctuation and Parakeet (EN) does, so the policy is
 * per direction rather than pretending both have sentences:
 * - with punctuation, every complete sentence available after a window is
 *   released together, and an unfinished sentence waits for more words;
 * - without it, a window cut placed in a pause releases the text so far; a
 *   forced cut never does, since it was placed exactly where no pause was found.
 *
 * Every Marian input (one sentence, see [splitSentences]) must fit
 * [maxSourceTokens] as counted by the direction's real tokenizer. Text that
 * would not is split at the last word boundary that fits, never truncated.
 * The rest of a turn is flushed as one unit, so a short single-window turn
 * yields exactly one unit, as the legacy whole-utterance path did.
 */
internal class TranslationUnitBuilder(
    private val maxSourceTokens: Int,
    private val hasPunctuation: Boolean,
    /** Marian source tokens for one sentence, special prefix and EOS included. */
    private val tokenCount: (String) -> Int,
) {
    private val open = ArrayList<AsrWord>()
    private var nextIndex = 0

    val pendingWords: List<AsrWord> get() = open

    /** Feed the words one window committed; returns units ready for translation. */
    fun onCommitted(words: List<AsrWord>, windowCut: ChunkCut): List<TranslationUnit> {
        open += words
        val units = ArrayList<TranslationUnit>()
        if (windowCut == ChunkCut.TURN_END) {
            // The flush that follows makes the remainder one unit.
            return releaseOverBudget(units)
        }
        val releaseUpTo = when {
            hasPunctuation -> open.indexOfLast { endsSentence(it.text) } + 1
            windowCut == ChunkCut.PAUSE -> open.size
            else -> 0
        }
        if (releaseUpTo > 0) {
            val reason = if (hasPunctuation) UnitReason.SENTENCE else UnitReason.PAUSE
            units += budgeted(open.subList(0, releaseUpTo).toList(), reason)
            open.subList(0, releaseUpTo).clear()
        }
        return releaseOverBudget(units)
    }

    /** End of turn: everything left, as few units as the budget allows. */
    fun flush(): List<TranslationUnit> {
        if (open.isEmpty()) return emptyList()
        val units = budgeted(open.toList(), UnitReason.TURN_END)
        open.clear()
        return units
    }

    /**
     * Text with no boundary that already exceeds the budget cannot wait for
     * one: release its longest prefix that fits and keep the rest open.
     */
    private fun releaseOverBudget(units: MutableList<TranslationUnit>): List<TranslationUnit> {
        while (open.isNotEmpty() && longestSentenceTokens(open) > maxSourceTokens) {
            val count = fittingPrefix(open)
            units += unit(open.subList(0, count).toList(), UnitReason.TOKEN_BUDGET)
            open.subList(0, count).clear()
        }
        return units
    }

    private fun budgeted(words: List<AsrWord>, reason: UnitReason): List<TranslationUnit> {
        val units = ArrayList<TranslationUnit>()
        var rest = words
        while (rest.isNotEmpty() && longestSentenceTokens(rest) > maxSourceTokens) {
            val count = fittingPrefix(rest)
            units += unit(rest.subList(0, count), UnitReason.TOKEN_BUDGET)
            rest = rest.drop(count)
        }
        if (rest.isNotEmpty()) units += unit(rest, reason)
        return units
    }

    /**
     * Largest word prefix whose every sentence fits; prefers to end after a
     * sentence end or comma in the second half. At least one word, so a
     * pathological single word reaches the translator and fails loudly there.
     */
    private fun fittingPrefix(words: List<AsrWord>): Int {
        var low = 1
        var high = words.size
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (longestSentenceTokens(words.subList(0, middle)) <= maxSourceTokens) {
                low = middle
            } else {
                high = middle - 1
            }
        }
        val soft = (low - 1 downTo (low - 1) / 2)
            .firstOrNull { index -> endsClause(words[index].text) }
        return (soft?.plus(1)) ?: low
    }

    private fun longestSentenceTokens(words: List<AsrWord>): Int =
        splitSentences(transcriptOf(words)).maxOfOrNull(tokenCount) ?: 0

    private fun unit(words: List<AsrWord>, reason: UnitReason): TranslationUnit =
        TranslationUnit(
            index = nextIndex++,
            text = transcriptOf(words),
            firstWordSample = words.first().startSample,
            lastWordSample = words.last().startSample,
            wordCount = words.size,
            sourceTokens = longestSentenceTokens(words),
            reason = reason,
        )

    private fun endsSentence(text: String): Boolean = text.lastOrNull()?.let { it in ".?!" } == true

    private fun endsClause(text: String): Boolean = text.lastOrNull()?.let { it in ".?!,;:" } == true
}
