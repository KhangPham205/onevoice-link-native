package com.onevoice.link.core

import kotlin.math.abs

/** Why a bounded utterance did, or did not, select a translation branch. */
internal enum class RoutingDecisionReason(val logValue: String) {
    MARGIN("margin"),
    TURN_PRIOR("prior"),
    /** Below the margin, but the score lead outweighed the turn prior. */
    SOFT_MARGIN("soft_margin"),
    /** One branch explained almost none of the audio while the other explained much of it. */
    COVERAGE("coverage"),
    /** Below the margin, a clear coverage lead decided before the turn prior. */
    COVERAGE_TIE("coverage_tie"),
    /** The score winner explained almost none of the audio: fail closed. */
    LOW_COVERAGE("low_coverage"),
    FLOOR("floor"),
    NO_PRIOR("no_prior"),
    ASR_FAILURE("asr_failure"),
    LID_ACCEPTED("lid"),
    LID_REJECTED("lid_rejected"),
    EXPLICIT("explicit"),
}

/**
 * How much of the bounded audio each branch's hypothesis accounts for.
 *
 * The average per-token score cannot see this: in factory noise at 5 dB the VI
 * RNN-T often decodes English speech as one or two confident filler tokens
 * ("ờ", "à mai"), whose average beats a 30-token English transcript with a few
 * noisy words. Tokens per second of audio exposes that hypothesis as covering
 * almost nothing of what was said.
 */
internal data class CoverageEvidence(
    val viTokens: Int,
    val enTokens: Int,
    val audioSeconds: Double,
) {
    val viRate: Double get() = if (audioSeconds > 0.0) viTokens / audioSeconds else 0.0
    val enRate: Double get() = if (audioSeconds > 0.0) enTokens / audioSeconds else 0.0
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
 *
 * With [CoverageEvidence] and the coverage settings enabled, emitted tokens per
 * second of audio also count (see [RoutingConfig.coverageLowRate]): a branch
 * that decoded almost nothing loses to one that decoded much more, a clear
 * coverage lead breaks a near tie before the prior does, and a score winner
 * that covers almost nothing fails closed.
 */
internal class DualAsrTurnRouter(private val config: RoutingConfig) {
    /** Direction expected for the next utterance, from the last committed one. */
    private var expected: TranslationDirection? = null

    @Synchronized
    fun decide(
        viNormalized: Double,
        enNormalized: Double,
        coverage: CoverageEvidence? = null,
    ): DualAsrDecision {
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
        coverageLead(coverage, config.coverageHighRate, lowRate = config.coverageLowRate)?.let {
            return DualAsrDecision(it, prior, RoutingDecisionReason.COVERAGE, margin)
        }

        if (margin >= config.marginThreshold) {
            val winner = if (vi >= en) {
                TranslationDirection.VI_TO_EN
            } else {
                TranslationDirection.EN_TO_VI
            }
            // A winner that accounts for almost none of the audio is not
            // evidence for its language: playing "Yeah." for a 6 s English
            // sentence the EN branch could not decode is worse than silence.
            if (coverage != null && config.minWinnerRate > 0.0) {
                val winnerRate = when (winner) {
                    TranslationDirection.VI_TO_EN -> coverage.viRate
                    TranslationDirection.EN_TO_VI -> coverage.enRate
                }
                if (winnerRate < config.minWinnerRate) {
                    return DualAsrDecision(null, prior, RoutingDecisionReason.LOW_COVERAGE, margin)
                }
            }
            return DualAsrDecision(
                direction = winner,
                priorExpected = prior,
                reason = RoutingDecisionReason.MARGIN,
                normalizedMargin = margin,
            )
        }

        // A near tie in score with a clear lead in coverage: the branch that
        // decoded far more of the audio is better evidence than turn order.
        if (config.coverageTieRatio > 0.0) {
            coverageLead(coverage, highRate = 0.0, ratio = config.coverageTieRatio)?.let {
                return DualAsrDecision(it, prior, RoutingDecisionReason.COVERAGE_TIE, margin)
            }
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

    /**
     * The branch with a decisive coverage lead, or null.
     *
     * Absolute mode ([lowRate] > 0): the loser is below [lowRate] while the
     * winner reaches [highRate]. Ratio mode ([ratio] > 0): the winner's rate is
     * at least [ratio] times the loser's. Both also need the winner to emit at
     * least [RoutingConfig.coverageMinTokenGap] more tokens, so two very short
     * hypotheses (a one-word "dạ" against "yeah") never trigger it.
     */
    private fun coverageLead(
        coverage: CoverageEvidence?,
        highRate: Double,
        lowRate: Double = 0.0,
        ratio: Double = 0.0,
    ): TranslationDirection? {
        if (coverage == null || coverage.audioSeconds <= 0.0) return null
        if (lowRate <= 0.0 && ratio <= 0.0) return null
        val gap = config.coverageMinTokenGap
        fun leads(winRate: Double, winTokens: Int, loseRate: Double, loseTokens: Int): Boolean {
            if (winTokens - loseTokens < gap) return false
            return if (ratio > 0.0) {
                winRate >= ratio * loseRate && winRate > 0.0
            } else {
                loseRate < lowRate && winRate >= highRate
            }
        }
        return when {
            leads(coverage.enRate, coverage.enTokens, coverage.viRate, coverage.viTokens) ->
                TranslationDirection.EN_TO_VI
            leads(coverage.viRate, coverage.viTokens, coverage.enRate, coverage.enTokens) ->
                TranslationDirection.VI_TO_EN
            else -> null
        }
    }

    private fun finiteMargin(a: Double, b: Double): Double {
        if (!a.isFinite() && !b.isFinite()) return 0.0
        return abs(a - b).takeIf(Double::isFinite) ?: Double.POSITIVE_INFINITY
    }
}

internal fun TranslationDirection.opposite(): TranslationDirection = when (this) {
    TranslationDirection.VI_TO_EN -> TranslationDirection.EN_TO_VI
    TranslationDirection.EN_TO_VI -> TranslationDirection.VI_TO_EN
}
