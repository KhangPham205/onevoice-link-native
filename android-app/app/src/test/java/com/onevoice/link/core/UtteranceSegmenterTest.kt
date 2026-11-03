package com.onevoice.link.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UtteranceSegmenterTest {
    private val frameSamples = 320
    private val guardFrames = 15 // 300 ms
    private val silence = ShortArray(frameSamples)
    private val guardSpeech = ShortArray(frameSamples) { 6_000 }
    private val speech = ShortArray(frameSamples) { 8_000 }

    /** Labels a frame as speech when it is non-zero; records how it was fed. */
    private class FakeDetector : SpeechActivityDetector {
        override val name = "fake"
        override val lastScore = 0f
        var resets = 0
        var observedFrames = 0
        var decidedFrames = 0

        override fun isSpeech(frame: ShortArray, count: Int): Boolean {
            decidedFrames++
            return frame[0] != 0.toShort()
        }

        override fun observe(frame: ShortArray, count: Int) {
            observedFrames++
        }

        override fun reset() {
            resets++
        }
    }

    private fun UtteranceSegmenter.feed(frames: List<ShortArray>): BoundedUtterance? {
        for (frame in frames) accept(frame, frame.size)?.let { return it }
        return null
    }

    @Test
    fun speechStartingInsideTheGuardKeepsItsHead() {
        val segmenter = UtteranceSegmenter(FakeDetector(), guardSamples = guardFrames * frameSamples)
        // The user answers 100 ms before the guard ends and keeps talking.
        val frames = List(10) { silence } + List(5) { guardSpeech } +
            List(20) { speech } + List(60) { silence }

        val utterance = requireNotNull(segmenter.feed(frames)) { "no utterance was bounded" }

        val guardValue = 6_000 / 32_768f
        val headSamples = utterance.samples.count { it == guardValue }
        assertEquals("all five guarded speech frames survive", 5 * frameSamples, headSamples)
        assertFalse(utterance.forcedBoundary)
    }

    @Test
    fun guardAudioCannotOpenATurn() {
        val detector = FakeDetector()
        val segmenter = UtteranceSegmenter(detector, guardSamples = guardFrames * frameSamples)
        // Loudspeaker tail fills the guard, then the room goes quiet.
        val frames = List(guardFrames) { speech } + List(80) { silence }

        assertNull(segmenter.feed(frames))
        assertEquals("guard frames only warm the detector", guardFrames, detector.observedFrames)
        assertEquals(80, detector.decidedFrames)
    }

    @Test
    fun detectorStateIsResetBeforeTheGuardNotAfterIt() {
        val detector = FakeDetector()
        val segmenter = UtteranceSegmenter(detector, guardSamples = guardFrames * frameSamples)
        assertEquals(1, detector.resets)

        segmenter.feed(List(guardFrames + 5) { silence })

        assertEquals("warm state must survive into live capture", 1, detector.resets)
    }

    @Test
    fun guardingReportsTheGuardWindow() {
        val segmenter = UtteranceSegmenter(FakeDetector(), guardSamples = 2 * frameSamples)
        assertTrue(segmenter.guarding)
        segmenter.accept(silence, frameSamples)
        assertTrue(segmenter.guarding)
        segmenter.accept(silence, frameSamples)
        assertFalse(segmenter.guarding)
    }

    @Test
    fun hangoverEndsTheTurnAndTrimsTrailingSilence() {
        val segmenter = UtteranceSegmenter(FakeDetector())
        val frames = List(20) { speech } + List(60) { silence }

        val utterance = requireNotNull(segmenter.feed(frames)) { "no utterance was bounded" }

        // The onset frame plus up to 14 pre-roll frames (all silence here) lead in.
        val speechSamples = utterance.samples.count { it != 0f }
        assertEquals(20 * frameSamples, speechSamples)
        assertTrue("trailing silence is trimmed", utterance.samples.last() != 0f)
    }

    @Test
    fun ceilingForcesABoundary() {
        val segmenter = UtteranceSegmenter(FakeDetector())

        val utterance = requireNotNull(segmenter.feed(List(600) { speech })) { "no utterance was bounded" }

        assertTrue(utterance.forcedBoundary)
        assertEquals(NpuEngine.MAX_SEGMENT_SAMPLES, utterance.samples.size)
    }

    @Test
    fun blipShorterThanMinimumSpeechIsDropped() {
        val segmenter = UtteranceSegmenter(FakeDetector())
        val frames = List(NpuEngine.MIN_SPEECH_FRAMES - 1) { speech } + List(60) { silence }

        assertNull(segmenter.feed(frames))
    }

    @Test
    fun quietSegmentBelowTheSpeechFloorIsDiscarded() {
        val segmenter = UtteranceSegmenter(FakeDetector())
        val faint = ShortArray(frameSamples) { 100 } // rms 0.003, labelled speech by the fake
        val frames = List(20) { faint } + List(60) { silence }

        assertNull(segmenter.feed(frames))
    }
}
