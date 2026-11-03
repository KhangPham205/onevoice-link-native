package com.onevoice.link.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class AudioResamplerTest {
    private fun tone(hz: Double, rate: Int, samples: Int, amplitude: Double = 0.5) =
        FloatArray(samples) { (amplitude * sin(2 * PI * hz * it / rate)).toFloat() }

    /** Skip the edges, where zero padding legitimately bends the waveform. */
    private fun FloatArray.middle(): FloatArray = copyOfRange(size / 4, size * 3 / 4)

    @Test
    fun equalRatesReturnACopy() {
        val input = floatArrayOf(0.1f, -0.2f, 0.3f)
        val output = resamplePcm(input, 24_000, 24_000)
        assertArrayEquals(input, output, 0f)
        assertTrue(output !== input)
    }

    @Test
    fun outputLengthFollowsTheRateRatio() {
        assertEquals(24_000, resamplePcm(FloatArray(44_100), 44_100, 24_000).size)
        assertEquals(12_000, resamplePcm(FloatArray(22_050), 44_100, 24_000).size)
    }

    @Test
    fun speechBandToneSurvivesSupertonicToPlaybackRate() {
        val output = resamplePcm(tone(1_000.0, 44_100, 44_100), 44_100, 24_000)
        val expected = tone(1_000.0, 24_000, output.size)

        val error = output.middle().zip(expected.middle()).maxOf { (a, b) -> abs(a - b) }
        assertTrue("max error $error", error < 2e-3f)
    }

    @Test
    fun toneAboveTheOutputNyquistIsRemovedNotAliased() {
        // 16 kHz would fold to 8 kHz at 24 kHz without the anti-alias filter.
        val output = resamplePcm(tone(16_000.0, 44_100, 44_100), 44_100, 24_000).middle()

        val rms = sqrt(output.sumOf { it.toDouble() * it } / output.size)
        assertTrue("residual rms $rms", rms < 0.005)
    }

    @Test
    fun dcGainIsUnity() {
        val output = resamplePcm(FloatArray(4_410) { 0.25f }, 44_100, 24_000).middle()
        output.forEach { assertEquals(0.25f, it, 1e-4f) }
    }
}
