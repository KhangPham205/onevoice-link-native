package com.onevoice.link.core

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal enum class HalfDuplexState {
    IDLE,
    CAPTURING,
    PROCESSING,
    PLAYING,
}

internal data class HalfDuplexTransition(
    val turnId: Long,
    val from: HalfDuplexState,
    val to: HalfDuplexState,
    val timestampNs: Long,
    val dwellMs: Long,
    val reason: String,
)

/**
 * Single-owner coordinator for the live audio state machine.
 *
 * Capture waits in [awaitIdleThenCapture] while inference or playback owns the
 * current turn. Teardown calls [cancel], which both returns the state to IDLE
 * and wakes every waiter. All production transitions are checked; an illegal
 * edge is a programming error rather than an implicit full-duplex fallback.
 */
internal class HalfDuplexCoordinator(
    private val clockNs: () -> Long = System::nanoTime,
    private val onTransition: (HalfDuplexTransition) -> Unit = {},
) {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private var state = HalfDuplexState.IDLE
    private var activeTurnId: Long? = null
    private var enteredNs = clockNs()
    private var cancelled = true

    fun reset() = lock.withLock {
        cancelled = false
        if (state != HalfDuplexState.IDLE) {
            transitionLocked(activeTurnId ?: -1L, HalfDuplexState.IDLE, "reset")
        }
        changed.signalAll()
    }

    @Throws(InterruptedException::class)
    fun awaitIdleThenCapture(turnId: Long): Boolean {
        lock.lockInterruptibly()
        try {
            while (!cancelled && state != HalfDuplexState.IDLE) changed.await()
            if (cancelled) return false
            check(activeTurnId == null) { "IDLE half-duplex state still has an owner" }
            activeTurnId = turnId
            transitionLocked(turnId, HalfDuplexState.CAPTURING, "capture_start")
            return true
        } finally {
            lock.unlock()
        }
    }

    fun transition(
        turnId: Long,
        expected: HalfDuplexState,
        next: HalfDuplexState,
        reason: String,
    ): HalfDuplexTransition = lock.withLock {
        check(!cancelled) { "Half-duplex session is cancelled" }
        check(activeTurnId == turnId) {
            "Turn $turnId does not own half-duplex state (owner=$activeTurnId)"
        }
        check(state == expected) { "Illegal half-duplex edge $state->$next (expected $expected)" }
        check(isProductionEdge(expected, next)) { "Illegal half-duplex edge $expected->$next" }
        transitionLocked(turnId, next, reason)
    }

    /** Safety-net for blank/floor/error paths that must never strand capture. */
    fun finishTurn(turnId: Long, reason: String): HalfDuplexTransition? = lock.withLock {
        if (activeTurnId != turnId || state == HalfDuplexState.IDLE) return null
        transitionLocked(turnId, HalfDuplexState.IDLE, reason)
    }

    @Throws(InterruptedException::class)
    fun awaitTurnFinished(turnId: Long): Boolean {
        lock.lockInterruptibly()
        try {
            while (!cancelled && activeTurnId == turnId) changed.await()
            return !cancelled
        } finally {
            lock.unlock()
        }
    }

    fun cancel(reason: String) = lock.withLock {
        cancelled = true
        if (state != HalfDuplexState.IDLE || activeTurnId != null) {
            transitionLocked(activeTurnId ?: -1L, HalfDuplexState.IDLE, reason)
        }
        changed.signalAll()
    }

    fun snapshot(): HalfDuplexState = lock.withLock { state }

    private fun transitionLocked(
        turnId: Long,
        next: HalfDuplexState,
        reason: String,
    ): HalfDuplexTransition {
        val now = clockNs()
        val transition = HalfDuplexTransition(
            turnId = turnId,
            from = state,
            to = next,
            timestampNs = now,
            dwellMs = ((now - enteredNs).coerceAtLeast(0L)) / 1_000_000L,
            reason = reason,
        )
        state = next
        enteredNs = now
        if (next == HalfDuplexState.IDLE) activeTurnId = null
        onTransition(transition)
        changed.signalAll()
        return transition
    }

    private fun isProductionEdge(from: HalfDuplexState, to: HalfDuplexState): Boolean =
        when (from) {
            HalfDuplexState.IDLE -> to == HalfDuplexState.CAPTURING
            HalfDuplexState.CAPTURING ->
                to == HalfDuplexState.PROCESSING || to == HalfDuplexState.IDLE
            HalfDuplexState.PROCESSING ->
                to == HalfDuplexState.PLAYING || to == HalfDuplexState.IDLE
            HalfDuplexState.PLAYING -> to == HalfDuplexState.IDLE
        }
}

/** Mono target waveform -> interleaved stereo frames for one shared TWS pair. */
internal fun interleaveTargetStereo(
    mono: FloatArray,
    direction: TranslationDirection,
): FloatArray = FloatArray(mono.size * 2).also { stereo ->
    val channelOffset = when (direction) {
        // Target Vietnamese is heard by the left bud.
        TranslationDirection.EN_TO_VI -> 0
        // Target English is heard by the right bud.
        TranslationDirection.VI_TO_EN -> 1
    }
    mono.forEachIndexed { frame, sample -> stereo[frame * 2 + channelOffset] = sample }
}
