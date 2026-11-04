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
}
