package com.onevoice.link.core

import java.nio.ShortBuffer
import kotlin.math.roundToInt

/**
 * QNN SCALE_OFFSET encoding of a UFIXED_POINT_16 tensor:
 * `real = scale * (q + offset)`, with `q` in 0..65535.
 */
data class Q16Params(val scale: Float, val offset: Int) {
    init {
        require(scale.isFinite() && scale > 0f) { "Q16 scale must be finite and positive" }
    }

    fun quantize(value: Float): Int =
        ((value / scale).roundToInt() - offset).coerceIn(0, U16_MAX)

    fun dequantize(raw: Short): Float = scale * ((raw.toInt() and U16_MAX) + offset)

    companion object {
        const val U16_MAX = 0xFFFF
    }
}

/**
 * Fixed contract of sherpa-onnx's SM8550 Parakeet-TDT-CTC-110M (CTC head)
 * context binary: normalized NeMo log-mel `x [1,1000,80]` in, CTC
 * `log_probs [1,125,1025]` out, both UFIXED_POINT_16. The graph already applies
 * log-softmax, so a dequantized value is a per-frame log probability.
 */
internal object ParakeetCtcContract {
    const val FEATURE_FRAMES = 1000
    const val FEATURE_DIM = 80
    const val OUTPUT_FRAMES = 125
    const val VOCAB_SIZE = 1025
    const val BLANK_ID = 1024
    const val SUBSAMPLING = FEATURE_FRAMES / OUTPUT_FRAMES
}

/** Greedy CTC result: lexical token ids plus the evidence the router compares. */
internal data class CtcScoredIds(
    val ids: List<Int>,
    /** Sum over emitted lexical tokens of each token's peak frame log-probability. */
    val rawLogProb: Double,
    /** Sum over every decoded frame (blank included) of its argmax log-probability. */
    val frameLogProbSum: Double = 0.0,
    /** Decoded frames whose argmax was not blank. */
    val nonBlankFrames: Int = 0,
    val frames: Int = 0,
)

/**
 * Greedy CTC over quantized log-probabilities without dequantizing every logit:
 * scale is positive, so the raw uint16 argmax is the real argmax.
 *
 * A token occupying several adjacent frames is one emission; its confidence is
 * the peak frame log-probability of that run, mirroring the RNN-T decoder's one
 * log-softmax term per emitted token. [skippedIds] (e.g. `<unk>`) are neither
 * emitted nor scored, but still break an adjacent-repeat run like any symbol.
 */
internal fun ctcGreedyDecodeQ16(
    logProbs: ShortBuffer,
    frames: Int,
    vocabSize: Int,
    blankId: Int,
    params: Q16Params,
    skippedIds: Set<Int> = emptySet(),
): CtcScoredIds {
    require(frames >= 0 && frames * vocabSize <= logProbs.limit()) {
        "CTC output holds ${logProbs.limit()} values, need $frames x $vocabSize"
    }
    val ids = ArrayList<Int>()
    val scores = ArrayList<Float>()
    var frameSum = 0.0
    var nonBlank = 0
    var previous = -1
    for (frame in 0 until frames) {
        val base = frame * vocabSize
        var best = 0
        var bestRaw = -1
        for (id in 0 until vocabSize) {
            val raw = logProbs.get(base + id).toInt() and Q16Params.U16_MAX
            if (raw > bestRaw) {
                bestRaw = raw
                best = id
            }
        }
        val logProb = params.dequantize(bestRaw.toShort())
        frameSum += logProb
        if (best != blankId) nonBlank++
        if (best != blankId && best !in skippedIds) {
            if (best != previous) {
                ids += best
                scores += logProb
            } else if (scores.isNotEmpty() && logProb > scores.last()) {
                scores[scores.lastIndex] = logProb
            }
        }
        previous = best
    }
    return CtcScoredIds(
        ids = ids,
        rawLogProb = scores.sumOf { it.toDouble() },
        frameLogProbSum = frameSum,
        nonBlankFrames = nonBlank,
        frames = frames,
    )
}

/** SentencePiece pieces -> text; `▁` marks a word start. */
internal fun detokenizeSentencePiece(ids: List<Int>, pieces: List<String>): String =
    buildString { ids.forEach { id -> pieces.getOrNull(id)?.let(::append) } }
        .replace('▁', ' ')
        .trim()
