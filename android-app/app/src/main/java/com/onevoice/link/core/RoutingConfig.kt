package com.onevoice.link.core

import com.onevoice.link.BuildConfig

/** Selects the utterance-level language router without removing either path. */
internal enum class RoutingMode {
    DUAL_ASR,
    LID;

    companion object {
        fun parse(value: String): RoutingMode =
            entries.firstOrNull { it.name == value }
                ?: throw IllegalArgumentException(
                    "routingMode must be DUAL_ASR or LID (got '$value')",
                )
    }
}

/** Immutable routing settings embedded in the APK that produced a tuning log. */
internal data class RoutingConfig(
    val mode: RoutingMode,
    val marginThreshold: Double,
    val floorThreshold: Double,
    /**
     * Nats/token added toward the expected turn when the margin is not met.
     * Values >= [marginThreshold] reproduce the old hard alternation rule.
     */
    val priorWeight: Double,
    /**
     * Added to the English branch's normalized score before any comparison.
     * The VI RNN-T and the EN Parakeet CTC head are different model families:
     * CTC peak-frame scores run systematically closer to zero, including on
     * Vietnamese speech. This is the intercept of a two-model linear fusion.
     */
    val enScoreOffset: Double = 0.0,
    /**
     * Emitted tokens per second of audio below which a branch's hypothesis is
     * treated as covering almost nothing. With [coverageHighRate] and
     * [coverageMinTokenGap], such a branch loses before the score comparison.
     * 0 disables the rule.
     */
    val coverageLowRate: Double = 0.0,
    /** Rate the other branch must reach for [coverageLowRate] to decide. */
    val coverageHighRate: Double = 0.0,
    /** Every coverage rule also needs the winner to emit this many more tokens. */
    val coverageMinTokenGap: Int = 0,
    /** Below the margin, a rate this many times the other's wins before the prior. 0 disables. */
    val coverageTieRatio: Double = 0.0,
    /** A margin winner below this rate fails closed (`low_coverage`). 0 disables. */
    val minWinnerRate: Double = 0.0,
) {
    init {
        require(marginThreshold.isFinite() && marginThreshold >= 0.0) {
            "Dual-ASR margin threshold must be finite and >= 0"
        }
        require(floorThreshold.isFinite()) {
            "Dual-ASR floor threshold must be finite"
        }
        require(priorWeight.isFinite() && priorWeight >= 0.0) {
            "Dual-ASR prior weight must be finite and >= 0"
        }
        require(enScoreOffset.isFinite()) { "Dual-ASR EN score offset must be finite" }
        for ((name, value) in listOf(
            "coverage low rate" to coverageLowRate,
            "coverage high rate" to coverageHighRate,
            "coverage tie ratio" to coverageTieRatio,
            "min winner rate" to minWinnerRate,
        )) {
            require(value.isFinite() && value >= 0.0) { "Dual-ASR $name must be finite and >= 0" }
        }
        require(coverageMinTokenGap >= 0) { "Dual-ASR coverage token gap must be >= 0" }
        require(coverageLowRate == 0.0 || coverageHighRate > coverageLowRate) {
            "Dual-ASR coverage high rate must exceed the low rate"
        }
    }

    companion object {
        fun fromBuildConfig(): RoutingConfig = RoutingConfig(
            mode = RoutingMode.parse(BuildConfig.ROUTING_MODE),
            marginThreshold = BuildConfig.DUAL_ASR_MARGIN_THRESHOLD,
            floorThreshold = BuildConfig.DUAL_ASR_FLOOR_THRESHOLD,
            priorWeight = BuildConfig.DUAL_ASR_PRIOR_WEIGHT,
            enScoreOffset = BuildConfig.DUAL_ASR_EN_OFFSET,
            coverageLowRate = BuildConfig.DUAL_ASR_COVERAGE_LOW_RATE,
            coverageHighRate = BuildConfig.DUAL_ASR_COVERAGE_HIGH_RATE,
            coverageMinTokenGap = BuildConfig.DUAL_ASR_COVERAGE_TOKEN_GAP,
            coverageTieRatio = BuildConfig.DUAL_ASR_COVERAGE_TIE_RATIO,
            minWinnerRate = BuildConfig.DUAL_ASR_MIN_WINNER_RATE,
        )
    }
}
