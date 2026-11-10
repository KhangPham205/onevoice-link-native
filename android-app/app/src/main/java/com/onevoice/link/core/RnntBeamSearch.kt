package com.onevoice.link.core

/**
 * Joint network seen by the beam search: for one encoder frame and a batch of
 * decoder contexts (last two token ids each), return one log-softmax row per
 * context.
 */
internal fun interface RnntJoint {
    fun logProbs(frame: Int, contexts: List<LongArray>): List<FloatArray>
}

internal class RnntBeamResult(
    /** Emitted lexical token ids (no blank, unknown or `<sos/eos>`). */
    val tokens: List<Int>,
    /** Encoder frame of each entry in [tokens]. */
    val frames: List<Int>,
    /** Acoustic log-probability of [tokens] only: the routing score. */
    val lexicalLogProb: Double,
    /** Context-graph bonus of the chosen path, for logs. Never part of routing. */
    val biasScore: Double,
)

/**
 * sherpa-style modified beam search: at most one symbol per encoder frame,
 * hypotheses with an identical symbol sequence merged by maximum (Viterbi),
 * ranked by acoustic log-probability plus the optional [ContextGraph] bonus.
 *
 * Two scores are kept per hypothesis on purpose. The ranking score includes the
 * bias so a listed term can win a near tie. The reported [RnntBeamResult.
 * lexicalLogProb] is the joiner's own log-probability of the emitted lexical
 * tokens, exactly what greedy decoding reports, so dual-ASR routing never sees
 * the bonus. A path the bias forced away from the acoustic argmax therefore
 * scores lower for routing, not higher.
 *
 * With beam 1 and no graph this reproduces greedy decoding token for token,
 * including the control-symbol handling of [ZipformerFamilyRnntDecoder].
 */
internal class RnntBeamSearch(
    private val vocabSize: Int,
    private val blankId: Int,
    private val unkId: Int,
    /** `<sos/eos>`: advances the decoder context but is never emitted text. */
    private val sosEosId: Int,
    private val beam: Int,
    private val graph: ContextGraph?,
) {
    init {
        require(beam in 1..16) { "Beam must be 1..16, got $beam" }
    }

    val beamWidth: Int get() = beam

    private class Hyp(
        val sequence: List<Int>,
        val tokens: List<Int>,
        val frames: List<Int>,
        val c0: Long,
        val c1: Long,
        val acoustic: Double,
        val lexical: Double,
        val bias: ContextGraph.State?,
    ) {
        val score: Double get() = acoustic + (bias?.total ?: 0.0)
    }

    fun search(validFrames: Int, joint: RnntJoint): RnntBeamResult {
        var hyps = listOf(
            Hyp(emptyList(), emptyList(), emptyList(), -1L, blankId.toLong(), 0.0, 0.0, graph?.start),
        )
        val topIds = IntArray(beam)
        val topValues = FloatArray(beam)
        repeat(validFrames) { frame ->
            val rows = joint.logProbs(frame, hyps.map { longArrayOf(it.c0, it.c1) })
            check(rows.size == hyps.size) { "Joint returned ${rows.size} rows for ${hyps.size} contexts" }
            val merged = LinkedHashMap<List<Int>, Hyp>(hyps.size * (beam + 1))
            fun offer(candidate: Hyp) {
                val existing = merged[candidate.sequence]
                if (existing == null || candidate.score > existing.score) {
                    merged[candidate.sequence] = candidate
                }
            }
            hyps.forEachIndexed { index, hyp ->
                val row = rows[index]
                require(row.size >= vocabSize) { "Joint row has ${row.size} < $vocabSize scores" }
                // Blank and unknown are both non-emitting (sherpa semantics).
                val stay = maxOf(row[blankId], row[unkId]).toDouble()
                offer(Hyp(hyp.sequence, hyp.tokens, hyp.frames, hyp.c0, hyp.c1,
                    hyp.acoustic + stay, hyp.lexical, hyp.bias))
                val found = topK(row, topIds, topValues)
                for (rank in 0 until found) {
                    val token = topIds[rank]
                    val logProb = topValues[rank].toDouble()
                    val lexical = token != sosEosId
                    val bias = if (lexical && hyp.bias != null) {
                        checkNotNull(graph).step(hyp.bias, token).first
                    } else {
                        hyp.bias
                    }
                    offer(
                        Hyp(
                            sequence = hyp.sequence + token,
                            tokens = if (lexical) hyp.tokens + token else hyp.tokens,
                            frames = if (lexical) hyp.frames + frame else hyp.frames,
                            c0 = hyp.c1,
                            c1 = token.toLong(),
                            acoustic = hyp.acoustic + logProb,
                            lexical = if (lexical) hyp.lexical + logProb else hyp.lexical,
                            bias = bias,
                        ),
                    )
                }
            }
            hyps = merged.values.sortedByDescending(Hyp::score).take(beam)
        }
        val best = hyps.maxBy { hyp -> hyp.score + (hyp.bias?.let { checkNotNull(graph).finish(it) } ?: 0.0) }
        val biasScore = best.bias?.let { it.total + checkNotNull(graph).finish(it) } ?: 0.0
        return RnntBeamResult(best.tokens, best.frames, best.lexical, biasScore)
    }

    /** Fills the top [beam] non-blank, non-unknown finite entries; returns how many. */
    private fun topK(row: FloatArray, ids: IntArray, values: FloatArray): Int {
        var count = 0
        for (id in 0 until vocabSize) {
            if (id == blankId || id == unkId) continue
            val value = row[id]
            if (!value.isFinite()) continue
            if (count < ids.size) {
                var slot = count++
                while (slot > 0 && values[slot - 1] < value) {
                    ids[slot] = ids[slot - 1]; values[slot] = values[slot - 1]; slot--
                }
                ids[slot] = id; values[slot] = value
            } else if (value > values[count - 1]) {
                var slot = count - 1
                while (slot > 0 && values[slot - 1] < value) {
                    ids[slot] = ids[slot - 1]; values[slot] = values[slot - 1]; slot--
                }
                ids[slot] = id; values[slot] = value
            }
        }
        return count
    }
}
