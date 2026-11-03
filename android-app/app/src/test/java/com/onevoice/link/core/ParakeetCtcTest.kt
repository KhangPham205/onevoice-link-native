package com.onevoice.link.core

import java.nio.ShortBuffer
import org.junit.Assert.assertEquals
import org.junit.Test

class ParakeetCtcTest {
    // Values measured from the shipped SM8550 binary's I/O contract.
    private val input = Q16Params(scale = 0.00012464652536436915f, offset = -31906)
    private val output = Q16Params(scale = 0.0006816668901592493f, offset = -65535)

    @Test
    fun inputQuantizationRoundTripsWithinHalfAStep() {
        for (value in listOf(-3.9f, -1.0f, 0f, 0.37f, 2.5f, 4.1f)) {
            val restored = input.dequantize(input.quantize(value).toShort())
            assertEquals(value, restored, input.scale / 2 + 1e-6f)
        }
    }

    @Test
    fun inputQuantizationSaturatesOutsideTheCalibratedRange() {
        assertEquals(0, input.quantize(-100f))
        assertEquals(Q16Params.U16_MAX, input.quantize(100f))
    }

    @Test
    fun outputTopCodeIsLogProbabilityZero() {
        assertEquals(0f, output.dequantize(0xFFFF.toShort()), 0f)
        assertEquals(-44.67f, output.dequantize(0), 0.01f)
    }

    @Test
    fun greedyCollapsesRepeatsDropsBlankAndScoresRunPeak() {
        // vocab: 0=<unk>, 1="▁hi", 2="▁there", 3=blank
        val frames = listOf(
            frame(best = 1, raw = 65_000),
            frame(best = 1, raw = 65_500), // same run: keeps the higher peak
            frame(best = 3, raw = 65_535),
            frame(best = 1, raw = 64_000), // after blank: a new emission
            frame(best = 0, raw = 65_535), // <unk>: skipped but breaks the run
            frame(best = 2, raw = 65_200),
        )
        val buffer = ShortBuffer.wrap(frames.flatten().toShortArray())

        val result = ctcGreedyDecodeQ16(
            buffer,
            frames = frames.size,
            vocabSize = 4,
            blankId = 3,
            params = output,
            skippedIds = setOf(0),
        )

        assertEquals(listOf(1, 1, 2), result.ids)
        val expected = listOf(65_500, 64_000, 65_200)
            .sumOf { output.dequantize(it.toShort()).toDouble() }
        assertEquals(expected, result.rawLogProb, 1e-6)
    }

    @Test
    fun onlyValidFramesAreDecoded() {
        val buffer = ShortBuffer.wrap(
            (frame(best = 1, raw = 65_000) + frame(best = 2, raw = 65_000)).toShortArray(),
        )

        val result = ctcGreedyDecodeQ16(buffer, 1, 4, 3, output)

        assertEquals(listOf(1), result.ids)
    }

    @Test
    fun sentencePieceWordBoundariesBecomeSpaces() {
        val pieces = listOf("<unk>", "▁he", "llo", "▁world", ",")
        assertEquals("hello world,", detokenizeSentencePiece(listOf(1, 2, 3, 4), pieces))
    }

    /** One frame of a 4-symbol vocabulary where [best] holds [raw]. */
    private fun frame(best: Int, raw: Int): List<Short> =
        List(4) { id -> (if (id == best) raw else 10).toShort() }
}
