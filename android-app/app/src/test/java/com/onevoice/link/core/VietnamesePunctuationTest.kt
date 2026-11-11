package com.onevoice.link.core

import org.junit.Assert.assertEquals
import org.junit.Test

class VietnamesePunctuationTest {
    private fun p(text: String) = VietnamesePunctuation.punctuate(text)

    @Test
    fun responseWordTakesACommaAndTheTurnAFullStop() {
        assertEquals("Dạ, mẫu đối đang để ở bàn KCS.", p("dạ mẫu đối đang để ở bàn KCS"))
        assertEquals("Dạ được, em sẽ để mở một thùng size M.", p("dạ được em sẽ để mở một thùng size M"))
    }

    @Test
    fun greetingKeepsTheNameBeforeTheComma() {
        assertEquals("Chào anh david, em là linh.", p("chào anh david em là linh"))
        assertEquals("Cảm ơn anh, em đi đây.", p("cảm ơn anh em đi đây"))
    }

    @Test
    fun conjunctionAndRestartedClauseTakeCommas() {
        assertEquals("Được anh, nhưng bên em cần thêm vải.", p("được anh nhưng bên em cần thêm vải"))
        assertEquals("Chân vịt bị mòn rồi, em sẽ thay mới.", p("chân vịt bị mòn rồi em sẽ thay mới"))
        // Too short to be a clause of its own: no comma.
        assertEquals("Hôm nay em sẽ thay.", p("hôm nay em sẽ thay"))
    }

    @Test
    fun questionWordsEndWithAQuestionMark() {
        assertEquals("Chuyền 5 có bao nhiêu công nhân?", p("chuyền 5 có bao nhiêu công nhân"))
        assertEquals("Anh xong chưa?", p("anh xong chưa"))
        assertEquals("Ai kiểm tra sơ đồ?", p("ai kiểm tra sơ đồ"))
    }

    @Test
    fun questionsOfEveryShape() {
        listOf(
            "anh kiểm tra mẫu đối chưa ạ" to "Anh kiểm tra mẫu đối chưa ạ?",
            "máy vắt sổ này bị lỗi gì" to "Máy vắt sổ này bị lỗi gì?",
            "khi nào vải cotton về" to "Khi nào vải cotton về?",
            "tại sao hôm qua tỷ lệ hàng lỗi cao vậy" to "Tại sao hôm qua tỷ lệ hàng lỗi cao vậy?",
            "anh cần thêm mẫu size M phải không" to "Anh cần thêm mẫu size M phải không?",
            "mỗi thùng đóng mấy cái áo" to "Mỗi thùng đóng mấy cái áo?",
            "nhãn giặt may ở bên trái hay bên phải" to "Nhãn giặt may ở bên trái hay bên phải?",
            "chân vịt mới về rồi à" to "Chân vịt mới về rồi à?",
        ).forEach { (said, expected) -> assertEquals(expected, p(said)) }
    }

    @Test
    fun statementsThatLookLikeQuestions() {
        listOf(
            "không có gì đâu anh" to "Không có gì đâu anh.",
            "ai cũng phải đeo khẩu trang trong xưởng" to "Ai cũng phải đeo khẩu trang trong xưởng.",
            "em không biết khi nào vải về" to "Em không biết khi nào vải về.",
            "anh cần gì thêm cứ gọi em" to "Anh cần gì thêm cứ gọi em.",
            "em chưa kiểm tra chuyền sáu" to "Em chưa kiểm tra chuyền sáu.",
            "dạ không" to "Dạ, không.",
            "em cũng không" to "Em cũng không.",
            "khi nào xong thì em báo anh" to "Khi nào xong thì em báo anh.",
        ).forEach { (said, expected) -> assertEquals(expected, p(said)) }
    }

    @Test
    fun sentenceMarksAndClosingParticlesSplitTheTurn() {
        // ";" is PauseCommas' sentence-length pause.
        assertEquals("Vải cotton về rồi. Anh có muốn kiểm tra không?", p("vải cotton về rồi; anh có muốn kiểm tra không"))
        assertEquals("Anh có muốn xem không? Em mang ra.", p("anh có muốn xem không, em mang ra"))
        // A question to the listener after a statement starts its own sentence.
        assertEquals("Hôm nay vắng hai người. Anh có cần thêm không?", p("hôm nay vắng hai người, anh có cần thêm không"))
        assertEquals("Dạ, mẫu đối đây. Anh thấy cổ áo thế nào?", p("dạ, mẫu đối đây, anh thấy cổ áo thế nào"))
        assertEquals("Anh có muốn xem, máy thùa khuy không?", p("anh có muốn xem, máy thùa khuy không"))
    }

    @Test
    fun aPauseRightAfterTheQuestionWordKeepsTheQuestion() {
        assertEquals("Bao giờ, anh quay lại kiểm hàng?", p("bao giờ, anh quay lại kiểm hàng"))
        assertEquals("Khi nào xong, em báo anh.", p("khi nào xong, em báo anh"))
    }

    @Test
    fun punctuatedOrEmptyTextIsLeftAlone() {
        assertEquals("Dạ, xong rồi.", p("Dạ, xong rồi."))
        assertEquals("", p(""))
        assertEquals("Dạ.", p("dạ"))
    }
}
