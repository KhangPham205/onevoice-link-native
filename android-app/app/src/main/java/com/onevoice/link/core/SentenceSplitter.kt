package com.onevoice.link.core

/**
 * Split an ASR transcript into sentences so Marian translates each one alone.
 *
 * opus-mt was trained on single-sentence pairs. Given a multi-sentence
 * Parakeet transcript it usually translates the first sentence and emits EOS,
 * silently dropping the rest: on the 91 EN→VI dataset turns, 40 of the 44
 * multi-sentence transcripts lost at least one sentence. Translating sentence
 * by sentence raised EN→VI COMET from 0.761 to 0.787 on the host replay
 * (docs/proposal/evidence/P3_phan-tich-loi_2026-11-04.md).
 *
 * A boundary is one or more of `. ? !` followed by whitespace, so decimals
 * ("3.5") and dotted tokens without a following space stay whole. GIPFormer
 * emits no punctuation, so a Vietnamese transcript comes back as one piece.
 * Fragments that are only punctuation are dropped.
 */
internal fun splitSentences(text: String): List<String> =
    text.split(SENTENCE_BOUNDARY)
        .map(String::trim)
        .filter { piece -> piece.any { it !in SENTENCE_PUNCTUATION && !it.isWhitespace() } }

/** Translate [text] one sentence at a time with [translate] and join with spaces. */
internal inline fun translateBySentence(text: String, translate: (String) -> String): String =
    splitSentences(text)
        .map { translate(it).trim() }
        .filter(String::isNotEmpty)
        .joinToString(" ")

private val SENTENCE_BOUNDARY = Regex("(?<=[.?!])\\s+")
private const val SENTENCE_PUNCTUATION = ".?!"
