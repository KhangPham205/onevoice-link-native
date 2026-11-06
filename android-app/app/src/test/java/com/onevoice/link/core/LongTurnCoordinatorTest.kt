package com.onevoice.link.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LongTurnCoordinatorTest {
    private val config = LongTurnConfig(mode = LongTurnMode.ADAPTIVE)
    private val second = NpuEngine.SAMPLE_RATE

    /**
     * Recognizes a window as one word per second of audio, named by its turn
     * second ("s0", "s1", ...), timed at that second; translation upper-cases.
     * The archive stores 1000 + second in every sample, so a window tells the
     * fake where it starts.
     */
    private class FakeInference(
        val routeTo: TranslationDirection? = TranslationDirection.VI_TO_EN,
        /** The word recognized for each turn second. */
        val wordFor: (Int) -> String = { "s$it" },
    ) : LongTurnInference {
        val routeCalls = ArrayList<Int>()
        val recognizeCalls = ArrayList<Pair<Int, ExecutionPath>>()
        val translateCalls = ArrayList<Pair<String, ExecutionPath>>()
        val synthesized = ArrayList<String>()
        var quarantined = false
        var failNpuMtOn: String? = null
        var truncateLongerThan = Int.MAX_VALUE

        fun startOf(samples: FloatArray): Int =
            (Math.round(samples[0] * 32_768f) - 1_000) * 16_000

        private fun words(samples: FloatArray): AsrHypothesis {
            val start = startOf(samples)
            val first = start / 16_000
            val last = (start + samples.size - 1) / 16_000
            val tokens = (first..last).map { s -> AsrToken("▁" + wordFor(s), s * 16_000 - start) }
            return AsrHypothesis(tokens.joinToString(" ") { it.piece.drop(1) }, -1.0, tokens.size, -0.5, tokens)
        }

        override fun routeChunk(samples: FloatArray): RoutedChunk {
            routeCalls += samples.size
            val routing = RoutingDiagnostics(
                mode = RoutingMode.DUAL_ASR,
                decision = routeTo,
                reason = if (routeTo == null) RoutingDecisionReason.NO_PRIOR else RoutingDecisionReason.MARGIN,
            )
            return RoutedChunk(routeTo, routeTo?.let { words(samples) }, routing)
        }

        override fun recognize(samples: FloatArray, direction: TranslationDirection, path: ExecutionPath): AsrHypothesis {
            recognizeCalls += startOf(samples) to path
            return words(samples)
        }

        override fun translate(text: String, direction: TranslationDirection, path: ExecutionPath): MtOutput {
            translateCalls += text to path
            if (path == ExecutionPath.HYBRID_NPU && failNpuMtOn != null && text.contains(failNpuMtOn!!)) {
                error("QNN execute failed")
            }
            val wordCount = text.split(' ').size
            return MtOutput(text.uppercase(), wordCount, truncated = wordCount > truncateLongerThan)
        }

        override fun sourceTokens(text: String, direction: TranslationDirection) = text.split(' ').size + 2
        override fun hasPunctuation(direction: TranslationDirection) = false
        override fun synthesize(text: String, direction: TranslationDirection): FloatArray {
            synthesized += text
            return FloatArray(2_400) { 0.1f }
        }
        override fun isNpuTextQuarantined(direction: TranslationDirection) = quarantined
        override fun quarantineNpuText(direction: TranslationDirection) {
            quarantined = true
        }
    }

    private class Harness(cfg: LongTurnConfig, val fake: FakeInference, seconds: Int) {
        val archive = TurnArchive(cfg.archiveCapacitySamples).apply {
            repeat(seconds) { s ->
                val marked = ShortArray(16_000) { (1_000 + s).toShort() }
                append(marked, 0, marked.size)
            }
        }
        val turn = LongTurn(1L, 0L, archive, cfg, 0L)
        val coordinator = LongTurnCoordinator(fake)

        fun window(index: Int, startS: Int, endS: Int, cut: ChunkCut, previous: ChunkCut?) {
            val chunk = AudioChunk(index, startS * 16_000, endS * 16_000, startS * 16_000, endS * 16_000, cut, previous)
            coordinator.handleForTest(turn, SegmenterEvent.Chunk(chunk))
        }

        fun end(reason: TurnEndReason = TurnEndReason.SPEAKER_DONE) {
            coordinator.handleForTest(turn, SegmenterEvent.TurnEnded(reason, archive.size, archive.size, 0))
            coordinator.drainTtsForTest()
        }

        fun playedUnits(): List<Int> {
            val played = ArrayList<Int>()
            while (true) {
                when (val take = turn.audio.take(0)) {
                    is AudioTake.Item -> played += take.audio.unitIndex
                    else -> return played
                }
            }
        }
    }

    @Test
    fun theFirstWindowIsRoutedOnceAndItsHypothesisReused() {
        val fake = FakeInference()
        val harness = Harness(config, fake, seconds = 24)
        harness.window(0, 0, 8, ChunkCut.PAUSE, null)
        harness.window(1, 8, 16, ChunkCut.PAUSE, ChunkCut.PAUSE)
        harness.window(2, 16, 24, ChunkCut.TURN_END, ChunkCut.PAUSE)
        harness.end()

        assertEquals("one routing call for the whole turn", 1, fake.routeCalls.size)
        assertEquals("windows 1 and 2 only use the locked branch", listOf(8 * second, 16 * second),
            fake.recognizeCalls.map { it.first })
        assertEquals(TranslationDirection.VI_TO_EN, harness.turn.direction)
        assertEquals((0 until 24).joinToString(" ") { "s$it" }, harness.turn.transcript)
        assertEquals(listOf(0, 1, 2), harness.playedUnits())
        assertNull(harness.turn.error)
        assertTrue(harness.turn.workerDone)
    }

    @Test
    fun aTurnOfOnlyFillerWordsEndsQuietlyWithoutTranslation() {
        val fake = FakeInference(wordFor = { "ừ" })
        val harness = Harness(config, fake, seconds = 2)
        harness.window(0, 0, 2, ChunkCut.TURN_END, null)
        harness.end()

        assertTrue("nothing reaches MT", fake.translateCalls.isEmpty())
        assertTrue("nothing is spoken", fake.synthesized.isEmpty())
        assertNull("noise is not an error", harness.turn.error)
        assertTrue(harness.turn.fillerOnly)
        assertTrue(harness.turn.audio.take(0) is AudioTake.Finished)
        assertTrue(harness.turn.workerDone)
    }

    @Test
    fun leadingFillerWordsAreNotTranslated() {
        val fake = FakeInference(wordFor = { s -> if (s == 0) "ờ" else "s$s" })
        val harness = Harness(config, fake, seconds = 3)
        harness.window(0, 0, 3, ChunkCut.TURN_END, null)
        harness.end()

        assertEquals(listOf("s1 s2"), fake.translateCalls.map { it.first })
        assertFalse(harness.turn.fillerOnly)
        assertEquals(listOf(0), harness.playedUnits())
    }

    @Test
    fun fillerWordsAreTranslatedWhenTheFilterIsOff() {
        val fake = FakeInference(wordFor = { "ừ" })
        val harness = Harness(config.copy(dropFillerWords = false), fake, seconds = 1)
        harness.window(0, 0, 1, ChunkCut.TURN_END, null)
        harness.end()

        assertEquals(listOf("ừ"), fake.translateCalls.map { it.first })
        assertFalse(harness.turn.fillerOnly)
    }

    @Test
    fun rejectedRoutingFailsClosedAndAsksCaptureToStop() {
        val fake = FakeInference(routeTo = null)
        val harness = Harness(config, fake, seconds = 8)
        harness.window(0, 0, 8, ChunkCut.PAUSE, null)

        assertEquals(TurnEndReason.ROUTING_REJECTED, harness.turn.abortRequest)
        harness.end(TurnEndReason.ROUTING_REJECTED)
        assertTrue(harness.turn.audio.take(0) is AudioTake.Failed)
        assertTrue(fake.recognizeCalls.isEmpty())
        assertTrue(fake.translateCalls.isEmpty())
        assertTrue(harness.turn.workerDone)
    }

    @Test
    fun deferredPolicyTranslatesOnlyAfterTheTurnEnds() {
        val fake = FakeInference()
        val harness = Harness(config.copy(backgroundTranslation = false), fake, seconds = 16)
        harness.window(0, 0, 8, ChunkCut.PAUSE, null)
        harness.window(1, 8, 16, ChunkCut.TURN_END, ChunkCut.PAUSE)

        assertTrue("no MT while the speaker is still talking", fake.translateCalls.isEmpty())
        harness.end()
        assertEquals(2, fake.translateCalls.size)
        assertEquals(listOf(0, 1), harness.playedUnits())
    }

    @Test
    fun npuFailureBeforePlaybackRebuildsTheWholeTurnOnCpu() {
        val fake = FakeInference().apply { failNpuMtOn = "s9" }
        val harness = Harness(config, fake, seconds = 24)
        harness.window(0, 0, 8, ChunkCut.PAUSE, null)
        // Window 1's unit fails on NPU MT; windows 0 and 1 must be redone on CPU.
        harness.window(1, 8, 16, ChunkCut.PAUSE, ChunkCut.PAUSE)
        harness.window(2, 16, 24, ChunkCut.TURN_END, ChunkCut.PAUSE)
        harness.end()

        assertTrue(fake.quarantined)
        assertEquals(ExecutionPath.CPU_FALLBACK, harness.turn.executionPath)
        assertEquals(1, harness.turn.revision)
        assertEquals(
            listOf(0 to ExecutionPath.CPU_FALLBACK, 8 * second to ExecutionPath.CPU_FALLBACK,
                16 * second to ExecutionPath.CPU_FALLBACK),
            fake.recognizeCalls.filter { it.second == ExecutionPath.CPU_FALLBACK },
        )
        assertTrue("only CPU translations are played", harness.turn.units
            .filter { it.revision == harness.turn.revision }.all { it.path == ExecutionPath.CPU_FALLBACK })
        val played = harness.playedUnits()
        assertEquals(3, played.size)
        assertNull(harness.turn.error)
        assertEquals((0 until 24).joinToString(" ") { "s$it" }, harness.turn.transcript)
    }

    @Test
    fun npuFailureAfterPlaybackBeganIsAPartialFailure() {
        val fake = FakeInference().apply { failNpuMtOn = "s9" }
        val harness = Harness(config, fake, seconds = 16)
        harness.window(0, 0, 8, ChunkCut.PAUSE, null)
        harness.coordinator.drainTtsForTest()
        assertTrue(harness.turn.markPlaybackStarted())
        harness.window(1, 8, 16, ChunkCut.TURN_END, ChunkCut.PAUSE)
        harness.end()

        assertTrue(harness.turn.partialFailure)
        assertTrue(harness.turn.error!!.startsWith("partial_failure"))
        assertEquals("no CPU replay after audio was heard", 0,
            fake.recognizeCalls.count { it.second == ExecutionPath.CPU_FALLBACK })
    }

    @Test
    fun aTruncatedTranslationIsSplitAndRetriedNotPlayedCut() {
        val fake = FakeInference().apply { truncateLongerThan = 5 }
        val harness = Harness(config, fake, seconds = 8)
        harness.window(0, 0, 8, ChunkCut.TURN_END, null)
        harness.end()

        val unit = harness.turn.units.single()
        assertTrue(unit.truncationSplits >= 1)
        assertEquals((0 until 8).joinToString(" ") { "S$it" }, unit.translation)
        assertNull(harness.turn.error)
    }

    @Test
    fun playbackCannotStartOnceRecoveryBegan() {
        val turn = LongTurn(1L, 0L, TurnArchive(config.archiveCapacitySamples), config, 0L)
        assertTrue(turn.beginRecovery())
        assertFalse(turn.markPlaybackStarted())
    }
}
