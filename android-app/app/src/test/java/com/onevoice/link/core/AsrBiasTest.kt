package com.onevoice.link.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ln

class AsrBiasTest {
    private val eps = 1e-9

    // ---- ContextGraph -----------------------------------------------------

    private fun graph(vararg phrases: IntArray, boost: Double = 1.0) =
        ContextGraph(phrases.map { ContextGraph.Phrase(it, boost) })

    private fun run(g: ContextGraph, tokens: IntArray): Pair<ContextGraph.State, Double> {
        var state = g.start
        var total = 0.0
        for (t in tokens) {
            val (next, delta) = g.step(state, t)
            state = next
            total += delta
        }
        return state to total
    }

    @Test
    fun completedPhraseKeepsItsBonus() {
        val g = graph(intArrayOf(5, 6, 7))
        val (state, total) = run(g, intArrayOf(5, 6, 7))
        assertEquals(3.0, total + g.finish(state), eps)
    }

    @Test
    fun abandonedPrefixIsTakenBack() {
        val g = graph(intArrayOf(5, 6, 7))
        val (state, total) = run(g, intArrayOf(5, 6, 9))
        assertEquals(0.0, total + g.finish(state), eps)
    }

    @Test
    fun unfinishedPhraseAtUtteranceEndIsTakenBack() {
        val g = graph(intArrayOf(5, 6, 7))
        val (state, total) = run(g, intArrayOf(5, 6))
        assertEquals(2.0, total, eps)
        assertEquals(0.0, total + g.finish(state), eps)
    }

    @Test
    fun failLinkRestartsAMatchInsideAnAbandonedOne() {
        // "5 5 6" must still complete "5 6" after the first 5 failed to continue.
        val g = graph(intArrayOf(5, 6, 7), intArrayOf(5, 6))
        val (state, total) = run(g, intArrayOf(5, 9, 5, 6, 9))
        assertEquals(2.0, total + g.finish(state), eps)
    }

    @Test
    fun shorterPhraseIsKeptWhenALongerOneFails() {
        // "sai em" completes; "sai em mờ" is abandoned at the next token.
        val g = graph(intArrayOf(1, 2), intArrayOf(1, 2, 3))
        val (state, total) = run(g, intArrayOf(1, 2, 9))
        assertEquals(2.0, total + g.finish(state), eps)
    }

    @Test
    fun phraseIsRewardedEachTimeItOccurs() {
        val g = graph(intArrayOf(5, 6))
        val (state, total) = run(g, intArrayOf(5, 6, 1, 5, 6))
        assertEquals(4.0, total + g.finish(state), eps)
    }

    // ---- AsrBiasList ------------------------------------------------------

    private val pieces = mapOf("▁SAI" to 10, "▁ÍCH" to 11, "▁E" to 12, "O" to 13, "▁EM" to 14, "▁M" to 15, "Ờ" to 16, "▁MÁY" to 17, "▁VẮT" to 18, "▁SỔ" to 19)

    private val compiled = listOf(
        "# onevoice-asr-bias v1",
        "-\t1\tsai ích eo\tsize XL\t▁SAI ▁ÍCH ▁E O",
        "-\t1\tsai em\tsize M\t▁SAI ▁EM",
        "2.5\t1\tsai em mờ\tsize M\t▁SAI ▁EM ▁M Ờ",
        "-\t0\tmáy vắt sổ\tmáy vắt sổ\t▁MÁY ▁VẮT ▁SỔ",
    )

    @Test
    fun parsesPhrasesWithDefaultAndExplicitBoost() {
        val list = AsrBiasList.parse(compiled, pieces, 1.5)
        assertEquals(4, list.phrases.size)
        assertEquals(listOf(10, 11, 12, 13), list.phrases[0].tokens.toList())
        assertEquals(1.5, list.phrases[0].boost, eps)
        assertEquals(2.5, list.phrases[2].boost, eps)
        assertEquals(3, list.ruleCount) // the rewrite=0 row only biases
    }

    @Test(expected = IllegalStateException::class)
    fun unknownPieceFailsTheLoad() {
        AsrBiasList.parse(listOf("-\t1\tgiu ki\tJuki\t▁GIU ▁KI"), pieces, 1.5)
    }

    @Test
    fun rewriteUsesLongestWholeWordMatchAndIsIdempotent() {
        val list = AsrBiasList.parse(compiled, pieces, 1.5)
        val once = list.rewrite("lấy áo sai em mờ và sai ích eo nhé")
        assertEquals("lấy áo size M và size XL nhé", once)
        assertEquals(once, list.rewrite(once))
        // No partial-word or partial-phrase rewrite.
        assertEquals("sai ích", list.rewrite("sai ích"))
        assertEquals("máy vắt sổ", list.rewrite("máy vắt sổ"))
    }

    // ---- RnntBeamSearch ---------------------------------------------------

    private val vocab = 8
    private val blank = 0
    private val unk = 2
    private val sosEos = 1

    /** Joint whose scores depend on the frame only: rows[frame][token] probabilities. */
    private fun joint(vararg rows: DoubleArray) = RnntJoint { frame, contexts ->
        val logs = FloatArray(vocab) { id -> ln(rows[frame][id].coerceAtLeast(1e-12)).toFloat() }
        contexts.map { logs.copyOf() }
    }

    private fun probs(vararg pairs: Pair<Int, Double>): DoubleArray {
        val p = DoubleArray(vocab) { 1e-6 }
        pairs.forEach { (id, v) -> p[id] = v }
        return p
    }

    private fun search(beam: Int, g: ContextGraph? = null) =
        RnntBeamSearch(vocab, blank, unk, sosEos, beam, g)

    @Test
    fun beamOneWithoutBiasIsGreedy() {
        val j = joint(
            probs(5 to 0.6, blank to 0.3),
            probs(blank to 0.7, 6 to 0.2),
            probs(sosEos to 0.5, blank to 0.4), // control symbol: context only
            probs(6 to 0.9),
        )
        val r = search(1).search(4, j)
        assertEquals(listOf(5, 6), r.tokens)
        assertEquals(listOf(0, 3), r.frames)
        assertEquals(ln(0.6f.toDouble()) + ln(0.9f.toDouble()), r.lexicalLogProb, 1e-5)
        assertEquals(0.0, r.biasScore, eps)
    }

    @Test
    fun biasFlipsANearTieTowardTheListedTermButRoutingScoreStaysAcoustic() {
        // Acoustically 3 narrowly beats 4 on frame 1; the list holds "5 4".
        val j = joint(probs(5 to 0.9), probs(3 to 0.46, 4 to 0.44))
        val plain = search(4).search(2, j)
        assertEquals(listOf(5, 3), plain.tokens)

        val biased = search(4, graph(intArrayOf(5, 4), boost = 0.5)).search(2, j)
        assertEquals(listOf(5, 4), biased.tokens)
        // Routing sees the joiner's own log-probabilities, not the bonus.
        assertEquals(ln(0.9) + ln(0.44), biased.lexicalLogProb, 1e-5)
        assertEquals(1.0, biased.biasScore, eps)
        assertTrue(biased.lexicalLogProb < plain.lexicalLogProb)
    }

    @Test
    fun biasCannotOverrideAClearAcousticWinner() {
        val j = joint(probs(5 to 0.9), probs(3 to 0.95, 4 to 0.01))
        val r = search(4, graph(intArrayOf(5, 4), boost = 1.0)).search(2, j)
        assertEquals(listOf(5, 3), r.tokens)
        assertEquals(0.0, r.biasScore, eps)
    }

    @Test
    fun unfinishedPhraseDoesNotWinOnProvisionalBonus() {
        // Only the first two tokens of "5 4 7" are spoken: the final pick must
        // be made on acoustic score after the unfinished bonus is taken back.
        val j = joint(probs(5 to 0.9), probs(3 to 0.46, 4 to 0.44))
        val r = search(4, graph(intArrayOf(5, 4, 7), boost = 0.5)).search(2, j)
        assertEquals(listOf(5, 3), r.tokens)
        assertTrue(abs(r.biasScore) < eps)
    }
}
