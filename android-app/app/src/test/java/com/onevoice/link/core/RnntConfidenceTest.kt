package com.onevoice.link.core

import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.ln
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RnntConfidenceTest {
    @Test
    fun argmaxLogProbabilityUsesStableLogSumExp() {
        val result = argmaxLogProbability(
            FloatBuffer.wrap(floatArrayOf(1_000f, 999f, -1_000f)),
            vocabSize = 3,
        )

        assertEquals(0, result.id)
        assertEquals(-ln(1.0 + exp(-1.0)), result.logProbability, 1.0e-12)
    }

    @Test
    fun argmaxLogProbabilityHonorsBufferPositionAndIgnoresNonFiniteTail() {
        val logits = FloatBuffer.wrap(
            floatArrayOf(77f, Float.NaN, -2f, 2f, Float.POSITIVE_INFINITY),
        ).apply { position(1) }

        val result = argmaxLogProbability(logits, vocabSize = 3)

        assertEquals(2, result.id)
        assertEquals(-ln(1.0 + exp(-4.0)), result.logProbability, 1.0e-12)
    }

    @Test
    fun argmaxLogProbabilityRejectsOutputWithoutFiniteLogit() {
        assertThrows(IllegalStateException::class.java) {
            argmaxLogProbability(
                FloatBuffer.wrap(floatArrayOf(Float.NaN, Float.NEGATIVE_INFINITY)),
                vocabSize = 2,
            )
        }
    }
}
