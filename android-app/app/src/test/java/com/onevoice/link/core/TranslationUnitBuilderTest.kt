package com.onevoice.link.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationUnitBuilderTest {
    /** One token per word plus a prefix and EOS, like a Marian input. */
    private val countTokens: (String) -> Int = { text -> text.split(' ').count(String::isNotBlank) + 2 }

    private fun words(text: String, fromMs: Int = 0): List<AsrWord> =
        text.split(' ').mapIndexed { index, word -> AsrWord(word, LongTurnConfig.ms(fromMs + index * 300)) }

    @Test
    fun englishReleasesCompleteSentencesAndKeepsTheUnfinishedOne() {
        val builder = TranslationUnitBuilder(96, hasPunctuation = true, tokenCount = countTokens)
        val units = builder.onCommitted(words("I agree. We ship on Monday. If all"), ChunkCut.PAUSE)

        assertEquals(listOf("I agree. We ship on Monday."), units.map { it.text })
        assertEquals(UnitReason.SENTENCE, units.single().reason)
        assertEquals("If all", transcriptOf(builder.pendingWords))
        val rest = builder.onCommitted(words("conditions are met.", 3_000), ChunkCut.TURN_END) + builder.flush()
        assertEquals(listOf("If all conditions are met."), rest.map { it.text })
    }

    @Test
    fun vietnameseWaitsForAPauseCutNotAForcedOne() {
        val builder = TranslationUnitBuilder(96, hasPunctuation = false, tokenCount = countTokens)

        assertTrue(builder.onCommitted(words("tôi chỉ đồng ý triển khai"), ChunkCut.FORCED).isEmpty())
        val units = builder.onCommitted(words("phương án này", 2_000), ChunkCut.PAUSE)

        assertEquals(listOf("tôi chỉ đồng ý triển khai phương án này"), units.map { it.text })
        assertEquals(UnitReason.PAUSE, units.single().reason)
    }

    @Test
    fun aSingleWindowTurnIsExactlyOneUnit() {
        val builder = TranslationUnitBuilder(96, hasPunctuation = true, tokenCount = countTokens)
        val units = builder.onCommitted(words("Hello there. How are you?"), ChunkCut.TURN_END) + builder.flush()

        assertEquals(listOf("Hello there. How are you?"), units.map { it.text })
        assertEquals(UnitReason.TURN_END, units.single().reason)
    }

    @Test
    fun overBudgetTextIsSplitAtWordBoundariesWithoutLoss() {
        val builder = TranslationUnitBuilder(10, hasPunctuation = false, tokenCount = countTokens)
        val source = (1..30).joinToString(" ") { "w$it" }
        val units = builder.onCommitted(words(source), ChunkCut.FORCED) + builder.flush()

        assertTrue(units.size >= 4)
        assertTrue(units.all { it.sourceTokens <= 10 })
        assertEquals("no word lost or doubled", source, units.joinToString(" ") { it.text })
        assertTrue(units.dropLast(1).all { it.reason == UnitReason.TOKEN_BUDGET })
        assertEquals((0 until units.size).toList(), units.map { it.index })
    }

    @Test
    fun budgetSplitPrefersAClauseEnd() {
        val builder = TranslationUnitBuilder(10, hasPunctuation = true, tokenCount = countTokens)
        val units = builder.onCommitted(words("one two three four, five six seven eight nine ten"), ChunkCut.FORCED)

        assertEquals("one two three four,", units.first().text)
    }

    @Test
    fun budgetIsPerSentenceBecauseMarianSeesOneSentenceAtATime() {
        val builder = TranslationUnitBuilder(8, hasPunctuation = true, tokenCount = countTokens)
        // Each sentence is 6 tokens; the pair is 12 but never one Marian input.
        val units = builder.onCommitted(words("a b c d. e f g h."), ChunkCut.TURN_END) + builder.flush()

        assertEquals(listOf("a b c d. e f g h."), units.map { it.text })
    }

    /** Words 300 ms apart, except a longer onset gap after the given word indexes. */
    private fun timedWords(count: Int, pausesAfter: Map<Int, Int>, fromMs: Int = 0): List<AsrWord> {
        var at = fromMs
        return (0 until count).map { index ->
            AsrWord("w$index", LongTurnConfig.ms(at)).also { at += 300 + (pausesAfter[index] ?: 0) }
        }
    }

    private fun gapBuilder() = TranslationUnitBuilder(
        96, hasPunctuation = false, tokenCount = countTokens, firstUnitTokens = 12, unitTokens = 20,
    )

    @Test
    fun vietnameseForcedCutBelowTheTargetStillWaits() {
        val builder = gapBuilder()
        // 10 words + 2 specials = 12 tokens: not over the first-unit target.
        assertTrue(builder.onCommitted(timedWords(10, emptyMap()), ChunkCut.FORCED).isEmpty())
    }

    @Test
    fun vietnameseFirstUnitIsCutAtTheWidestWordGapInItsRange() {
        val builder = gapBuilder()
        // First target 12 tokens: candidates end after 4..10 words. The 400 ms
        // gap after w6 wins over the 200 ms one after w8; the gap after w13 is out of range.
        val words = timedWords(30, mapOf(6 to 400, 8 to 200, 13 to 900))
        val units = builder.onCommitted(words, ChunkCut.FORCED)

        val first = units.first()
        assertEquals(UnitReason.WORD_GAP, first.reason)
        assertEquals((0..6).joinToString(" ") { "w$it" }, first.text)
        assertEquals(LongTurnConfig.ms(700), first.boundaryGapSamples)
        // Later units use the larger target: what is left open fits in it.
        assertTrue(units.drop(1).all { it.sourceTokens <= 20 })
        assertTrue(countTokens(transcriptOf(builder.pendingWords)) <= 20)
    }

    @Test
    fun vietnameseWordGapSplitsLoseAndDoubleNothing() {
        val builder = gapBuilder()
        val all = timedWords(90, mapOf(5 to 250, 17 to 500, 31 to 120, 44 to 600, 60 to 300, 77 to 350))
        val units = ArrayList<TranslationUnit>()
        all.chunked(23).forEachIndexed { index, window ->
            units += builder.onCommitted(window, if (index == 3) ChunkCut.TURN_END else ChunkCut.FORCED)
        }
        units += builder.flush()

        assertEquals(transcriptOf(all), units.joinToString(" ") { it.text })
        assertEquals((0 until units.size).toList(), units.map { it.index })
        assertTrue(units.first().sourceTokens <= 12)
        assertTrue(units.dropLast(1).all { it.sourceTokens in 6..20 && it.reason == UnitReason.WORD_GAP })
    }

    @Test
    fun englishIgnoresWordGapTargetsAndKeepsSentences() {
        val builder = TranslationUnitBuilder(
            96, hasPunctuation = true, tokenCount = countTokens, firstUnitTokens = 4, unitTokens = 4,
        )
        val units = builder.onCommitted(words("We will ship it on Monday if all tests pass"), ChunkCut.FORCED)
        assertTrue(units.isEmpty())
    }

    @Test
    fun aShortVietnameseTurnIsStillOneUnit() {
        val builder = gapBuilder()
        val units = builder.onCommitted(timedWords(30, mapOf(6 to 400)), ChunkCut.TURN_END) + builder.flush()
        assertEquals(1, units.size)
        assertEquals(UnitReason.TURN_END, units.single().reason)
    }
}
