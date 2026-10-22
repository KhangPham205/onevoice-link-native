package com.onevoice.link.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechActivityDetectorTest {
    private val frame = ShortArray(320)

    @Test
    fun twentyMsFramesAreReblockedIntoFullWindows() {
        val windowSizes = mutableListOf<Int>()
        val gate = WindowedSpeechGate(512, 0.5f, 0.35f) { window ->
            windowSizes += window.size
            0f
        }

        // 8 x 320 = 2560 samples = exactly 5 Silero windows.
        repeat(8) { gate.accept(frame, frame.size) }

        assertEquals(listOf(512, 512, 512, 512, 512), windowSizes)
    }

    @Test
    fun hysteresisKeepsSoftEndingsInsideTheTurn() {
        val probabilities = ArrayDeque(listOf(0.4f, 0.6f, 0.4f, 0.3f))
        val gate = WindowedSpeechGate(320, 0.5f, 0.35f) { probabilities.removeFirst() }

        assertFalse("0.4 is below onset", gate.accept(frame, frame.size))
        assertTrue("0.6 opens", gate.accept(frame, frame.size))
        assertTrue("0.4 stays above offset", gate.accept(frame, frame.size))
        assertFalse("0.3 closes", gate.accept(frame, frame.size))
    }

    @Test
    fun decisionHoldsUntilTheNextWindowCompletes() {
        val gate = WindowedSpeechGate(512, 0.5f, 0.35f) { 0.9f }

        assertFalse("no complete window yet", gate.accept(frame, frame.size))
        assertTrue("first window completes", gate.accept(frame, frame.size))
        assertTrue(gate.accept(frame, 100))
    }

    @Test
    fun resetDropsPartialWindowAndSpeechState() {
        val gate = WindowedSpeechGate(320, 0.5f, 0.35f) { 0.9f }
        assertTrue(gate.accept(frame, frame.size))

        gate.reset()

        assertFalse(gate.accept(frame, 100))
        assertEquals(0f, gate.lastProbability)
    }

    @Test
    fun energyDetectorMatchesTheLegacyRmsGate() {
        val detector = EnergySpeechDetector(rmsThreshold = 0.008)
        val quiet = ShortArray(320) { 200 } // rms 0.0061
        val loud = ShortArray(320) { 400 } // rms 0.0122

        assertFalse(detector.isSpeech(quiet, quiet.size))
        assertTrue(detector.isSpeech(loud, loud.size))
    }
}
