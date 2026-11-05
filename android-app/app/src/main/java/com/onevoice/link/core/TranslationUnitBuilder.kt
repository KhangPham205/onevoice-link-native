package com.onevoice.link.core

/** Why a stretch of committed source text became its own translation unit. */
internal enum class UnitReason(val logValue: String) {
    /** Ends with sentence punctuation the ASR actually emitted (Parakeet EN). */
    SENTENCE("sentence"),
    /** Ends at a window cut placed in an acoustic pause (no punctuation available). */
    PAUSE("pause"),
    /** No pause cut came; split at the widest gap between word onsets (no punctuation available). */
    WORD_GAP("word_gap"),
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
    /** For [UnitReason.WORD_GAP]: onset-to-onset gap at the split, in samples. */
    val boundaryGapSamples: Int? = null,
)

/**
 * Turns committed ASR words into translation units.
 *
 * GIPFormer (VI) emits no punctuation and Parakeet (EN) does, so the policy is
 * per direction rather than pretending both have sentences:
 * - with punctuation, every complete sentence available after a window is
 *   released together, and an unfinished sentence waits for more words;
 * - without it, a window cut placed in a pause releases the text so far. A
 *   forced cut was placed where no pause was found, so it releases nothing by
 *   itself; but real continuous speech rarely gives Silero a pause at all
 *   (12 real-voice VI recordings: 0-1 pause cuts per turn), and waiting for
 *   the token budget made ~25 s units whose speech was still being rendered
 *   when the turn ended. So once the open text exceeds [firstUnitTokens] (the
 *   first unit of a turn) or [unitTokens], a unit is released at the widest
 *   gap between word onsets among the boundaries that give it between half
 *   and all of that size. Onsets are emission times, so the gap is a pause
 *   proxy, not a measured silence; it only chooses among word boundaries.
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
    /** Without punctuation: release the first unit at a word gap once the text exceeds this; null waits. */
    private val firstUnitTokens: Int? = null,
    /** Without punctuation: the same for every later unit. */
    private val unitTokens: Int? = null,
) {
    init {
        require(firstUnitTokens == null || firstUnitTokens >= 2)
        require(unitTokens == null || unitTokens >= 2)
    }

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
        if (!hasPunctuation) releaseAtWordGaps(units)
        return releaseOverBudget(units)
    }

    /** Release units at word gaps while the open text is longer than the current target. */
    private fun releaseAtWordGaps(units: MutableList<TranslationUnit>) {
        while (open.size >= 2) {
            val target = (if (nextIndex == 0) firstUnitTokens else unitTokens) ?: return
            if (longestSentenceTokens(open) <= target) return
            // Prefix lengths are monotone in tokens; both ends are found by bisection.
            val longest = largestPrefixWithin(open, target).coerceAtMost(open.size - 1)
            val shortest = smallestPrefixReaching(open, (target + 1) / 2).coerceIn(1, longest)
            var best = longest
            var bestGap = -1
            for (count in shortest..longest) {
                val gap = open[count].startSample - open[count - 1].startSample
                if (gap >= bestGap) {
                    best = count
                    bestGap = gap
                }
            }
            units += unit(open.subList(0, best).toList(), UnitReason.WORD_GAP, bestGap)
            open.subList(0, best).clear()
        }
    }

    private fun largestPrefixWithin(words: List<AsrWord>, tokens: Int): Int {
        var low = 1
        var high = words.size
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (longestSentenceTokens(words.subList(0, middle)) <= tokens) low = middle else high = middle - 1
        }
        return low
    }

    private fun smallestPrefixReaching(words: List<AsrWord>, tokens: Int): Int {
        var low = 1
        var high = words.size
        while (low < high) {
            val middle = (low + high) / 2
            if (longestSentenceTokens(words.subList(0, middle)) >= tokens) high = middle else low = middle + 1
        }
        return low
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
        val low = largestPrefixWithin(words, maxSourceTokens)
        val soft = (low - 1 downTo (low - 1) / 2)
            .firstOrNull { index -> endsClause(words[index].text) }
        return (soft?.plus(1)) ?: low
    }

    private fun longestSentenceTokens(words: List<AsrWord>): Int =
        splitSentences(transcriptOf(words)).maxOfOrNull(tokenCount) ?: 0

    private fun unit(words: List<AsrWord>, reason: UnitReason, gapSamples: Int? = null): TranslationUnit =
        TranslationUnit(
            index = nextIndex++,
            text = transcriptOf(words),
            firstWordSample = words.first().startSample,
            lastWordSample = words.last().startSample,
            wordCount = words.size,
            sourceTokens = longestSentenceTokens(words),
            reason = reason,
            boundaryGapSamples = gapSamples,
        )

    private fun endsSentence(text: String): Boolean = text.lastOrNull()?.let { it in ".?!" } == true

    private fun endsClause(text: String): Boolean = text.lastOrNull()?.let { it in ".?!,;:" } == true
}
