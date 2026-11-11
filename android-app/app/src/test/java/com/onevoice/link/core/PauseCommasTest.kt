package com.onevoice.link.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PauseCommasTest {
    /** 16 kHz: [ms] of a 0.5-amplitude tone or of near-silence. */
    private fun tone(ms: Int) = FloatArray(ms * 16) { if (it % 2 == 0) 0.5f else -0.5f }
    private fun quiet(ms: Int) = FloatArray(ms * 16) { 0.001f }

    private fun words(vararg startMs: Int) = startMs.mapIndexed { i, ms -> AsrWord("w$i", ms * 16) }

    @Test
    fun marksTheWordAfterALongEnoughSilence() {
        // w0 w1 w2 | 300 ms silence | w3 w4
        val audio = tone(600) + quiet(300) + tone(600)
        val marked = PauseCommas.mark(words(0, 200, 400, 950, 1150), audio, 0, 250)
        assertEquals(listOf(false, false, false, true, false), marked.map { it.pauseBefore })
        assertEquals("w0 w1 w2, w3 w4", transcriptOf(marked))
    }

    @Test
    fun earlyEmissionStillGetsTheCommaAfterThePauseAndLeadingSilenceIsIgnored() {
        // Measured on device (D10): "dạ" spoken 380-580 ms, "cổ" from 940 ms,
        // emitted at 30 and 670 ms; "kim" ends 2520, "mật" from 2820, emitted 2350/2750.
        val audio = quiet(380) + tone(200) + quiet(360) + tone(1580) + quiet(300) + tone(1320)
        val w = listOf("dạ" to 30, "cổ" to 670, "áo" to 990, "kim" to 2350, "mật" to 2750, "độ" to 2950)
            .map { (t, ms) -> AsrWord(t, ms * 16) }
        val marked = PauseCommas.mark(w, audio, 0, 250)
        assertEquals(listOf(false, true, false, false, true, false), marked.map { it.pauseBefore })
        assertEquals("dạ cổ áo kim, mật độ", transcriptOf(marked)) // no comma after the first word
    }

    @Test
    fun aSentenceLengthPauseBecomesASentenceMark() {
        val audio = tone(600) + quiet(300) + tone(600) + quiet(800) + tone(600)
        val w = listOf(0, 200, 400, 950, 1150, 1950, 2150).mapIndexed { i, ms -> AsrWord("w$i", ms * 16) }
        val marked = PauseCommas.mark(w, audio, 0, 250, 600)
        assertEquals("w0 w1 w2, w3 w4; w5 w6", transcriptOf(marked))
    }

    @Test
    fun aPauseCommaInsideAKeptTermIsDropped() {
        val list = AsrBiasList.parse(
            listOf("-\t0\tsơ đồ cắt\tsơ đồ cắt\t▁SƠ ▁ĐỒ ▁CẮT"),
            mapOf("▁SƠ" to 1, "▁ĐỒ" to 2, "▁CẮT" to 3),
            1.5,
        )
        assertEquals("kiểm tra sơ đồ cắt trước", list.rewrite("kiểm tra sơ đồ, cắt trước"))
    }

    @Test
    fun ignoresShortSilencesAndCanBeTurnedOff() {
        val audio = tone(600) + quiet(160) + tone(600)
        val w = words(0, 200, 400, 800, 1000)
        assertEquals(List(5) { false }, PauseCommas.mark(w, audio, 0, 250).map { it.pauseBefore })
        val long = tone(600) + quiet(400) + tone(600)
        assertEquals(w, PauseCommas.mark(w, long, 0, 0))
    }

    @Test
    fun offsetsAreTurnAbsoluteAndTheWindowStartsLater() {
        val audio = tone(600) + quiet(300) + tone(600)
        val start = 16_000 * 7 // window starts 7 s into the turn
        val w = listOf(0, 200, 400, 950, 1150).mapIndexed { i, ms -> AsrWord("w$i", start + ms * 16) }
        assertEquals(true, PauseCommas.mark(w, audio, start, 250)[3].pauseBefore)
    }

    @Test
    fun noCommaAfterTheFirstWordOrBeforeTheLast() {
        val w = listOf(AsrWord("dạ", 0), AsrWord("em", 1, true), AsrWord("sẽ", 2), AsrWord("làm", 3, true))
        assertEquals("dạ em sẽ làm", transcriptOf(w))
    }

    @Test
    fun rewriteMatchesThroughACommaAndKeepsTheOneAfterTheTerm() {
        val list = AsrBiasList.parse(
            listOf(
                "-\t1\tchuyền năm\tchuyền 5\t▁CHUYỀN ▁NĂM",
                "-\t1\tsai em\tsize M\t▁SAI ▁EM",
            ),
            mapOf("▁CHUYỀN" to 1, "▁NĂM" to 2, "▁SAI" to 3, "▁EM" to 4),
            1.5,
        )
        assertEquals("chuyền 5, có ba mươi", list.rewrite("chuyền năm, có ba mươi"))
        assertEquals("lấy size M cho anh", list.rewrite("lấy sai, em cho anh"))
    }

    @Test
    fun punctuationKeepsPauseCommasAndAddsItsOwn() {
        assertEquals(
            "Hôm nay chuyền 5 có ba mươi hai công nhân, vắng hai người nghỉ phép.",
            VietnamesePunctuation.punctuate("hôm nay chuyền 5 có ba mươi hai công nhân, vắng hai người nghỉ phép"),
        )
        assertEquals(
            "Dạ, chân vịt bị mòn rồi, em sẽ thay.",
            VietnamesePunctuation.punctuate("dạ chân vịt bị mòn rồi, em sẽ thay"),
        )
    }

    @Test
    fun glossaryTermMayHoldAPauseComma() {
        val g = MtGlossary(listOf(MtGlossary.Entry(listOf("máy", "dò", "kim"), false, "needle detector")))
        assertEquals("có 101 ở cuối", g.protect("có máy dò, kim ở cuối").text)
    }
}
