package com.onevoice.link.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DualAsrRouterTest {
    private val config = RoutingConfig(
        mode = RoutingMode.DUAL_ASR,
        marginThreshold = 0.25,
        floorThreshold = -5.0,
        priorWeight = 0.15,
    )

    @Test
    fun floorWinsBeforeMarginOrPrior() {
        val router = DualAsrTurnRouter(config)
        router.commit(TranslationDirection.VI_TO_EN, forcedBoundary = false)

        val result = router.decide(viNormalized = -8.0, enNormalized = -6.0)

        assertNull(result.direction)
        assertEquals(RoutingDecisionReason.FLOOR, result.reason)
    }

    @Test
    fun configuredMarginChoosesHigherAverageLogProbability() {
        val result = DualAsrTurnRouter(config).decide(-1.10, -1.36)

        assertEquals(TranslationDirection.VI_TO_EN, result.direction)
        assertEquals(RoutingDecisionReason.MARGIN, result.reason)
    }

    @Test
    fun marginThresholdIsInclusive() {
        val result = DualAsrTurnRouter(config).decide(-1.0, -1.25)

        assertEquals(TranslationDirection.VI_TO_EN, result.direction)
        assertEquals(RoutingDecisionReason.MARGIN, result.reason)
    }

    @Test
    fun floorThresholdIsStrictlyBelow() {
        val result = DualAsrTurnRouter(config).decide(-5.0, -5.0)

        assertNull(result.direction)
        assertEquals(RoutingDecisionReason.NO_PRIOR, result.reason)
    }

    @Test
    fun ambiguousFirstTurnFailsClosed() {
        val result = DualAsrTurnRouter(config).decide(-1.10, -1.20)

        assertNull(result.direction)
        assertEquals(RoutingDecisionReason.NO_PRIOR, result.reason)
    }

    @Test
    fun ambiguousTurnUsesOppositeOfLastCompletedLanguage() {
        val router = DualAsrTurnRouter(config)
        router.commit(TranslationDirection.VI_TO_EN, forcedBoundary = false)

        val result = router.decide(-1.10, -1.20)

        assertEquals(TranslationDirection.EN_TO_VI, result.direction)
        assertEquals(RoutingDecisionReason.TURN_PRIOR, result.reason)
    }

    @Test
    fun scoresBeatPriorInsideTheAmbiguousZone() {
        // Measured on device: a 1.6 s Vietnamese fixture scored VI -0.192 vs
        // EN -0.419. The hard rule discarded that 0.227 lead and followed the
        // alternation prior into English.
        val router = DualAsrTurnRouter(config)
        router.commit(TranslationDirection.VI_TO_EN, forcedBoundary = false)

        val result = router.decide(-0.192, -0.419)

        assertEquals(TranslationDirection.VI_TO_EN, result.direction)
        assertEquals(RoutingDecisionReason.SOFT_MARGIN, result.reason)
    }

    @Test
    fun priorBreaksNearTies() {
        val router = DualAsrTurnRouter(config)
        router.commit(TranslationDirection.EN_TO_VI, forcedBoundary = false)

        // VI leads by only 0.10 < priorWeight, so the expected VI turn stands;
        // EN leading by 0.10 is likewise overruled by a VI expectation.
        assertEquals(TranslationDirection.VI_TO_EN, router.decide(-1.0, -1.10).direction)
        val overruled = router.decide(-1.10, -1.0)
        assertEquals(TranslationDirection.VI_TO_EN, overruled.direction)
        assertEquals(RoutingDecisionReason.TURN_PRIOR, overruled.reason)
    }

    @Test
    fun priorWeightAtMarginRestoresHardPrior() {
        val hard = DualAsrTurnRouter(config.copy(priorWeight = 0.25))
        hard.commit(TranslationDirection.VI_TO_EN, forcedBoundary = false)

        val result = hard.decide(-0.192, -0.419)

        assertEquals(TranslationDirection.EN_TO_VI, result.direction)
        assertEquals(RoutingDecisionReason.TURN_PRIOR, result.reason)
    }

    @Test
    fun forcedBoundaryExpectsTheSameSpeakerToContinue() {
        val router = DualAsrTurnRouter(config)
        router.commit(TranslationDirection.VI_TO_EN, forcedBoundary = false)
        router.commit(TranslationDirection.EN_TO_VI, forcedBoundary = true)

        assertEquals(TranslationDirection.EN_TO_VI, router.expectedNext())
    }

    @Test
    fun forcedFirstBoundaryCreatesContinuationPrior() {
        val router = DualAsrTurnRouter(config)
        router.commit(TranslationDirection.VI_TO_EN, forcedBoundary = true)

        assertEquals(TranslationDirection.VI_TO_EN, router.expectedNext())
    }

    @Test
    fun enOffsetCalibratesBeforeTheMargin() {
        // Measured: Parakeet CTC scored a Vietnamese question at -0.140 against
        // GIPFormer's -0.050; uncalibrated, that 0.09 lead sat in the prior zone.
        val calibrated = DualAsrTurnRouter(config.copy(marginThreshold = 0.15, enScoreOffset = -0.08))
        calibrated.commit(TranslationDirection.VI_TO_EN, forcedBoundary = false)

        val result = calibrated.decide(-0.050, -0.140)

        assertEquals(TranslationDirection.VI_TO_EN, result.direction)
        assertEquals(RoutingDecisionReason.MARGIN, result.reason)
    }

    @Test
    fun enOffsetAlsoAppliesToTheFloor() {
        val result = DualAsrTurnRouter(config.copy(enScoreOffset = -0.5)).decide(-6.0, -4.8)

        assertNull(result.direction)
        assertEquals(RoutingDecisionReason.FLOOR, result.reason)
    }

    @Test
    fun negativePriorWeightIsRejected() {
        val failure = runCatching { config.copy(priorWeight = -0.1) }.exceptionOrNull()
        assertEquals(IllegalArgumentException::class.java, failure?.javaClass)
    }

    // Coverage rules, with the values in gradle.properties. Cases named after a
    // clip are measured rows from bench-results/noise-5db-800 (factory noise,
    // 5 dB); the short "dạ" reply is constructed, as that set has none.
    private val covered = config.copy(
        marginThreshold = 0.15,
        enScoreOffset = -0.08,
        coverageLowRate = 1.2,
        coverageHighRate = 2.0,
        coverageMinTokenGap = 4,
        coverageTieRatio = 1.8,
        minWinnerRate = 0.8,
    )

    @Test
    fun confidentFillerDoesNotBeatAFullEnglishTranscript() {
        // en_EV-0250__cutting_steam: VI heard "ờ" (2 tokens, -0.09) while
        // Parakeet transcribed the sentence (34 tokens, -0.31) in 5.5 s.
        val evidence = CoverageEvidence(viTokens = 2, enTokens = 34, audioSeconds = 5.5)

        assertEquals(TranslationDirection.VI_TO_EN, DualAsrTurnRouter(config.copy(
            marginThreshold = 0.15, enScoreOffset = -0.08,
        )).decide(-0.09, -0.31, evidence).direction)
        val result = DualAsrTurnRouter(covered).decide(-0.09, -0.31, evidence)

        assertEquals(TranslationDirection.EN_TO_VI, result.direction)
        assertEquals(RoutingDecisionReason.COVERAGE, result.reason)
    }

    @Test
    fun coverageLeadBreaksANearTieBeforeThePrior() {
        // en_EV-9341__cutting_steam: scores within the margin, prior said VI,
        // but EN decoded 18 tokens against VI's 8 in 3.6 s.
        val router = DualAsrTurnRouter(covered)
        router.commit(TranslationDirection.EN_TO_VI, forcedBoundary = false)

        val result = router.decide(-0.59, -0.50, CoverageEvidence(8, 18, 3.6))

        assertEquals(TranslationDirection.EN_TO_VI, result.direction)
        assertEquals(RoutingDecisionReason.COVERAGE_TIE, result.reason)
    }

    @Test
    fun winnerThatCoversAlmostNothingFailsClosed() {
        // en_EV-2748: Parakeet emitted nothing; VI's "ừ" (2 tokens in 6 s)
        // used to win by margin and play "Yeah.".
        val result = DualAsrTurnRouter(covered).decide(
            -0.87, Double.NEGATIVE_INFINITY, CoverageEvidence(2, 0, 6.0),
        )

        assertNull(result.direction)
        assertEquals(RoutingDecisionReason.LOW_COVERAGE, result.reason)
    }

    @Test
    fun shortVietnameseReplyIsNotOverruledByCoverage() {
        // A one-word "dạ" in 0.8 s: both hypotheses are short, so the token
        // gap keeps every coverage rule out and the score decides.
        val result = DualAsrTurnRouter(covered).decide(-0.10, -0.90, CoverageEvidence(1, 2, 0.8))

        assertEquals(TranslationDirection.VI_TO_EN, result.direction)
        assertEquals(RoutingDecisionReason.MARGIN, result.reason)
    }

    @Test
    fun ordinaryVietnameseStillRoutesByMargin() {
        // vi_VE-0928__cutting_steam: EN produced more tokens (19 vs 12), but VI
        // covered the audio (2.9/s), so the low-rate rule does not fire.
        val result = DualAsrTurnRouter(covered).decide(-0.18, -0.98, CoverageEvidence(12, 19, 4.2))

        assertEquals(TranslationDirection.VI_TO_EN, result.direction)
        assertEquals(RoutingDecisionReason.MARGIN, result.reason)
    }

    @Test
    fun coverageRulesAreOffWithoutEvidenceOrByDefault() {
        val evidence = CoverageEvidence(2, 34, 5.5)

        assertEquals(RoutingDecisionReason.MARGIN, DualAsrTurnRouter(covered).decide(-0.09, -0.31).reason)
        assertEquals(RoutingDecisionReason.MARGIN, DualAsrTurnRouter(config).decide(-0.09, -0.40, evidence).reason)
    }

    @Test
    fun coverageHighRateMustExceedLowRate() {
        val failure = runCatching {
            covered.copy(coverageLowRate = 2.0, coverageHighRate = 1.5)
        }.exceptionOrNull()
        assertEquals(IllegalArgumentException::class.java, failure?.javaClass)
    }
}
