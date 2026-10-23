package com.onevoice.link.core

import kotlin.math.sqrt

/**
 * Trim leading and trailing near-silence from one synthesized utterance,
 * keeping [leadMarginMs] / [tailMarginMs] around the first and last frame
 * whose RMS reaches [threshold].
 *
 * Supertonic pads every utterance with ~0.6 s of leading and ~0.7 s of
 * trailing silence (measured on 17 Vietnamese sentences; Kokoro pads ~0.2 s).
 * Leading silence is pure time-to-first-audio, and in strict half-duplex the
 * trailing silence keeps capture closed for the next speaker. The tail margin
 * is longer than the lead so unreleased final stops (-t, -c, -p) and their
 * decay are never clipped. Returns the input unchanged when no frame is loud
 * enough, so an unusually quiet render is never discarded.
 */
internal fun trimSilence(
    samples: FloatArray,
    sampleRate: Int,
    threshold: Float = 0.01f,
    frameMs: Int = 10,
    leadMarginMs: Int = 60,
    tailMarginMs: Int = 150,
): FloatArray {
    val frame = (sampleRate * frameMs / 1_000).coerceAtLeast(1)
    val frames = samples.size / frame
    var first = -1
    var last = -1
    for (index in 0 until frames) {
        var sumSquares = 0.0
        for (offset in index * frame until (index + 1) * frame) {
            sumSquares += samples[offset].toDouble() * samples[offset]
        }
        if (sqrt(sumSquares / frame) >= threshold) {
            if (first < 0) first = index
            last = index
        }
    }
    if (first < 0) return samples
    val start = (first * frame - sampleRate * leadMarginMs / 1_000).coerceAtLeast(0)
    val end = ((last + 1) * frame + sampleRate * tailMarginMs / 1_000).coerceAtMost(samples.size)
    return if (start == 0 && end == samples.size) samples else samples.copyOfRange(start, end)
}
