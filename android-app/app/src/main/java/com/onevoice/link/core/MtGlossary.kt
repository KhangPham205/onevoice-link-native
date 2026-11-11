package com.onevoice.link.core

/**
 * [text] for Marian with listed terms replaced by placeholders; [slots] maps
 * each placeholder to its English. With [MtGlossary.Style.INLINE] the
 * placeholder is the English itself and nothing needs restoring.
 */
internal class ProtectedText(
    val text: String,
    val slots: List<Pair<String, String>>,
    val style: MtGlossary.Style = MtGlossary.Style.NUMBER,
)

/**
 * A term library's MT glossary (the `g` lines of a compiled library).
 *
 * Marian keeps a number it was given far more reliably than a name or a trade
 * term: "chân vịt" came out as "duck" and "áo size M" as "nightgown M-shirt",
 * while "máy 101" stays "101 machine". So each listed term crosses Marian as a
 * number and the library's English replaces that number afterwards. The
 * numbers are ones absent from the text, so a real "101 cái áo" is never taken
 * for a placeholder.
 *
 * Matching mirrors [AsrBiasList.rewrite]: left to right, longest match, whole
 * words, trailing punctuation kept. A surface with a capital ("Thành Công")
 * matches only as written, so the ordinary words "thành công" are untouched.
 */
internal class MtGlossary(entries: List<Entry>) {
    class Entry(val words: List<String>, val caseSensitive: Boolean, val english: String) {
        init {
            require(words.isNotEmpty() && english.isNotBlank()) { "Empty glossary entry" }
        }
    }

    private val byFirst: Map<String, List<Entry>> = entries
        .groupBy { it.words.first().lowercase() }
        .mapValues { (_, list) -> list.sortedByDescending { it.words.size } }

    val size: Int = entries.size

    /**
     * How a term crosses Marian. No one form survives every sentence, so the
     * caller tries them in this order until one keeps every term:
     * - NUMBER ("101"): kept best overall, but two in a row ("báo 101 102")
     *   or one after a count ("fifty more 101 shirts") is often dropped.
     * - LETTER ("X1"): survives the counted case ("50 cái áo X1 nữa").
     * - INLINE (the English itself): the last resort when Marian drops any
     *   placeholder as an object ("báo 101" -> "call you"); Marian may
     *   respell an unknown word, so it is never the first try.
     */
    enum class Style { NUMBER, LETTER, INLINE }

    fun protect(text: String, style: Style = Style.NUMBER): ProtectedText {
        if (byFirst.isEmpty()) return ProtectedText(text, emptyList(), style)
        val words = text.split(' ').filter(String::isNotEmpty)
        val cores = words.map { it.trimEnd(*TRAILING) }
        val out = ArrayList<String>(words.size)
        val slots = ArrayList<Pair<String, String>>()
        var next = if (style == Style.NUMBER) FIRST_PLACEHOLDER else 1
        var i = 0
        while (i < words.size) {
            val match = byFirst[cores[i].lowercase()]?.firstOrNull { matches(it, words, cores, i) }
            if (match == null) {
                out += words[i]
                i++
                continue
            }
            val placeholder = when (style) {
                Style.INLINE -> match.english
                else -> {
                    val prefix = if (style == Style.LETTER) LETTER_PREFIX else ""
                    while (text.contains("$prefix$next")) next++
                    "$prefix${next++}"
                }
            }
            val last = i + match.words.size - 1
            out += placeholder + words[last].substring(cores[last].length)
            slots += placeholder to match.english
            i = last + 1
        }
        return ProtectedText(out.joinToString(" "), slots, style)
    }

    /**
     * Puts each slot's English back into Marian's [translation], fixing the
     * article before it and capitalising a sentence start. Null when a
     * placeholder is missing or repeated: the caller then translates the
     * unprotected text rather than play a sentence with a number in it.
     */
    fun restore(translation: String, protected: ProtectedText): String? =
        restoreFound(translation, protected)?.takeIf { it.second == protected.slots.size }?.first

    /**
     * [restore] that skips a dropped placeholder: the translation with the
     * terms Marian kept, and how many those are. Null when a placeholder is
     * repeated, since which copy is the term is unknowable. INLINE text is
     * returned as is.
     */
    fun restoreFound(translation: String, protected: ProtectedText): Pair<String, Int>? {
        if (protected.style == Style.INLINE) return translation to covered(translation, protected)
        var out = translation
        var found = 0
        for ((placeholder, english) in protected.slots) {
            // Marian treats a bare number as one: "#101", "No. 101", "101st",
            // "101s", and in Vietnamese "số 101" or a counted noun after it
            // ("101 người", "102 cái": "máy Brother người"). The mark, the
            // suffix and the counter belong to the number, not the term.
            val pattern = Regex(
                "(?:#\\s?|No\\.\\s?|(?<!\\p{L})số\\s)?(?<![\\d\\p{L}.,])$placeholder" +
                    "(?:st|nd|rd|th|'s|s)?(?:\\s(?:người|cái|chiếc)(?!\\p{L}))?(?![\\d\\p{L}])",
            )
            val hits = pattern.findAll(out).toList()
            if (hits.size > 1) return null
            // Dropped outright: skip it. Mangled ("1010", "X12"): reject,
            // rather than play a sentence with a number in it.
            val match = hits.singleOrNull() ?: if (out.contains(placeholder)) return null else continue
            found++
            val suffix = match.value.substringAfter(placeholder).trim()
                .takeIf { it in setOf("st", "nd", "rd", "th", "'s", "s") }.orEmpty()
            // Keep a plural "s" unless the term already ends in one ("goods").
            val plural = if (suffix == "s" && !english.endsWith("s")) "s" else if (suffix == "'s") "'s" else ""
            val before = out.substring(0, match.range.first)
            val sentenceStart = before.isBlank() || before.trimEnd().lastOrNull() in SENTENCE_END
            val term = if (sentenceStart) english.replaceFirstChar(Char::uppercaseChar) else english
            out = fixArticle(before, term) + term + plural + out.substring(match.range.last + 1)
        }
        return out to found
    }

    /** True when [translation] already holds every slot's English (case-insensitive). */
    fun covers(translation: String, protected: ProtectedText): Boolean =
        covered(translation, protected) == protected.slots.size

    /** How many slots' English [translation] holds (case-insensitive). */
    fun covered(translation: String, protected: ProtectedText): Int =
        protected.slots.count { (_, english) -> translation.contains(english, ignoreCase = true) }

    /** "a" before a vowel letter becomes "an", and "an" before a consonant "a". */
    private fun fixArticle(before: String, term: String): String {
        val article = ARTICLE.find(before) ?: return before
        val word = article.groupValues[1]
        // "u" is left alone: "a Uniqlo", "a uniform".
        val vowel = term.first().lowercaseChar() in "aeio"
        val fixed = when {
            vowel && word.length == 1 -> word + "n"
            !vowel && word.length == 2 -> word.substring(0, 1)
            else -> return before
        }
        return before.substring(0, article.range.first) + fixed + " "
    }

    private fun matches(entry: Entry, words: List<String>, cores: List<String>, start: Int): Boolean {
        val n = entry.words.size
        if (start + n > words.size) return false
        for (k in 0 until n) {
            // Only the last word of a match may carry punctuation.
            if (k < n - 1 && cores[start + k].length != words[start + k].length) return false
            val core = cores[start + k]
            val equal = if (entry.caseSensitive) core == entry.words[k] else core.equals(entry.words[k], ignoreCase = true)
            if (!equal) return false
        }
        return true
    }

    private companion object {
        const val FIRST_PLACEHOLDER = 101
        const val LETTER_PREFIX = "X"
        val TRAILING = charArrayOf('.', ',', '!', '?', ';', ':')
        val SENTENCE_END = setOf('.', '!', '?')
        val ARTICLE = Regex("\\b([Aa]n?) $")
    }
}
