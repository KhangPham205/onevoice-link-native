package com.onevoice.link.core

/**
 * Punctuation for the VI ASR transcript before Marian (and on screen).
 *
 * GIPFormer emits lower-case words with no punctuation, so Marian reads a
 * whole turn as one run-on sentence: "dạ cổ áo được may..." came out as "The
 * stomach is...", and "dạ thứ năm em sẽ..." as "in the fifth dance". Marian
 * was trained on punctuated text, [translateBySentence] splits at "." and
 * "?", and a question is translated as one ("Anh kiểm tra chưa?" -> "Have you
 * checked?"), so:
 *
 * Boundaries
 * - a speaker pause ([PauseCommas]) arrives as "," and a sentence-length one
 *   as ";" (a mark only, turned into "." or "?" here);
 * - an opening response ("dạ", "vâng", "có anh", "cảm ơn anh David") takes a
 *   comma, which turns "dạ" into "Yes" rather than "stomach";
 * - a clause conjunction ("nhưng", "còn", "trong lúc đó") takes a comma before it;
 * - a clause restarting with a pronoun subject ("em sẽ", "anh ấy") after at
 *   least three words takes a comma before it.
 *
 * Questions (Vietnamese marks them with words; its tones leave intonation
 * unreliable), checked per sentence on its last clause after dropping a
 * trailing address or softener ("chưa ạ", "không anh", "vậy"):
 * - a closing particle: "không", "chưa", "à", "hả", "nhỉ", "chứ", "phải
 *   không", "được không" (not a bare "Không." / "Em chưa." answer, nor "cũng
 *   không"); such a clause also ends a sentence at a comma ("... không, em mang
 *   ra" -> "...không? Em mang ra.");
 * - a closing question word: "gì", "nào", "đâu", "ai", "mấy", "thế nào", "ở
 *   đâu", "bao giờ"... unless a negation precedes it ("không có gì đâu") or a
 *   verb embeds it ("em không biết khi nào...");
 * - an opening "ai", "tại sao", "bao giờ", "khi nào"... unless "cũng" follows
 *   ("ai cũng phải") or, for "khi nào"/"bao giờ", the sentence goes on after a
 *   comma or with "thì" ("khi nào xong, em báo");
 * - "bao nhiêu" / "bao lâu" anywhere, "mấy" before a counted noun;
 * - an alternative "X hay X": "bên trái hay bên phải".
 * Every other sentence ends with "."; each sentence starts with a capital.
 *
 * On the 45 Vietnamese demo turns (synthetic voice) the first version raised
 * VI->EN BLEU from 20.0 to 22.3 and COMET from 0.650 to 0.684; with pause
 * commas COMET reached 0.695 (the script's own punctuation: 0.697). Text that
 * already ends sentences (a CPU fallback result, a typed test string) is
 * returned as is.
 */
internal object VietnamesePunctuation {
    private val RESPONSES = listOf(
        "dạ vâng", "dạ được", "dạ có", "dạ không", "dạ", "vâng", "ừ", "ạ",
        "có anh", "có chị", "được anh", "được chị", "đây anh", "đây chị", "không anh", "không chị",
        "chào anh", "chào chị", "cảm ơn anh", "cảm ơn chị",
    ).map(::words)

    /** Greetings whose next word is the name being addressed: "chào anh David,". */
    private val VOCATIVE = setOf("chào anh", "chào chị", "cảm ơn anh", "cảm ơn chị")
    private val PRONOUNS = setOf("em", "anh", "chị")

    /** A question clause opening with the listener: "anh có cần...", "bên anh dùng...". */
    private val ADDRESSED = setOf("anh", "chị", "bên")

    private val CONJUNCTIONS = listOf("trong lúc đó", "chủ yếu là", "sau đó", "nhưng", "còn", "nên", "rồi mới")
        .map(::words)
    private val RESTARTS = listOf(
        "em sẽ", "em đã", "em đang", "em cũng", "anh ấy", "chị ấy", "tôi sẽ", "tôi đã", "bên em",
        "chúng tôi", "chúng em",
    ).map(::words)

    private val CLOSING_PARTICLES = listOf(
        "phải không", "được không", "đúng không", "có không", "không", "chưa", "à", "hả", "hở", "nhỉ", "chứ",
    ).map(::words)
    private val CLOSING_WH = listOf(
        "thế nào", "ra sao", "bao nhiêu", "bao lâu", "bao giờ", "khi nào", "ở đâu", "làm sao", "tại sao", "vì sao",
        "gì", "nào", "đâu", "ai", "mấy", "sao",
    ).map(::words)
    private val OPENING_WH = listOf("tại sao", "vì sao", "bao giờ", "khi nào", "bao nhiêu", "làm sao", "ai", "sao")
        .map(::words)
    private val CONDITIONAL_WH = setOf("bao giờ", "khi nào")
    private val ANYWHERE_WH = listOf("bao nhiêu", "bao lâu").map(::words)

    /** Address and softeners after the question word: "chưa ạ", "không anh", "vậy". */
    private val TAIL = setOf("anh", "chị", "em", "ạ", "vậy", "thế", "ơi")
    private val NEGATIONS = setOf("không", "chẳng", "chả", "chưa")

    /** Verbs that embed a question in a statement: "em không biết khi nào vải về". */
    private val EMBEDDING = setOf("biết", "hỏi", "xem", "nhớ", "báo", "hiểu")

    /** Nouns "mấy" counts in a question: "đóng mấy cái áo". */
    private val COUNTED = setOf(
        "cái", "người", "giờ", "ngày", "tiếng", "thùng", "chiếc", "máy", "lần", "phút", "tuần", "tháng", "bó",
        "size", "mũi", "công", "chuyền", "tổ",
    )

    /** Text already ended by someone else (CPU fallback, a typed string) is left alone. */
    private const val EXTERNAL = ".?!:"

    fun punctuate(text: String): String {
        if (text.any { it in EXTERNAL }) return text
        val raw = text.split(' ').filter(String::isNotEmpty)
        if (raw.isEmpty()) return text
        val words = raw.map { it.trimEnd(',', ';') }
        val low = words.map { it.lowercase() }
        // Boundary after word i: ',' a clause, ';' a sentence (from PauseCommas).
        val marks = HashMap<Int, Char>()
        raw.forEachIndexed { i, w ->
            if (w.endsWith(';')) marks[i] = ';' else if (w.endsWith(',')) marks[i] = ','
        }

        var responseEnd = -1
        for (response in RESPONSES) {
            if (low.size > response.size && startsWith(low, 0, response)) {
                var end = response.size
                if (response.joinToString(" ") in VOCATIVE && low.size > end + 1 && low[end] !in PRONOUNS) {
                    end++ // "chào anh david, ..."
                }
                responseEnd = end - 1
                marks.putIfAbsent(responseEnd, ',')
                break
            }
        }
        for (i in 2 until low.size) {
            val conjunction = CONJUNCTIONS.any { startsWith(low, i, it) }
            val restart = !conjunction && RESTARTS.any { startsWith(low, i, it) }
            if (!conjunction && !restart || (i - 1) in marks) continue
            // A restart needs a clause of its own before it.
            val last = marks.keys.filter { it < i }.maxOrNull() ?: -1
            if (conjunction || i - last >= 4) marks[i - 1] = ','
        }

        val ends = HashMap<Int, Char>()
        var sentenceStart = 0
        var clauseStart = 0
        for (i in low.indices) {
            val mark = if (i == low.lastIndex) ';' else marks[i] ?: continue
            val clause = low.subList(clauseStart, i + 1)
            val inResponse = i <= responseEnd
            if (mark == ';') {
                val sentence = low.subList(sentenceStart, i + 1)
                val bodyStart = if (responseEnd in sentenceStart until i) responseEnd + 1 - sentenceStart else 0
                val firstComma = (sentenceStart + bodyStart until i).firstOrNull { marks[it] == ',' }
                val firstClause = (firstComma ?: i) - (sentenceStart + bodyStart) + 1
                ends[i] = if (!inResponse && isQuestion(sentence, bodyStart, clause, firstClause)) '?' else '.'
                sentenceStart = i + 1
            } else if (!inResponse && closesWithParticle(clause)) {
                ends[i] = '?'
                sentenceStart = i + 1
            } else {
                ends[i] = ','
            }
            clauseStart = i + 1
        }
        splitBeforeAddressedQuestions(low, ends, responseEnd)

        return buildString {
            var capital = true
            words.forEachIndexed { i, word ->
                if (i > 0) append(' ')
                append(if (capital) word.replaceFirstChar(Char::uppercaseChar) else word)
                val end = ends[i]
                if (end != null) append(end)
                capital = end == '.' || end == '?'
            }
        }
    }

    /**
     * "Hôm nay vắng hai người, anh có cần thêm không?" is a statement and then
     * a question to the listener: the comma before a question clause opening
     * with "anh"/"chị"/"bên anh" becomes a full stop when at least three words
     * of statement precede it, so Marian does not fold both into one question
     * ("You need more workers today?").
     */
    private fun splitBeforeAddressedQuestions(low: List<String>, ends: HashMap<Int, Char>, responseEnd: Int) {
        var start = 0
        for (i in low.indices) {
            val end = ends[i] ?: continue
            if (end == '?') {
                val comma = (i - 1 downTo start).firstOrNull { ends[it] == ',' }
                if (comma != null && low.getOrNull(comma + 1) in ADDRESSED &&
                    comma - maxOf(start, responseEnd + 1) + 1 >= 3
                ) {
                    ends[comma] = '.'
                }
            }
            if (end == '.' || end == '?') start = i + 1
        }
    }

    private fun isQuestion(
        sentence: List<String>,
        bodyStart: Int,
        lastClause: List<String>,
        firstClause: Int,
    ): Boolean {
        val clause = withoutTail(lastClause)
        if (clause.isEmpty()) return false
        if (closesWithParticle(lastClause) || closesWithWh(clause)) return true
        val body = sentence.subList(bodyStart, sentence.size)
        return opensWithWh(body, firstClause) || asksAnywhere(body) ||
            asksAlternative(clause)
    }

    private fun closesWithParticle(rawClause: List<String>): Boolean {
        val clause = withoutTail(rawClause)
        val particle = CLOSING_PARTICLES.firstOrNull { endsWith(clause, it) } ?: return false
        if (particle.size == 1 && particle[0] in setOf("không", "chưa")) {
            // "Không." / "Em chưa." answer; "em cũng không" is "me neither".
            if (clause.size < 2 || clause.size == 2 && clause[0] in setOf("em", "tôi", "cũng")) return false
            if (clause[clause.size - 2] == "cũng") return false
        }
        return true
    }

    private fun closesWithWh(clause: List<String>): Boolean {
        val wh = CLOSING_WH.firstOrNull { endsWith(clause, it) } ?: return false
        val before = clause.subList(0, clause.size - wh.size)
        return before.none { it in NEGATIONS || it in EMBEDDING }
    }

    /**
     * [firstClause]: the words of the body up to its first comma. "Khi nào xong,
     * em báo" is a condition; "Bao giờ, anh quay lại?" (a pause right after the
     * question word) is still a question.
     */
    private fun opensWithWh(body: List<String>, firstClause: Int): Boolean {
        val wh = OPENING_WH.firstOrNull { startsWith(body, 0, it) } ?: return false
        if (body.drop(wh.size).take(3).contains("cũng")) return false // "ai cũng phải..."
        val conditional = firstClause > wh.size && firstClause < body.size || "thì" in body
        if (wh.joinToString(" ") in CONDITIONAL_WH && conditional) return false
        return true
    }

    private fun asksAnywhere(body: List<String>): Boolean = body.indices.any { i ->
        val cue = ANYWHERE_WH.any { startsWith(body, i, it) } ||
            i > 0 && body[i] == "mấy" && body.getOrNull(i + 1) in COUNTED
        cue && body.subList(0, i).none { it in NEGATIONS || it in EMBEDDING } && body.getOrNull(i + 2) != "cũng"
    }

    /** "bên trái hay bên phải": "hay" between two parallel phrases. */
    private fun asksAlternative(clause: List<String>): Boolean = (1 until clause.size - 1).any { i ->
        clause[i] == "hay" && clause[i + 1] in clause.subList(maxOf(0, i - 3), i)
    }

    private fun withoutTail(clause: List<String>): List<String> {
        var end = clause.size
        while (end > 1 && end > clause.size - 2 && clause[end - 1] in TAIL) end--
        return clause.subList(0, end)
    }

    private fun words(phrase: String): List<String> = phrase.split(' ')

    private fun startsWith(words: List<String>, at: Int, phrase: List<String>): Boolean =
        at + phrase.size <= words.size && phrase.indices.all { words[at + it] == phrase[it] }

    private fun endsWith(words: List<String>, phrase: List<String>): Boolean =
        words.size >= phrase.size && startsWith(words, words.size - phrase.size, phrase)
}
