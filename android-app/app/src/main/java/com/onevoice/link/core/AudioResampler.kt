package com.onevoice.link.core

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

private const val MAX_PHASES = 1_024

/**
 * Band-limited rational resampling of one whole utterance.
 *
 * Playback, route priming and every latency figure run at 24 kHz; Supertonic
 * renders 44.1 kHz. Dropping samples would fold its 12–22 kHz band back into
 * the speech band, so this is a Blackman-windowed sinc, low-passed below the
 * narrower Nyquist and evaluated as a polyphase table: 44.1 → 24 kHz has only
 * 80 distinct phases, which keeps a 3 s utterance in the low milliseconds.
 * Samples beyond either edge are treated as silence.
 */
internal fun resamplePcm(
    samples: FloatArray,
    fromRate: Int,
    toRate: Int,
    zeroCrossings: Int = 16,
): FloatArray {
    require(fromRate > 0 && toRate > 0) { "Sample rates must be positive" }
    require(zeroCrossings > 0) { "zeroCrossings must be positive" }
    if (fromRate == toRate || samples.isEmpty()) return samples.copyOf()
    val divisor = gcd(fromRate, toRate)
    val up = toRate / divisor
    val down = fromRate / divisor
    require(up <= MAX_PHASES) { "Unsupported resampling ratio $fromRate -> $toRate Hz" }

    // Cutoff as a fraction of the input rate's Nyquist; 0.95 leaves the
    // window's transition band below the output Nyquist.
    val cutoff = 0.95 * minOf(1.0, toRate.toDouble() / fromRate)
    val halfWidth = ceil(zeroCrossings / cutoff).toInt()
    val taps = 2 * halfWidth
    val table = Array(up) { phase ->
        val fraction = phase.toDouble() / up
        val row = DoubleArray(taps) { tap ->
            val offset = fraction + (halfWidth - 1 - tap) // t - k, in input samples
            windowedSinc(offset, cutoff, halfWidth.toDouble())
        }
        // Normalize each phase to unit DC gain so no phase is louder than another.
        val sum = row.sum()
        FloatArray(taps) { (row[it] / sum).toFloat() }
    }

    val outputSize = (samples.size.toLong() * up / down).toInt()
    return FloatArray(outputSize) { n ->
        val position = n.toLong() * down
        val base = (position / up).toInt()
        val weights = table[(position % up).toInt()]
        val first = base - halfWidth + 1
        var acc = 0f
        for (tap in 0 until taps) {
            val index = first + tap
            if (index >= 0 && index < samples.size) acc += samples[index] * weights[tap]
        }
        acc
    }
}

private fun windowedSinc(offset: Double, cutoff: Double, halfWidth: Double): Double {
    if (offset <= -halfWidth || offset >= halfWidth) return 0.0
    val x = PI * cutoff * offset
    val sinc = if (x == 0.0) 1.0 else sin(x) / x
    val u = offset / halfWidth // -1..1
    val blackman = 0.42 + 0.5 * cos(PI * u) + 0.08 * cos(2 * PI * u)
    return cutoff * sinc * blackman
}

private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
