package com.onevoice.link.core

import java.text.Normalizer
import java.util.Locale

/**
 * Hesitation words that carry nothing to translate.
 *
 * Breath, clothing rustle and room noise on a lavalier or phone mic pass
 * Silero, and the VI recognizer decodes them as "ừ" or "à"; Marian then says
 * "Yeah." or "Well..." although nobody spoke (16 of 32 turns in one
 * 2026-11-06 lavalier session). A translation unit made only of these words
 * is dropped, and leading ones are cut off ("ờ anh đóng" came out as "Yeah,
 * yeah, yeah, yeah, yeah.").
 *
 * Kept on purpose: words that answer ("dạ", "vâng", "yes", "ok", "no"), "a"
 * as in "a lô", and trailing particles ("anh đi à") that change what a
 * sentence means.
 */
internal object FillerWords {
    private val VIETNAMESE = setOf("ừ", "ờ", "ừm", "ờm", "ừa", "à", "ơ", "ư", "hừm", "hm", "hmm")
    private val ENGLISH = setOf("uh", "um", "umm", "uhm", "hmm", "hm", "mm", "mmm", "mhm", "er", "erm", "ah")
    private val WHITESPACE = Regex("\\s+")

    /** [text] (in the source language of [direction]) without its leading fillers; "" if nothing else. */
    fun stripLeading(text: String, direction: TranslationDirection): String {
        val fillers = when (direction) {
            TranslationDirection.VI_TO_EN -> VIETNAMESE
            TranslationDirection.EN_TO_VI -> ENGLISH
        }
        val words = text.trim().split(WHITESPACE).filter(String::isNotEmpty)
        val first = words.indexOfFirst { bare(it) !in fillers }
        return when (first) {
            -1 -> ""
            0 -> text
            else -> words.subList(first, words.size).joinToString(" ")
        }
    }

    private fun bare(word: String): String =
        Normalizer.normalize(word, Normalizer.Form.NFC)
            .trim { !it.isLetter() }
            .lowercase(Locale.ROOT)
}
