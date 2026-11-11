package com.onevoice.link.core

/**
 * Punctuation for the VI ASR transcript before Marian (and on screen).
 *
 * GIPFormer emits lower-case words with no punctuation, so Marian reads a
 * whole turn as one run-on sentence: "dạ cổ áo được may..." came out as "The
 * stomach is...", and "dạ thứ năm em sẽ..." as "in the fifth dance". Marian
 * was trained on punctuated text, and [translateBySentence] splits at full
 * stops, so a few grammar rules recover much of that context:
 *
 * - an opening response ("dạ", "vâng", "có anh", "cảm ơn anh David") takes a
 *   comma, which turns "dạ" into "Yes" rather than "stomach";
 * - a clause conjunction ("nhưng", "còn", "trong lúc đó") takes a comma before it;
 * - a clause restarting with a pronoun subject ("em sẽ", "anh ấy") after at
 *   least three words takes a comma before it;
 * - the turn ends with "?" after a closing question word ("không", "chưa",
 *   "đâu"...), after an opening "ai", "tại sao", or when "bao nhiêu" / "bao
 *   lâu" stands anywhere; otherwise with "."
 * - the first letter is capitalised.
 *
 * On the 45 Vietnamese demo turns (synthetic voice) this raised VI->EN BLEU
 * from 20.0 to 22.3 and COMET from 0.650 to 0.684; copying the script's own
 * punctuation reached 21.8 / 0.697. Text that already has punctuation (a CPU
 * fallback result, a typed test string) is returned as is.
 */
internal object VietnamesePunctuation {
    private val RESPONSES = listOf(
        "dạ vâng", "dạ được", "dạ có", "dạ không", "dạ", "vâng", "ừ", "ạ",
        "có anh", "có chị", "được anh", "được chị", "đây anh", "đây chị", "không anh", "không chị",
        "chào anh", "chào chị", "cảm ơn anh", "cảm ơn chị",
    ).map { it.split(' ') }

    /** Greetings whose next word is the name being addressed: "chào anh David,". */
    private val VOCATIVE = setOf("chào anh", "chào chị", "cảm ơn anh", "cảm ơn chị")
    private val PRONOUNS = setOf("em", "anh", "chị")

    private val CONJUNCTIONS = listOf("trong lúc đó", "chủ yếu là", "sau đó", "nhưng", "còn", "nên", "rồi mới")
        .map { it.split(' ') }
    private val RESTARTS = listOf(
        "em sẽ", "em đã", "em đang", "em cũng", "anh ấy", "chị ấy", "tôi sẽ", "tôi đã", "bên em",
        "chúng tôi", "chúng em",
    ).map { it.split(' ') }

    private val QUESTION_ENDS = listOf(
        "bao nhiêu", "bao lâu", "khi nào", "phải không", "được không", "không", "chưa", "à", "hả",
        "nhỉ", "sao", "gì", "nào", "đâu", "mấy",
    ).map { it.split(' ') }
    private val QUESTION_STARTS = listOf("tại sao", "vì sao", "bao giờ", "ai").map { it.split(' ') }

    /** Asks wherever it stands: "chuyền 5 có bao nhiêu công nhân". */
    private val QUESTION_ANYWHERE = listOf("bao nhiêu", "bao lâu").map { it.split(' ') }

    private const val PUNCTUATION = ".,?!;:"

    fun punctuate(text: String): String {
        if (text.any { it in PUNCTUATION }) return text
        val words = text.split(' ').filter(String::isNotEmpty)
        if (words.isEmpty()) return text
        val low = words.map { it.lowercase() }
        val commas = HashSet<Int>() // a comma after this word index

        for (response in RESPONSES) {
            if (low.size > response.size && startsWith(low, 0, response)) {
                var end = response.size
                if (response.joinToString(" ") in VOCATIVE && low.size > end + 1 && low[end] !in PRONOUNS) {
                    end++ // "chào anh david, ..."
                }
                commas += end - 1
                break
            }
        }
        for (i in 2 until low.size) {
            val conjunction = CONJUNCTIONS.any { startsWith(low, i, it) }
            val restart = !conjunction && RESTARTS.any { startsWith(low, i, it) }
            if (!conjunction && !restart || (i - 1) in commas) continue
            // A restart needs a clause of its own before it.
            val last = commas.filter { it < i }.maxOrNull() ?: -1
            if (conjunction || i - last >= 4) commas += i - 1
        }

        val question = QUESTION_ENDS.any { low.size >= it.size && startsWith(low, low.size - it.size, it) } ||
            QUESTION_STARTS.any { startsWith(low, 0, it) } ||
            low.indices.any { i -> QUESTION_ANYWHERE.any { startsWith(low, i, it) } }
        val body = words.mapIndexed { i, w -> if (i in commas && i < words.lastIndex) "$w," else w }
            .joinToString(" ")
        val ended = body + if (question) "?" else "."
        return ended.replaceFirstChar(Char::uppercaseChar)
    }

    private fun startsWith(words: List<String>, at: Int, phrase: List<String>): Boolean =
        at + phrase.size <= words.size && phrase.indices.all { words[at + it] == phrase[it] }
}
