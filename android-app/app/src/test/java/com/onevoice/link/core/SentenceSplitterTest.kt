package com.onevoice.link.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SentenceSplitterTest {
    @Test
    fun splitsOnTerminalPunctuationFollowedBySpace() {
        assertEquals(
            listOf("Hello there.", "How are you?", "Fine!"),
            splitSentences("Hello there. How are you?  Fine!"),
        )
    }

    @Test
    fun keepsDecimalsAndUnspacedDotsWhole() {
        assertEquals(
            listOf("It costs 3.5 dollars at example.com.", "Okay."),
            splitSentences("It costs 3.5 dollars at example.com. Okay."),
        )
    }

    @Test
    fun unpunctuatedTextIsOnePiece() {
        assertEquals(listOf("xin chào bạn khỏe không"), splitSentences("xin chào bạn khỏe không"))
    }

    @Test
    fun dropsPunctuationOnlyFragmentsAndEmptyInput() {
        assertEquals(listOf("Yes.", "No?!"), splitSentences(" Yes. ... No?! ."))
        assertEquals(emptyList<String>(), splitSentences("  "))
    }

    @Test
    fun translatesEachSentenceAndJoins() {
        val seen = mutableListOf<String>()
        val joined = translateBySentence("One. Two? Three") { sentence ->
            seen += sentence
            " <${sentence.lowercase()}> "
        }
        assertEquals(listOf("One.", "Two?", "Three"), seen)
        assertEquals("<one.> <two?> <three>", joined)
    }
}
