package com.onevoice.link.core

import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** One synthesized translation unit, mono at [sampleRate]. */
internal class PreparedAudio(
    val unitIndex: Int,
    val mono: FloatArray,
    val sampleRate: Int,
    /** Monotonic time the waveform became ready. */
    val readyNs: Long,
    /** Turn revision it was rendered for; a CPU recovery bumps the revision. */
    val revision: Int = 0,
) {
    val bytes: Long get() = mono.size.toLong() * Float.SIZE_BYTES
}

internal sealed interface AudioTake {
    data class Item(val audio: PreparedAudio) : AudioTake
    /** The producer finished and every item was taken. */
    data object Finished : AudioTake
    /** The producer failed; items already taken may have played. */
    data class Failed(val reason: String) : AudioTake
    data object Cancelled : AudioTake
    data object Timeout : AudioTake
}

/**
 * Synthesized audio waiting for playback, bounded by bytes rather than item
 * count: one long unit can hold as much PCM as ten short ones.
 *
 * The budget is soft in one direction only: an empty queue always admits the
 * next item, so a unit longer than the whole budget is delayed, never lost or
 * deadlocked. Only the TTS worker blocks on it; capture and ASR never do.
 */
internal class PreparedAudioQueue(private val budgetBytes: Long) {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val items = ArrayDeque<PreparedAudio>()
    private var finished = false
    private var failure: String? = null
    private var cancelled = false

    var queuedBytes: Long = 0
        private set
    var highWaterBytes: Long = 0
        private set
    var produced: Int = 0
        private set

    /** Blocks while over budget. Returns false when the queue was cancelled or closed. */
    @Throws(InterruptedException::class)
    fun put(audio: PreparedAudio): Boolean {
        lock.lockInterruptibly()
        try {
            while (!cancelled && !finished && failure == null &&
                items.isNotEmpty() && queuedBytes + audio.bytes > budgetBytes
            ) {
                changed.await()
            }
            if (cancelled || finished || failure != null) return false
            items.addLast(audio)
            queuedBytes += audio.bytes
            highWaterBytes = maxOf(highWaterBytes, queuedBytes)
            produced++
            changed.signalAll()
            return true
        } finally {
            lock.unlock()
        }
    }

    @Throws(InterruptedException::class)
    fun take(timeoutMs: Long): AudioTake {
        lock.lockInterruptibly()
        try {
            var remainingNs = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (true) {
                if (cancelled) return AudioTake.Cancelled
                items.pollFirst()?.let { audio ->
                    queuedBytes -= audio.bytes
                    changed.signalAll()
                    return AudioTake.Item(audio)
                }
                failure?.let { return AudioTake.Failed(it) }
                if (finished) return AudioTake.Finished
                if (remainingNs <= 0L) return AudioTake.Timeout
                remainingNs = changed.awaitNanos(remainingNs)
            }
        } finally {
            lock.unlock()
        }
    }

    /** No more items will follow; queued ones are still delivered. */
    fun finish() = lock.withLock {
        finished = true
        changed.signalAll()
    }

    /** The producer failed; queued items are discarded and takers see the reason. */
    fun fail(reason: String) = lock.withLock {
        if (failure == null) failure = reason
        items.clear()
        queuedBytes = 0
        changed.signalAll()
    }

    /** A recovery replaced every unit before playback began: drop what was rendered. */
    fun clearForRevision() = lock.withLock {
        items.clear()
        queuedBytes = 0
        changed.signalAll()
    }

    fun cancel() = lock.withLock {
        cancelled = true
        items.clear()
        queuedBytes = 0
        changed.signalAll()
    }
}
