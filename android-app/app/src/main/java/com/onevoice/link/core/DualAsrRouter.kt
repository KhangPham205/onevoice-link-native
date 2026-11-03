package com.onevoice.link.core

import kotlin.math.abs

/** Why a bounded utterance did, or did not, select a translation branch. */
internal enum class RoutingDecisionReason(val logValue: String) {
    MARGIN("margin"),
    TURN_PRIOR("prior"),
    /** Below the margin, but the score lead outweighed the turn prior. */
    SOFT_MARGIN("soft_margin"),
    FLOOR("floor"),
    NO_PRIOR("no_prior"),
    ASR_FAILURE("asr_failure"),
    LID_ACCEPTED("lid"),
    LID_REJECTED("lid_rejected"),
    EXPLICIT("explicit"),
}

internal data class DualAsrDecision(
    val direction: TranslationDirection?,
    /** Direction expected from alternation before looking at this utterance. */
    val priorExpected: TranslationDirection?,
    val reason: RoutingDecisionReason,
    val normalizedMargin: Double,
)

/**
 * Pure decision policy for dual-ASR routing.
 *
 * The two scores are average emitted-token log probabilities. They are already
 * length-normalized by the RNN-T decoders; this class deliberately does not
 * apply another sequence-length or vocabulary-size correction. The current VI
 * and EN vocabularies differ substantially, so the configured thresholds are
 * provisional and must be calibrated from the structured device logs.
 *
 * Below the margin the turn prior is evidence, not a verdict: it shifts the
 * score difference by [RoutingConfig.priorWeight]. A hard prior discarded real
 * score leads and, once one same-speaker turn put it out of phase, kept every
 * later ambiguous turn wrong.
 */
internal class DualAsrTurnRouter(private val config: RoutingConfig) {
    /** Direction expected for the next utterance, from the last committed one. */
    private var expected: TranslationDirection? = null

    @Synchronized
    fun decide(viNormalized: Double, enNormalized: Double): DualAsrDecision {
        val vi = viNormalized.takeIf(Double::isFinite) ?: Double.NEGATIVE_INFINITY
        // Calibrated EN score: margin, floor and prior all see the same value.
        val en = enNormalized.takeIf(Double::isFinite)?.plus(config.enScoreOffset)
            ?: Double.NEGATIVE_INFINITY
        val prior = expected

        if (vi < config.floorThreshold && en < config.floorThreshold) {
            return DualAsrDecision(
                direction = null,
                priorExpected = prior,
                reason = RoutingDecisionReason.FLOOR,
                normalizedMargin = finiteMargin(vi, en),
            )
        }

        val margin = finiteMargin(vi, en)
        if (margin >= config.marginThreshold) {
            return DualAsrDecision(
                direction = if (vi >= en) {
                    TranslationDirection.VI_TO_EN
                } else {
                    TranslationDirection.EN_TO_VI
                },
                priorExpected = prior,
                reason = RoutingDecisionReason.MARGIN,
                normalizedMargin = margin,
            )
        }

        if (prior == null) {
            return DualAsrDecision(
                direction = null,
                priorExpected = null,
                reason = RoutingDecisionReason.NO_PRIOR,
                normalizedMargin = margin,
            )
        }
        // Finite here: an infinite difference always clears the margin above.
        val adjusted = (vi - en) + when (prior) {
            TranslationDirection.VI_TO_EN -> config.priorWeight
            TranslationDirection.EN_TO_VI -> -config.priorWeight
        }
        val direction = when {
            adjusted > 0.0 -> TranslationDirection.VI_TO_EN
            adjusted < 0.0 -> TranslationDirection.EN_TO_VI
            else -> prior
        }
        return DualAsrDecision(
            direction = direction,
            priorExpected = prior,
            reason = if (direction == prior) {
                RoutingDecisionReason.TURN_PRIOR
            } else {
                RoutingDecisionReason.SOFT_MARGIN
            },
            normalizedMargin = margin,
        )
    }

    /**
     * Commit only a successfully translated turn. People alternate after a
     * natural pause, but a forced 10-second boundary cut someone mid-thought:
     * after its translation plays, that same person most likely continues.
     */
    @Synchronized
    fun commit(direction: TranslationDirection, forcedBoundary: Boolean) {
        expected = if (forcedBoundary) direction else direction.opposite()
    }

    @Synchronized
    fun expectedNext(): TranslationDirection? = expected

    private fun finiteMargin(a: Double, b: Double): Double {
        if (!a.isFinite() && !b.isFinite()) return 0.0
        return abs(a - b).takeIf(Double::isFinite) ?: Double.POSITIVE_INFINITY
    }
}

internal fun TranslationDirection.opposite(): TranslationDirection = when (this) {
    TranslationDirection.VI_TO_EN -> TranslationDirection.EN_TO_VI
    TranslationDirection.EN_TO_VI -> TranslationDirection.VI_TO_EN
}
