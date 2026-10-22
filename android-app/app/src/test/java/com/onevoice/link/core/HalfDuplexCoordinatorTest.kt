package com.onevoice.link.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HalfDuplexCoordinatorTest {
    @Test
    fun productionTurnTraversesEveryStateInOrder() {
        var now = 0L
        val transitions = mutableListOf<Pair<HalfDuplexState, HalfDuplexState>>()
        val coordinator = HalfDuplexCoordinator(
            clockNs = { now.also { now += 1_000_000L } },
            onTransition = { transitions += it.from to it.to },
        )
        coordinator.reset()

        assertTrue(coordinator.awaitIdleThenCapture(7L))
        coordinator.transition(7L, HalfDuplexState.CAPTURING, HalfDuplexState.PROCESSING, "bounded")
        coordinator.transition(7L, HalfDuplexState.PROCESSING, HalfDuplexState.PLAYING, "route_ready")
        coordinator.finishTurn(7L, "drained")

        assertEquals(
            listOf(
                HalfDuplexState.IDLE to HalfDuplexState.CAPTURING,
                HalfDuplexState.CAPTURING to HalfDuplexState.PROCESSING,
                HalfDuplexState.PROCESSING to HalfDuplexState.PLAYING,
                HalfDuplexState.PLAYING to HalfDuplexState.IDLE,
            ),
            transitions,
        )
    }

    @Test
    fun failClosedProcessingReturnsToIdle() {
        val coordinator = HalfDuplexCoordinator()
        coordinator.reset()
        coordinator.awaitIdleThenCapture(11L)
        coordinator.transition(11L, HalfDuplexState.CAPTURING, HalfDuplexState.PROCESSING, "bounded")

        coordinator.finishTurn(11L, "floor")

        assertEquals(HalfDuplexState.IDLE, coordinator.snapshot())
    }

    @Test
    fun stereoMappingLeavesOppositeChannelSilent() {
        val mono = floatArrayOf(0.25f, -0.5f)

        assertTrue(
            interleaveTargetStereo(mono, TranslationDirection.EN_TO_VI)
                .contentEquals(floatArrayOf(0.25f, 0f, -0.5f, 0f)),
        )
        assertTrue(
            interleaveTargetStereo(mono, TranslationDirection.VI_TO_EN)
                .contentEquals(floatArrayOf(0f, 0.25f, 0f, -0.5f)),
        )
    }
}
