package com.onevoice.link.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TurnSegmenterTest {
    private val frame = NpuEngine.FRAME_SAMPLES
    private val config = LongTurnConfig(mode = LongTurnMode.ADAPTIVE)

    /** Speech iff the read's first sample is non-zero; counts resets. */
    private class FakeDetector : SpeechActivityDetector {
        override val name = "fake"
        override val lastScore = 0f
        var resets = 0
        override fun isSpeech(frame: ShortArray, count: Int) = frame[0] != 0.toShort()
        override fun reset() {
            resets++
        }
    }

    /** Index-marked speech: every sample is unique and loud enough for the floor. */
    private fun speech(samples: Int, from: Int) = ShortArray(samples) { (1_000 + (from + it) % 20_000).toShort() }

    private class Stream {
        private val parts = ArrayList<ShortArray>()
        var size = 0
            private set
        fun add(part: ShortArray) = apply {
            parts += part
            size += part.size
        }
        fun silence(ms: Int) = add(ShortArray(ms * 16))
        fun toArray(): ShortArray {
            val out = ShortArray(size)
            var at = 0
            parts.forEach { it.copyInto(out, at); at += it.size }
            return out
        }
    }

    private fun Stream.speechMs(ms: Int) = add(speech(ms * 16, size))

    private class Run(val events: List<SegmenterEvent>, val archive: TurnArchive, val detector: FakeDetector) {
        val chunks get() = events.filterIsInstance<SegmenterEvent.Chunk>().map { it.chunk }
        val ends get() = events.filterIsInstance<SegmenterEvent.TurnEnded>()
    }

    private fun run(
        stream: ShortArray,
        cfg: LongTurnConfig = config,
        readSizes: (Int) -> Int = { frame },
    ): Run {
        val detector = FakeDetector()
        val archive = TurnArchive(cfg.archiveCapacitySamples)
        val segmenter = TurnSegmenter(detector, archive, cfg)
        val events = ArrayList<SegmenterEvent>()
        var offset = 0
        var read = 0
        val buffer = ShortArray(frame)
        while (offset < stream.size) {
            val count = minOf(readSizes(read++), stream.size - offset, frame)
            stream.copyInto(buffer, 0, offset, offset + count)
            offset += count
            events += segmenter.accept(buffer, count)
        }
        return Run(events, archive, detector)
    }

    /** Cores tile the turn; windows respect the model contract and hold their core. */
    private fun assertCoverage(run: Run) {
        val chunks = run.chunks
        assertTrue("at least one window", chunks.isNotEmpty())
        assertEquals(0, chunks.first().coreStart)
        chunks.zipWithNext().forEach { (previous, next) ->
            assertEquals("cores are contiguous", previous.coreEnd, next.coreStart)
            assertEquals(previous.cut, next.previousCut)
            when (previous.cut) {
                ChunkCut.PAUSE -> assertEquals("no overlap after a pause cut", previous.end, next.start)
                ChunkCut.FORCED -> {
                    assertEquals(previous.end - config.overlapSamples, next.start)
                    assertEquals(
                        "window grids stay aligned",
                        0,
                        (next.start - previous.start) % LongTurnConfig.OVERLAP_GRID_SAMPLES,
                    )
                }
                ChunkCut.TURN_END -> throw AssertionError("TURN_END must be last")
            }
        }
        chunks.forEach { chunk ->
            assertTrue("window ${chunk.index} within contract", chunk.length in 1..NpuEngine.MAX_SEGMENT_SAMPLES)
            assertTrue(chunk.start <= chunk.coreStart && chunk.coreStart < chunk.coreEnd)
            assertTrue(chunk.coreEnd <= chunk.end)
        }
        // A pause cut followed only by silence needs no final window.
        assertTrue(chunks.last().cut == ChunkCut.TURN_END || chunks.last().cut == ChunkCut.PAUSE)
        assertEquals("exactly one terminal event", 1, run.ends.size)
        assertTrue("terminal event is last", run.events.last() is SegmenterEvent.TurnEnded)
    }

    /**
     * The owned cores reach past the last speech sample. They may end a little
     * later: a pause cut can fall inside the trailing silence itself.
     */
    private fun assertSpeechCovered(run: Run, lastSpeechSample: Int, detectorSlack: Int = 0) {
        val detected = run.ends.single().lastSpeechSample
        assertTrue("last speech $detected vs $lastSpeechSample", detected - lastSpeechSample in 0..detectorSlack)
        val coverageEnd = run.chunks.last().coreEnd
        assertTrue("cores end at $coverageEnd, speech at $detected", coverageEnd >= detected)
        assertTrue(coverageEnd - detected <= config.microPauseSamples)
    }

    /** Every archived sample equals the input, in order: nothing dropped or doubled. */
    private fun assertArchiveIsInput(run: Run, stream: ShortArray) {
        val first = run.archive.sample(0)
        val base = stream.indexOfFirst { it == first }
        for (index in 0 until run.archive.size) {
            assertEquals("archive[$index]", stream[base + index], run.archive.sample(index))
        }
    }

    @Test
    fun shortTurnIsOneWindowIdenticalToTheLegacyUtterance() {
        val stream = Stream().silence(200).speechMs(3_000).silence(1_200).toArray()
        val run = run(stream)

        assertEquals(1, run.chunks.size)
        assertEquals(TurnEndReason.SPEAKER_DONE, run.ends.single().reason)
        val legacy = UtteranceSegmenter(FakeDetector())
        var utterance: BoundedUtterance? = null
        stream.toList().chunked(frame).forEach { part ->
            val buffer = part.toShortArray()
            utterance = utterance ?: legacy.accept(buffer, buffer.size)
        }
        val chunk = run.chunks.single()
        assertArrayEquals(
            "same samples as the legacy whole-utterance path",
            checkNotNull(utterance).samples,
            run.archive.window(chunk.start, chunk.end),
            0f,
        )
    }

    @Test
    fun continuousSpeechIsCutAtTheCeilingWithAlignedOverlap() {
        val stream = Stream().silence(100).speechMs(35_000).silence(1_200).toArray()
        val run = run(stream)

        assertCoverage(run)
        assertTrue(run.chunks.dropLast(1).all { it.cut == ChunkCut.FORCED })
        assertArchiveIsInput(run, stream)
        // Pre-roll holds the 100 ms of leading silence, then every speech sample.
        assertSpeechCovered(run, 100 * 16 + 35_000 * 16)
    }

    @Test
    fun pausesSplitWindowsWithoutOverlap() {
        val stream = Stream().silence(100).apply {
            repeat(15) { speechMs(1_800); silence(200) }
        }.silence(1_200).toArray()
        val run = run(stream)

        assertCoverage(run)
        val inner = run.chunks.dropLast(1)
        assertTrue("several pause cuts", inner.size >= 3)
        assertTrue(inner.all { it.cut == ChunkCut.PAUSE })
        assertTrue(inner.all { it.length >= config.softSplitSamples })
    }

    @Test
    fun softSplitFollowedBySilenceEndsTheTurnWithoutAnEmptyWindow() {
        val stream = Stream().silence(100).speechMs(7_500).silence(1_500).toArray()
        val run = run(stream)

        assertCoverage(run)
        assertEquals("the pause cut window is the only window", listOf(ChunkCut.PAUSE), run.chunks.map { it.cut })
        assertEquals(TurnEndReason.SPEAKER_DONE, run.ends.single().reason)
    }

    @Test
    fun turnCapEndsOnceAndKeepsEveryArchivedSample() {
        val capped = config.copy(maxTurnSamples = LongTurnConfig.ms(20_000))
        val stream = Stream().silence(100).speechMs(25_000).silence(1_200).toArray()
        val run = run(stream, capped)

        assertCoverage(run)
        val end = run.ends.single()
        assertEquals(TurnEndReason.TURN_CAP, end.reason)
        assertEquals(run.archive.size, run.chunks.last().coreEnd)
        assertTrue(run.archive.size >= capped.maxTurnSamples)
        assertArchiveIsInput(run, stream)
    }

    @Test
    fun partialReadsKeepCoverageAndCountSilenceInSamples() {
        val stream = Stream().silence(100).speechMs(24_000).silence(1_200).toArray()
        val random = Random(7)
        val run = run(stream) { 1 + random.nextInt(frame) }

        assertCoverage(run)
        assertArchiveIsInput(run, stream)
        // The fake labels a whole read by its first sample, so the read that
        // straddles the speech end counts as speech: at most one read of slack.
        assertSpeechCovered(run, 100 * 16 + 24_000 * 16, detectorSlack = frame - 1)
    }

    @Test
    fun detectorIsResetOncePerTurnNotPerWindow() {
        val stream = Stream().silence(100).speechMs(30_000).silence(1_200).toArray()
        val run = run(stream)

        assertTrue(run.chunks.size > 2)
        assertEquals(1, run.detector.resets)
    }

    @Test
    fun quietFirstWindowIsDiscardedAndListeningContinues() {
        val quiet = ShortArray(2_000 * 16) { 100 }
        val stream = Stream().silence(100).add(quiet).silence(1_200).speechMs(2_000).silence(1_200).toArray()
        val run = run(stream)

        assertEquals(1, run.chunks.size)
        assertEquals(1, run.ends.size)
        val window = run.archive.window(run.chunks.single().start, run.chunks.single().end)
        assertTrue("the accepted turn is the loud one", window.none { it == 100 / 32_768f })
    }

    @Test
    fun fixedPolicyCutsOnlyAtTheCeiling() {
        val fixed = config.copy(splitPolicy = SplitPolicy.FIXED)
        val stream = Stream().silence(100).apply {
            repeat(12) { speechMs(1_800); silence(200) }
        }.silence(1_200).toArray()
        val run = run(stream, fixed)

        val inner = run.chunks.dropLast(1)
        assertTrue(inner.isNotEmpty())
        assertTrue(inner.all { it.cut == ChunkCut.FORCED && it.length == NpuEngine.MAX_SEGMENT_SAMPLES })
    }

    @Test
    fun forcedCutPrefersAShortGapInsideTheWindow() {
        // One 60 ms gap 8 s in: too short for a pause cut, long enough to cut in.
        val stream = Stream().silence(100).speechMs(8_000).silence(60).speechMs(6_000).silence(1_200).toArray()
        val run = run(stream)

        assertCoverage(run)
        val first = run.chunks.first()
        assertEquals(ChunkCut.FORCED, first.cut)
        assertTrue("cut moved into the gap, not at the ceiling", first.length < NpuEngine.MAX_SEGMENT_SAMPLES)
    }

    @Test
    fun finishFlushesTheOpenTurn() {
        val detector = FakeDetector()
        val archive = TurnArchive(config.archiveCapacitySamples)
        val segmenter = TurnSegmenter(detector, archive, config)
        val buffer = ShortArray(frame) { 8_000 }
        repeat(100) { segmenter.accept(buffer, frame) }
        val events = segmenter.finish()

        assertEquals(2, events.size)
        assertEquals(TurnEndReason.INPUT_END, (events.last() as SegmenterEvent.TurnEnded).reason)
        assertTrue(segmenter.finish().isEmpty())
    }
}
