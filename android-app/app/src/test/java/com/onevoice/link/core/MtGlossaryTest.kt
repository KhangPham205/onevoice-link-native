package com.onevoice.link.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MtGlossaryTest {
    private fun entry(surface: String, english: String) = MtGlossary.Entry(
        surface.split(' '),
        caseSensitive = surface != surface.lowercase(),
        english = english,
    )

    private val glossary = MtGlossary(
        listOf(
            entry("size M", "size M"),
            entry("Nike", "Nike"),
            entry("chân vịt", "presser foot"),
            entry("máy vắt sổ", "overlock machine"),
            entry("máy hai kim", "double-needle machine"),
            entry("Thành Công", "Thanh Cong"),
        ),
    )

    @Test
    fun replacesTermsWithUnusedNumbersLongestFirst() {
        val p = glossary.protect("thay chân vịt cho máy hai kim")
        assertEquals("thay 101 cho 102", p.text)
        assertEquals(listOf("101" to "presser foot", "102" to "double-needle machine"), p.slots)
    }

    @Test
    fun skipsNumbersAlreadyInTheSentence() {
        val p = glossary.protect("lấy 101 cái áo size M")
        assertEquals("lấy 101 cái áo 102", p.text)
        assertEquals(listOf("102" to "size M"), p.slots)
    }

    @Test
    fun capitalisedTermsMatchOnlyAsWritten() {
        // "thành công" is "success"; only the company name is a glossary term.
        assertEquals("làm thành công rồi", glossary.protect("làm thành công rồi").text)
        assertEquals("công ty 101", glossary.protect("công ty Thành Công").text)
        // Lower-case terms match whatever the case.
        assertEquals("101 hỏng", glossary.protect("Máy vắt sổ hỏng").text)
    }

    @Test
    fun keepsTrailingPunctuation() {
        val p = glossary.protect("hàng của Nike, size M.")
        assertEquals("hàng của 101, 102.", p.text)
    }

    @Test
    fun restoresEnglishAndFixesArticleAndCapital() {
        val p = glossary.protect("máy vắt sổ của Nike")
        assertEquals("Overlock machine from Nike.", glossary.restore("101 from 102.", p))
        val q = glossary.protect("tôi muốn mua một cái áo size M")
        assertEquals("I want to buy a size M shirt.", glossary.restore("I want to buy a 101 shirt.", q))
        val r = glossary.protect("thay máy vắt sổ")
        assertEquals("Replace an overlock machine.", glossary.restore("Replace a 101.", r))
    }

    @Test
    fun dropsNumberMarksMarianAddsAroundAPlaceholder() {
        val p = glossary.protect("máy vắt sổ của Nike")
        // "101st", "#102": Marian read the placeholders as numbers.
        assertEquals("The overlock machine from Nike.", glossary.restore("The 101st from #102.", p))
        val q = glossary.protect("hai cái chân vịt")
        assertEquals("Two presser foots.", glossary.restore("Two 101s.", q))
        val goods = MtGlossary(listOf(entry("hàng lỗi", "defective goods")))
        val r = goods.protect("trả lại hàng lỗi")
        assertEquals("Returned defective goods.", goods.restore("Returned 101s.", r))
    }

    @Test
    fun coversWhenThePlainTranslationAlreadyHasEveryTerm() {
        val p = glossary.protect("đơn hàng Nike giao thứ sáu")
        assertEquals(true, glossary.covers("The Nike delivery order came by Friday.", p))
        val q = glossary.protect("thay chân vịt cho máy hai kim")
        assertEquals(false, glossary.covers("Put the duck on the two-chip.", q))
    }

    @Test
    fun lostOrRepeatedPlaceholderFallsBack() {
        val p = glossary.protect("áo size M của Nike")
        assertNull(glossary.restore("A shirt from 102.", p)) // 101 dropped
        assertNull(glossary.restore("101 and 101 from 102.", p)) // 101 repeated
        assertNull(glossary.restore("A 1010 shirt from 102.", p)) // only a longer number
    }

    @Test
    fun letterPlaceholdersSkipCodesInTheSentenceAndRestore() {
        val p = glossary.protect("thêm X1 cái áo size M", MtGlossary.Style.LETTER)
        assertEquals("thêm X1 cái áo X2", p.text)
        // Seen on device: "fifty more X1 shirts" kept the code where "101" was dropped.
        val q = glossary.protect("áo size M", MtGlossary.Style.LETTER)
        assertEquals("Người mua muốn 50 cái áo size M nữa.", glossary.restore("Người mua muốn 50 cái áo X1 nữa.", q))
        assertEquals("Two presser foots.", glossary.restore("Two X1s.", glossary.protect("hai chân vịt", MtGlossary.Style.LETTER)))
    }

    @Test
    fun inlinePutsTheEnglishInTheSourceAndCountsWhatSurvives() {
        val p = glossary.protect("báo chân vịt và máy vắt sổ", MtGlossary.Style.INLINE)
        assertEquals("báo presser foot và overlock machine", p.text)
        assertEquals("Report the presser foot and the overlock maschine." to 1,
            glossary.restoreFound("Report the presser foot and the overlock maschine.", p))
    }

    @Test
    fun restoreFoundKeepsWhatMarianKeptAndRejectsMangledPlaceholders() {
        val p = glossary.protect("áo size M của Nike")
        assertEquals("A shirt from Nike." to 1, glossary.restoreFound("A shirt from 102.", p))
        assertNull(glossary.restoreFound("A 1010 shirt from 102.", p)) // mangled, not dropped
        assertNull(glossary.restoreFound("101 and 101 from 102.", p)) // repeated
    }

    @Test
    fun guardKeepsTheLongerPhraseAsSpoken() {
        val list = AsrBiasList.parse(
            listOf(
                "-\t1\tchuyền năm\tchuyền 5\t▁CHUYỀN ▁NĂM",
                "k\tchuyền năm nay",
                "g\t0\tchuyền 5\tline 5",
            ),
            mapOf("▁CHUYỀN" to 1, "▁NĂM" to 2),
            1.5,
        )
        assertEquals("chuyền 5 hôm nay đủ người", list.rewrite("chuyền năm hôm nay đủ người"))
        assertEquals("chuyền năm nay làm tốt", list.rewrite("chuyền năm nay làm tốt"))
        assertEquals("101 hôm nay", list.glossary.protect(list.rewrite("chuyền năm hôm nay")).text)
    }

    @Test
    fun reverseGlossaryProtectsEnglishTermsForVietnamese() {
        val list = AsrBiasList.parse(
            listOf(
                "e\t0\tline 5\tchuyền 5",
                "e\t0\tline five\tchuyền 5",
                "e\t0\tpresser foot\tchân vịt",
            ),
            emptyMap(),
            1.5,
        )
        val g = list.reverseGlossary
        assertEquals(3, g.size)
        // Parakeet capitalises a sentence start; English terms match any case.
        val p = g.protect("Line five needs a new presser foot.")
        assertEquals("101 needs a new 102.", p.text)
        assertEquals("Chuyền 5 cần một chân vịt mới.", g.restore("101 cần một 102 mới.", p))
        // Marian writes "số 101" for a number it was given.
        val q = g.protect("Please replace the presser foot")
        assertEquals("Xin hãy thay chân vịt", g.restore("Xin hãy thay số 101", q))
        assertEquals(0, list.glossary.size)
    }

    @Test
    fun capitalisedBrandIsKeptButTheEverydayWordIsNot() {
        val g = AsrBiasList.parse(listOf("e\t1\tBrother\tBrother"), emptyMap(), 1.5).reverseGlossary
        assertEquals("The 101 machine is broken.", g.protect("The Brother machine is broken.").text)
        assertEquals("My brother works on line 3.", g.protect("My brother works on line 3.").text)
    }

    @Test
    fun dropsTheCounterMarianPutsAfterAPlaceholder() {
        val g = AsrBiasList.parse(
            listOf("e\t0\tbrother machine\tmáy Brother", "e\t0\tzipper\tdây kéo"),
            emptyMap(),
            1.5,
        ).reverseGlossary
        val p = g.protect("The brother machine needs a new zipper")
        assertEquals("Máy Brother cũng cần dây kéo mới.", g.restore("101 người cũng cần 102 người mới.", p))
    }

    @Test
    fun compiledGlossaryLinesAreParsed() {
        val list = AsrBiasList.parse(
            listOf("# onevoice-asr-bias v1", "g\t1\tsize M\tsize M", "g\t0\tchân vịt\tpresser foot"),
            emptyMap(),
            1.5,
        )
        assertEquals(2, list.glossary.size)
        assertEquals("thay 101", list.glossary.protect("thay chân vịt").text)
    }
}
