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
}
