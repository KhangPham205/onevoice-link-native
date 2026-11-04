package com.onevoice.link.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CaptureSourceTest {
    private val frame = ShortArray(320)

    @Test
    fun fastReplayDeliversEverySampleThenTheTailSilenceThenEnd() {
        val pcm = ShortArray(1_000) { (it + 1).toShort() }
        val provider = ReplayCaptureProvider(pcm, paced = false, replayTag = "t", route = AudioRouteKind.PHONE,
            tailSilenceSamples = 500)
        val capture = checkNotNull(provider.open(1L))
        val out = ArrayList<Short>()
        while (true) {
            val count = capture.read(frame)
            if (count < 0) break
            for (index in 0 until count) out += frame[index]
        }
        assertEquals(1_500, out.size)
        assertArrayEquals(pcm, out.take(1_000).toShortArray())
        assertTrue(out.drop(1_000).all { it == 0.toShort() })
        assertTrue(provider.exhausted)
        assertNull(provider.open(2L))
    }

    @Test
    fun pacedReplaySkipsAudioThatPlayedWhileNoTurnWasCapturing() {
        var now = 1_000_000_000L
        val provider = ReplayCaptureProvider(
            pcm = ShortArray(48_000),
            paced = true,
            replayTag = null,
            route = AudioRouteKind.PHONE,
            tailSilenceSamples = 0,
            clockNs = { now },
            sleepNs = { ns -> if (ns > 0) now += ns },
        )
        val first = checkNotNull(provider.open(1L))
        repeat(50) { first.read(frame) } // one second, paced
        first.close()
        now += 1_000_000_000L // a second of "playback" with the microphone closed
        checkNotNull(provider.open(2L))
        assertEquals("one second skipped, like a closed microphone", 16_000L, provider.skippedWhileClosed)
    }

    @Test
    fun wavPcm16MonoIsParsedAndOtherFormatsAreRejected() {
        val samples = shortArrayOf(1, -2, 3, 32_767)
        assertArrayEquals(samples, parseWavPcm16(wav(samples, rate = 16_000, channels = 1)))
        val rejected = runCatching { parseWavPcm16(wav(samples, rate = 48_000, channels = 1)) }
        assertTrue(rejected.isFailure)
        assertTrue(runCatching { parseWavPcm16(wav(samples, rate = 16_000, channels = 2)) }.isFailure)
    }

    private fun wav(samples: ShortArray, rate: Int, channels: Int): ByteArray {
        val data = samples.size * 2
        return ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(channels.toShort())
            putInt(rate); putInt(rate * channels * 2); putShort((channels * 2).toShort()); putShort(16)
            put("data".toByteArray()); putInt(data)
            samples.forEach { putShort(it) }
        }.array()
    }
}
