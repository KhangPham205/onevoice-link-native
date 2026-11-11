package com.onevoice.link.core

/**
 * Aho-Corasick context graph for decode-time phrase biasing (the sherpa-onnx
 * "hotwords" idea), over RNN-T token ids.
 *
 * A hypothesis that follows a phrase prefix earns [Phrase.boost] per token.
 * The bonus is provisional: if the hypothesis leaves the prefix before the
 * phrase is complete, the unfinished part is taken back (the transition
 * returns a negative delta), so the bias cannot buy tokens that do not end in
 * a listed term. A completed phrase keeps its bonus. Both properties matter
 * because the bonus only ranks beam hypotheses; it never reaches the routing
 * score (see [RnntBeamSearch]).
 *
 * Known approximation: after a completed phrase whose node still has longer
 * continuations, an overlap between that phrase and the next match can be
 * counted twice. The bonus stays bounded by the two phrases' lengths.
 */
internal class ContextGraph(phrases: List<Phrase>) {
    class Phrase(val tokens: IntArray, val boost: Double) {
        init {
            require(tokens.isNotEmpty()) { "Bias phrase has no tokens" }
            require(boost.isFinite() && boost > 0.0) { "Bias boost must be positive, got $boost" }
        }
    }

    class Node internal constructor(val depth: Int) {
        internal val children = HashMap<Int, Node>(4)
        /** Provisional bonus held by a hypothesis sitting at this node. */
        var nodeScore = 0.0
            internal set
        /** Bonus of the deepest completed phrase on the root..this path. */
        var lockScore = 0.0
            internal set
        var isEnd = false
            internal set
        internal var fail: Node? = null
    }

    /** Immutable per-hypothesis bias state; total bonus = [committed] + node score. */
    class State internal constructor(val node: Node, val committed: Double) {
        val total: Double get() = committed + node.nodeScore
    }

    val root = Node(0)
    val phraseCount: Int = phrases.size
    val start = State(root, 0.0)

    init {
        for (phrase in phrases) {
            var node = root
            for (token in phrase.tokens) {
                val parentScore = node.nodeScore
                node = node.children.getOrPut(token) { Node(node.depth + 1) }
                // Phrases sharing a prefix with different boosts keep the larger one.
                node.nodeScore = maxOf(node.nodeScore, parentScore + phrase.boost)
            }
            node.isEnd = true
        }
        // Breadth-first: fail links and lock scores depend on shallower nodes.
        val queue = ArrayDeque<Node>()
        for (child in root.children.values) {
            child.fail = root
            child.lockScore = if (child.isEnd) child.nodeScore else 0.0
            queue.addLast(child)
        }
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            for ((token, child) in node.children) {
                child.lockScore = if (child.isEnd) child.nodeScore else node.lockScore
                child.fail = gotoFrom(node.fail, token)
                queue.addLast(child)
            }
        }
    }

    /** Longest suffix state that continues with [token], walking fail links from [from]. */
    private fun gotoFrom(from: Node?, token: Int): Node {
        var current = from
        while (current != null) {
            current.children[token]?.let { return it }
            current = current.fail
        }
        return root
    }

    /** Advances one emitted lexical token; returns the new state and the bonus delta. */
    fun step(state: State, token: Int): Pair<State, Double> {
        val node = state.node
        var committed = state.committed
        var next = node.children[token]
        if (next == null) {
            // Leaving the prefix: keep a completed phrase, drop the unfinished tail.
            committed += node.lockScore
            next = gotoFrom(node.fail, token)
        }
        if (next.isEnd && next.children.isEmpty()) {
            committed += next.nodeScore
            next = root
        }
        val updated = State(next, committed)
        return updated to (updated.total - state.total)
    }

    /** Bonus delta at the end of the utterance: unfinished phrase tails are taken back. */
    fun finish(state: State): Double = state.node.lockScore - state.node.nodeScore
}

/**
 * A compiled bias list (`asr_<model>_bias.txt`, written by
 * tools/models/asr-bias/compile_bias_list.py) resolved against the decoder's
 * own token table.
 *
 * Line format: `<boost|->\t<rewrite 0|1>\t<spoken>\t<display>\t<piece piece ...>`.
 */
internal class AsrBiasList private constructor(
    val phrases: List<ContextGraph.Phrase>,
    /** First word -> (spoken words, display); a null display is a guard that keeps the words. */
    private val rules: Map<String, List<Pair<List<String>, String?>>>,
    val ruleCount: Int,
    /** Terms translated with their library English instead of by Marian (`g` lines). */
    val glossary: MtGlossary,
    /** EN->VI: an English speaker's term and its Vietnamese display (`e` lines). */
    val reverseGlossary: MtGlossary = MtGlossary(emptyList()),
    /** First word -> every multi-word spoken term, rewritten or not, longest first. */
    private val terms: Map<String, List<List<String>>> = emptyMap(),
) {
    companion object {
        fun parse(
            lines: List<String>,
            pieceToId: Map<String, Int>,
            defaultBoost: Double,
        ): AsrBiasList {
            require(defaultBoost.isFinite() && defaultBoost > 0.0) {
                "Default bias boost must be positive, got $defaultBoost"
            }
            val phrases = ArrayList<ContextGraph.Phrase>()
            val rules = HashMap<String, MutableList<Pair<List<String>, String?>>>()
            val terms = HashMap<String, MutableList<List<String>>>()
            val glossary = ArrayList<MtGlossary.Entry>()
            val reverse = ArrayList<MtGlossary.Entry>()
            var ruleCount = 0
            lines.forEachIndexed { index, raw ->
                val line = raw.trimEnd('\r')
                if (line.isBlank() || line.startsWith("#")) return@forEachIndexed
                val cols = line.split('\t')
                if (cols[0] == "k") {
                    // k <TAB> <words>: a guard. As the longer match it keeps
                    // "chuyền năm nay" (this year) from becoming "chuyền 5 nay".
                    require(cols.size == 2 && cols[1].isNotBlank()) { "Bias line ${index + 1}: bad guard" }
                    val words = cols[1].lowercase().split(' ').filter(String::isNotEmpty)
                    rules.getOrPut(words.first()) { ArrayList() } += words to null
                    return@forEachIndexed
                }
                if (cols[0] == "g" || cols[0] == "e") {
                    // g|e <TAB> <case-sensitive 0|1> <TAB> <surface> <TAB> <translation>
                    require(cols.size == 4 && (cols[1] == "0" || cols[1] == "1") &&
                        cols[2].isNotBlank() && cols[3].isNotBlank()) {
                        "Bias line ${index + 1}: bad glossary entry"
                    }
                    (if (cols[0] == "g") glossary else reverse) += MtGlossary.Entry(
                        words = cols[2].split(' ').filter(String::isNotEmpty),
                        caseSensitive = cols[1] == "1",
                        english = cols[3],
                    )
                    return@forEachIndexed
                }
                require(cols.size == 5) { "Bias line ${index + 1}: expected 5 columns, got ${cols.size}" }
                val (boostText, rewriteText, spoken, display, pieceText) = cols
                val boost = if (boostText == "-") defaultBoost else boostText.toDoubleOrNull()
                    ?.takeIf { it.isFinite() && it > 0.0 }
                    ?: error("Bias line ${index + 1}: bad boost '$boostText'")
                require(rewriteText == "0" || rewriteText == "1") {
                    "Bias line ${index + 1}: rewrite must be 0/1"
                }
                // An unknown piece means the list was compiled for another
                // model; matching a partial phrase would bias the wrong tokens.
                val ids = pieceText.split(' ').filter(String::isNotEmpty).map { piece ->
                    pieceToId[piece] ?: error("Bias line ${index + 1}: piece '$piece' is not in the ASR vocabulary")
                }
                phrases += ContextGraph.Phrase(ids.toIntArray(), boost)
                val words = spoken.lowercase().split(' ').filter(String::isNotEmpty)
                if (words.size > 1) terms.getOrPut(words.first()) { ArrayList() } += words
                if (rewriteText == "1" && words.isNotEmpty() && words.joinToString(" ") != display) {
                    rules.getOrPut(words.first()) { ArrayList() } += words to display
                    ruleCount++
                }
            }
            rules.values.forEach { list -> list.sortByDescending { it.first.size } }
            terms.values.forEach { list -> list.sortByDescending { it.size } }
            return AsrBiasList(phrases, rules, ruleCount, MtGlossary(glossary), MtGlossary(reverse), terms)
        }
    }

    /**
     * Replaces spoken forms with their display forms: left to right, longest
     * match first, whole words only. Idempotent for a list that passed the
     * compiler, so applying it to the decoder transcript and again at MT input
     * is safe.
     */
    fun rewrite(text: String): String {
        if (rules.isEmpty() && terms.isEmpty()) return text
        val words = joinTerms(text.split(' ').filter(String::isNotEmpty))
        // A pause comma (PauseCommas) may sit on any word: match on the words
        // without it, keep the one after the term and drop one inside it
        // ("chuyền, năm" is still "chuyền 5").
        val cores = words.map { it.trimEnd(',', ';') }
        val lower = cores.map(String::lowercase)
        val out = ArrayList<String>(words.size)
        var i = 0
        while (i < words.size) {
            val match = rules[lower[i]]?.firstOrNull { (spoken, _) ->
                i + spoken.size <= words.size && spoken.indices.all { lower[i + it] == spoken[it] }
            }
            if (match != null) {
                val n = match.first.size
                val last = i + n - 1
                out += match.second?.let { it + words[last].substring(cores[last].length) }
                    ?: words.subList(i, i + n).joinToString(" ")
                i += n
            } else {
                out += words[i]
                i++
            }
        }
        return out.joinToString(" ")
    }

    /**
     * Drops a pause comma (PauseCommas) that fell inside a listed term, kept
     * or rewritten alike: "sơ đồ, cắt" is still the term "sơ đồ cắt".
     */
    private fun joinTerms(words: List<String>): List<String> {
        if (terms.isEmpty() || words.none { it.endsWith(',') || it.endsWith(';') }) return words
        val cores = words.map { it.trimEnd(',', ';').lowercase() }
        val out = words.toMutableList()
        var i = 0
        while (i < words.size) {
            val term = terms[cores[i]]?.firstOrNull { t ->
                i + t.size <= words.size && t.indices.all { cores[i + it] == t[it] }
            }
            if (term == null) {
                i++
                continue
            }
            for (k in i until i + term.size - 1) out[k] = out[k].trimEnd(',', ';')
            i += term.size
        }
        return out
    }
}
