package com.onevoice.link.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptAssemblerTest {
    private val tolerance = LongTurnConfig.ms(120)
    private val overlap = LongTurnConfig.ms(960)

    private fun w(text: String, ms: Int) = AsrWord(text, LongTurnConfig.ms(ms))

    private fun chunk(index: Int, startMs: Int, endMs: Int, cut: ChunkCut, previous: ChunkCut?): AudioChunk {
        val start = LongTurnConfig.ms(startMs)
        val end = LongTurnConfig.ms(endMs)
        val coreStart = when (previous) {
            null -> 0
            ChunkCut.FORCED -> start + overlap / 2
            else -> start
        }
        val coreEnd = if (cut == ChunkCut.FORCED) end - overlap / 2 else end
        return AudioChunk(index, start, end, coreStart, coreEnd, cut, previous)
    }

    private fun TranscriptAssembler.text() = transcriptOf(committedWords)

    @Test
    fun tokensBecomeWordsAtWordMarks() {
        val words = wordsFromTokens(
            listOf(AsrToken("▁xin", 0), AsrToken("▁ch", 640), AsrToken("ào", 1_280), AsrToken(".", 1_920)),
            windowStart = 16_000,
        )
        assertEquals(listOf(AsrWord("xin", 16_000), AsrWord("chào.", 16_640)), words)
    }

    @Test
    fun pauseCutWindowsConcatenate() {
        val assembler = TranscriptAssembler(tolerance)
        assembler.accept(chunk(0, 0, 8_000, ChunkCut.PAUSE, null), listOf(w("một", 500), w("hai", 7_800)))
        assembler.accept(chunk(1, 8_000, 12_000, ChunkCut.TURN_END, ChunkCut.PAUSE), listOf(w("ba", 8_100)))
        assertEquals("một hai ba", assembler.text())
    }

    @Test
    fun overlappingWindowsKeepEachWordOnceByCore() {
        // Window 0 [0,10000) core ends at 9520; window 1 starts at 9040.
        val assembler = TranscriptAssembler(tolerance)
        assembler.accept(
            chunk(0, 0, 10_000, ChunkCut.FORCED, null),
            listOf(w("we", 200), w("will", 9_100), w("ship", 9_700)),
        )
        assertEquals(listOf("ship"), assembler.pendingWords.map { it.text })
        assembler.accept(
            chunk(1, 9_040, 14_000, ChunkCut.TURN_END, ChunkCut.FORCED),
            listOf(w("will", 9_100), w("ship", 9_700), w("it", 10_300)),
        )
        assertEquals("we will ship it", assembler.text())
        assertTrue(assembler.pendingWords.isEmpty())
    }

    @Test
    fun aWordBothCoresClaimAtTheMidpointIsKeptOnce() {
        val assembler = TranscriptAssembler(tolerance)
        assembler.accept(chunk(0, 0, 10_000, ChunkCut.FORCED, null), listOf(w("xin", 9_480)))
        assembler.accept(
            chunk(1, 9_040, 12_000, ChunkCut.TURN_END, ChunkCut.FORCED),
            listOf(w("xin", 9_540), w("chào", 9_900)),
        )
        assertEquals("xin chào", assembler.text())
    }

    @Test
    fun aWordBothCoresDropAtTheMidpointIsRecovered() {
        val assembler = TranscriptAssembler(tolerance)
        assembler.accept(chunk(0, 0, 10_000, ChunkCut.FORCED, null), listOf(w("cảm", 9_000), w("ơn", 9_560)))
        assembler.accept(
            chunk(1, 9_040, 12_000, ChunkCut.TURN_END, ChunkCut.FORCED),
            listOf(w("cảm", 9_000), w("ơn", 9_500), w("bạn", 10_000)),
        )
        assertEquals("cảm ơn bạn", assembler.text())
    }

    @Test
    fun genuineRepeatsAcrossTheBoundarySurvive() {
        val assembler = TranscriptAssembler(tolerance)
        assembler.accept(chunk(0, 0, 10_000, ChunkCut.FORCED, null), listOf(w("có", 9_250)))
        assembler.accept(
            chunk(1, 9_040, 12_000, ChunkCut.TURN_END, ChunkCut.FORCED),
            listOf(w("có", 9_250), w("có", 9_600), w("rất", 10_000), w("rất", 10_250)),
        )
        assertEquals("có có rất rất", assembler.text())
    }

    @Test
    fun flushCommitsTheUnresolvedTail() {
        val assembler = TranscriptAssembler(tolerance)
        assembler.accept(chunk(0, 0, 10_000, ChunkCut.FORCED, null), listOf(w("a", 100), w("tail", 9_800)))
        assertEquals("a", assembler.text())
        assertEquals(listOf(AsrWord("tail", LongTurnConfig.ms(9_800))), assembler.flush())
        assertEquals("a tail", assembler.text())
    }

    @Test
    fun punctuationAndCaseDoNotSplitADuplicate() {
        assertEquals(normalizedWord("Ship."), normalizedWord("ship"))
    }
}
