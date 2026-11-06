package com.onevoice.link.core

import java.text.Normalizer
import org.junit.Assert.assertEquals
import org.junit.Test

class FillerWordsTest {
    private fun vi(text: String) = FillerWords.stripLeading(text, TranslationDirection.VI_TO_EN)
    private fun en(text: String) = FillerWords.stripLeading(text, TranslationDirection.EN_TO_VI)

    @Test
    fun loneVietnameseFillersLeaveNothingToTranslate() {
        // The turns that played "Yeah." / "Well..." with nobody speaking.
        assertEquals("", vi("ừ"))
        assertEquals("", vi("à"))
        assertEquals("", vi("ừ ừ"))
        assertEquals("", vi(" ờ  "))
    }

    @Test
    fun leadingFillersAreCutAndTheRestKept() {
        assertEquals("anh đóng", vi("ờ anh đóng"))
        assertEquals("I think so.", en("Uh, I think so."))
    }

    @Test
    fun answersParticlesAndGreetingsAreKept() {
        assertEquals("dạ", vi("dạ"))
        assertEquals("vâng", vi("vâng"))
        assertEquals("a lô một hai ba", vi("a lô một hai ba"))
        assertEquals("anh đi à", vi("anh đi à"))
        assertEquals("yes", en("yes"))
        assertEquals("ok", en("ok"))
    }

    @Test
    fun englishFillersWithPunctuationAreDropped() {
        assertEquals("", en("Um."))
        assertEquals("", en("uh, hmm..."))
    }

    @Test
    fun decomposedDiacriticsAreRecognized() {
        assertEquals("", vi(Normalizer.normalize("ừ", Normalizer.Form.NFD)))
    }

    @Test
    fun fillersAreLanguageSpecific() {
        // "ah" is an English filler only; GIPFormer never emits it as one.
        assertEquals("ah", vi("ah"))
        assertEquals("ừ", en("ừ"))
    }
}
