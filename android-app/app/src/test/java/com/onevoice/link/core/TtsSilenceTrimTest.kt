package com.onevoice.link.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TtsSilenceTrimTest {
    private val rate = 24_000

    private fun utterance(leadMs: Int, speechMs: Int, tailMs: Int): FloatArray =
        FloatArray(rate * leadMs / 1_000) +
            FloatArray(rate * speechMs / 1_000) { 0.2f } +
            FloatArray(rate * tailMs / 1_000)

    @Test
    fun paddingIsCutToTheMargins() {
        val trimmed = trimSilence(utterance(600, 1_000, 700), rate)

        // 60 ms lead + 1000 ms speech + 150 ms tail.
        assertEquals(rate * 1_210 / 1_000, trimmed.size)
        assertEquals(0f, trimmed.first())
        assertEquals(0.2f, trimmed[rate * 60 / 1_000])
    }

    @Test
    fun shortPaddingIsKeptWhole() {
        val input = utterance(30, 500, 100)
        assertSame(input, trimSilence(input, rate))
    }

    @Test
    fun silentRenderIsNeverDiscarded() {
        val input = FloatArray(rate)
        assertSame(input, trimSilence(input, rate))
    }

    @Test
    fun quietGapInsideSpeechSurvives() {
        val input = utterance(0, 300, 0) + FloatArray(rate / 2) + utterance(0, 300, 400)
        val trimmed = trimSilence(input, rate)
        assertEquals(rate * (300 + 500 + 300 + 150) / 1_000, trimmed.size)
    }
}
