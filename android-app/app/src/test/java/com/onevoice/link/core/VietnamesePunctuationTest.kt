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
    fun punctuatedOrEmptyTextIsLeftAlone() {
        assertEquals("Dạ, xong rồi.", p("Dạ, xong rồi."))
        assertEquals("", p(""))
        assertEquals("Dạ.", p("dạ"))
    }
}
