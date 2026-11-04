package com.onevoice.link.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class PreparedAudioQueueTest {
    private fun audio(index: Int, samples: Int) = PreparedAudio(index, FloatArray(samples), 24_000, 0L)

    @Test
    fun anEmptyQueueAdmitsAnItemLargerThanTheBudget() {
        val queue = PreparedAudioQueue(budgetBytes = 100)
        assertTrue(queue.put(audio(0, 1_000)))
        assertEquals(4_000L, queue.queuedBytes)
    }

    @Test
    fun producerWaitsForPlaybackWhenOverBudget() {
        val queue = PreparedAudioQueue(budgetBytes = 4_000)
        queue.put(audio(0, 1_000))
        val admitted = CountDownLatch(1)
        val producer = thread {
            queue.put(audio(1, 1_000))
            admitted.countDown()
        }
        assertFalse("second item must wait", admitted.await(150, TimeUnit.MILLISECONDS))
        val first = queue.take(100) as AudioTake.Item
        assertEquals(0, first.audio.unitIndex)
        assertTrue(admitted.await(1, TimeUnit.SECONDS))
        producer.join()
        assertEquals(4_000L, queue.highWaterBytes)
    }

    @Test
    fun finishDeliversQueuedItemsInOrderBeforeFinished() {
        val queue = PreparedAudioQueue(budgetBytes = 1_000_000)
        queue.put(audio(0, 10))
        queue.put(audio(1, 10))
        queue.finish()
        assertEquals(0, (queue.take(10) as AudioTake.Item).audio.unitIndex)
        assertEquals(1, (queue.take(10) as AudioTake.Item).audio.unitIndex)
        assertEquals(AudioTake.Finished, queue.take(10))
    }

    @Test
    fun anEmptyOpenQueueTimesOutInsteadOfLookingFinished() {
        val queue = PreparedAudioQueue(budgetBytes = 1_000)
        assertEquals(AudioTake.Timeout, queue.take(20))
    }

    @Test
    fun failureAndCancelStopTheTurn() {
        val failed = PreparedAudioQueue(budgetBytes = 1_000_000)
        failed.put(audio(0, 10))
        failed.fail("tts")
        assertEquals(AudioTake.Failed("tts"), failed.take(10))
        assertFalse(failed.put(audio(1, 10)))

        val cancelled = PreparedAudioQueue(budgetBytes = 1_000_000)
        cancelled.cancel()
        assertEquals(AudioTake.Cancelled, cancelled.take(10))
        assertFalse(cancelled.put(audio(0, 10)))
    }

    @Test
    fun replayCopyKeepsWhatPlayedOrNothingWhenOverTheCap() {
        val replay = ReplayAccumulator(capBytes = 400)
        replay.append(FloatArray(40) { 0.5f })
        replay.appendSilence(20)
        replay.append(FloatArray(30) { -0.5f })
        val played = checkNotNull(replay.result())
        assertEquals(90, played.size)
        assertEquals(0f, played[45], 0f)
        assertEquals(-0.5f, played[89], 0f)

        val over = ReplayAccumulator(capBytes = 400)
        over.append(FloatArray(80))
        over.append(FloatArray(80))
        assertEquals("never a silently shortened copy", null, over.result())
    }
}
