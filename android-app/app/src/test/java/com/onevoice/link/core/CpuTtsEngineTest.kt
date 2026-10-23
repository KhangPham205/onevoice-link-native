package com.onevoice.link.core

import org.junit.Assert.assertEquals
import org.junit.Test

class CpuTtsEngineTest {
    @Test
    fun parsesBuildConfigNames() {
        assertEquals(CpuTtsEngine.KOKORO, CpuTtsEngine.parse("KOKORO"))
        assertEquals(CpuTtsEngine.SUPERTONIC, CpuTtsEngine.parse("SUPERTONIC"))
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
