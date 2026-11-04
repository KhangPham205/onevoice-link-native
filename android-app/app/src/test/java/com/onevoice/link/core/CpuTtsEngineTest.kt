package com.onevoice.link.core

import org.junit.Assert.assertEquals
import org.junit.Test

class CpuTtsEngineTest {
    @Test
    fun parsesBuildConfigNames() {
        assertEquals(CpuTtsEngine.KOKORO, CpuTtsEngine.parse("KOKORO"))
        assertEquals(CpuTtsEngine.SUPERTONIC, CpuTtsEngine.parse("SUPERTONIC"))
        assertEquals(CpuTtsEngine.VIENEU, CpuTtsEngine.parse("VIENEU"))
    }

    @Test
    fun onlyKokoroNeedsKokoroAssets() {
        assertEquals(listOf(CpuTtsEngine.KOKORO), CpuTtsEngine.entries.filter { it.usesKokoroAssets })
    }

    @Test
    fun vieneuKeepsAShortTurnAsOneChunk() {
        val turn = "Cho tôi một chiếc taxi để đưa tôi ra sân bay vào thứ sáu."
        assertEquals(listOf(turn), VieNeuNanoTts.chunkText(turn))
        assertEquals(emptyList<String>(), VieNeuNanoTts.chunkText("   "))
    }

    @Test
    fun vieneuSplitsLongTextAtSentencesWithinTheTrainingLength() {
        val sentence = "Cảm ơn vì đã đến và tôi sẽ gửi cho bạn biên bản cuộc họp hôm nay."
        val text = List(4) { sentence }.joinToString(" ")
        val chunks = VieNeuNanoTts.chunkText(text)
        assert(chunks.size >= 2) { "expected a split, got $chunks" }
        assert(chunks.all { it.length <= 140 }) { chunks.toString() }
        assertEquals(text.replace(" ", ""), chunks.joinToString("").replace(" ", ""))
    }

    @Test
    fun vieneuFallsBackToSpacesForOneRunOnClause() {
        val text = List(40) { "chữ" }.joinToString(" ")
        val chunks = VieNeuNanoTts.chunkText(text)
        assert(chunks.all { it.length <= 140 }) { chunks.toString() }
        assertEquals(40, chunks.sumOf { it.split(" ").size })
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownEngine() {
        CpuTtsEngine.parse("supertonic")
    }

    @Test
    fun supertonicVoicesFollowSherpaVoiceBinOrder() {
        assertEquals(0, SupertonicFiles.sid("F1"))
        assertEquals(4, SupertonicFiles.sid("F5"))
        assertEquals(5, SupertonicFiles.sid("M1"))
        assertEquals(9, SupertonicFiles.sid("M5"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownVoice() {
        SupertonicFiles.sid("M6")
    }

    @Test
    fun supertonicTextIsDecomposedToBaseLettersAndCombiningMarks() {
        // "ữ" = u + horn + tilde; "đ" has no decomposition and stays.
        assertEquals("su\u031B\u0303a đi", SupertonicFiles.prepareText("sữa đi"))
        val prepared = SupertonicFiles.prepareText("Cẩn thận, sàn nhà đang trơn ở xưởng.")
        assertEquals(
            "no precomposed letters may reach the indexer",
            emptyList<Char>(),
            prepared.filter { it.code in 0x00C0..0x024F && it != 'đ' || it.code in 0x1E00..0x1EFF }.toList(),
        )
    }

    @Test
    fun languageFollowsTheDirectionTarget() {
        assertEquals("vi", SupertonicFiles.language("en-vi"))
        assertEquals("en", SupertonicFiles.language("vi-en"))
    }
}
