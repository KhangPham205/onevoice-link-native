package com.onevoice.link.hf

import java.io.File
import java.text.Normalizer

/**
 * Minimal pure-Kotlin SentencePiece **unigram** encoder for MarianMT models
 * (Helsinki-NLP opus-mt): parses the `.spm` ModelProto directly and segments
 * with Viterbi over piece log-probabilities. No native tokenizer dependency.
 *
 * Scope: exactly what opus-mt needs —
 *  - NFKC-ish normalization (java.text.Normalizer) + whitespace collapsing,
 *  - `▁` (U+2581) word-boundary marker with a dummy prefix,
 *  - Viterbi unigram segmentation, unknown characters fall back to `<unk>`.
 *
 * Piece→id mapping is NOT taken from the .spm: Marian uses the shared
 * `vocab.json`, which the caller supplies via [MarianVocab].
 */
class SentencePieceUnigram private constructor(
    private val pieces: HashMap<String, Float>,
    private val maxPieceLen: Int,
) {
    companion object {
        private const val BOUNDARY = '▁' // ▁

        /** Parse the pieces (field 1: {piece, score, type}) out of a .spm file. */
        fun load(spmFile: File): SentencePieceUnigram {
            val bytes = spmFile.readBytes()
            val pieces = HashMap<String, Float>(65536)
            var maxLen = 1
            var i = 0

            fun readVarint(): Long {
                var shift = 0
                var result = 0L
                while (true) {
                    val b = bytes[i++].toInt() and 0xFF
                    result = result or ((b and 0x7F).toLong() shl shift)
                    if (b < 0x80) return result
                    shift += 7
                }
            }

            while (i < bytes.size) {
                val key = readVarint()
                val field = (key shr 3).toInt()
                val wire = (key and 0x7).toInt()
                if (field == 1 && wire == 2) {
                    // repeated SentencePiece { 1: piece, 2: score, 3: type }
                    val len = readVarint().toInt()
                    val end = i + len
                    var piece: String? = null
                    var score = 0f
                    var type = 1 // NORMAL
                    while (i < end) {
                        val k2 = readVarint()
                        when (((k2 shr 3).toInt() shl 3) or (k2 and 0x7).toInt()) {
                            (1 shl 3) or 2 -> { // piece: string
                                val sl = readVarint().toInt()
                                piece = String(bytes, i, sl, Charsets.UTF_8)
                                i += sl
                            }
                            (2 shl 3) or 5 -> { // score: float (fixed32)
                                score = Float.fromBits(
                                    (bytes[i].toInt() and 0xFF) or
                                        ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                                        ((bytes[i + 2].toInt() and 0xFF) shl 16) or
                                        ((bytes[i + 3].toInt() and 0xFF) shl 24),
                                )
                                i += 4
                            }
                            (3 shl 3) or 0 -> type = readVarint().toInt()
                            else -> i = skipField(k2, bytes, i)
                        }
                    }
                    // Keep NORMAL(1) and USER_DEFINED(4) pieces for segmentation.
                    if (piece != null && (type == 1 || type == 4)) {
                        pieces[piece] = score
                        if (piece.length > maxLen) maxLen = piece.length
                    }
                } else {
                    i = skipField(key, bytes, i)
                }
            }
            return SentencePieceUnigram(pieces, maxLen)
        }

        private fun skipField(key: Long, bytes: ByteArray, pos: Int): Int {
            var i = pos
            when ((key and 0x7).toInt()) {
                0 -> { // varint — readVarint advances the shared index; emulate here
                    while ((bytes[i].toInt() and 0x80) != 0) i++
                    i++
                }
                1 -> i += 8
                2 -> {
                    // length-delimited: read the varint length at i
                    var shift = 0; var len = 0L
                    while (true) {
                        val b = bytes[i++].toInt() and 0xFF
                        len = len or ((b and 0x7F).toLong() shl shift)
                        if (b < 0x80) break
                        shift += 7
                    }
                    i += len.toInt()
                }
                5 -> i += 4
                else -> error("Unsupported wire type in .spm: ${key and 0x7}")
            }
            return i
        }
    }

    /** Normalize + segment [text] into SentencePiece tokens (with ▁ markers). */
    fun encodeToPieces(text: String): List<String> {
        val normalized = Normalizer.normalize(text.trim(), Normalizer.Form.NFKC)
            .replace(Regex("\\s+"), " ")
        if (normalized.isEmpty()) return emptyList()
        val s = BOUNDARY + normalized.replace(' ', BOUNDARY)

        val n = s.length
        val best = FloatArray(n + 1) { Float.NEGATIVE_INFINITY }
        val prev = IntArray(n + 1) { -1 }
        val chosen = arrayOfNulls<String>(n + 1)
        best[0] = 0f
        val unkPenalty = -20f

        for (pos in 0 until n) {
            if (best[pos] == Float.NEGATIVE_INFINITY) continue
            val maxEnd = minOf(n, pos + maxPieceLen)
            var matched = false
            for (end in pos + 1..maxEnd) {
                val cand = s.substring(pos, end)
                val score = pieces[cand] ?: continue
                matched = true
                if (best[pos] + score > best[end]) {
                    best[end] = best[pos] + score
                    prev[end] = pos
                    chosen[end] = cand
                }
            }
            if (!matched) {
                // Unknown character: consume one code unit as an <unk> marker.
                val end = pos + 1
                if (best[pos] + unkPenalty > best[end]) {
                    best[end] = best[pos] + unkPenalty
                    prev[end] = pos
                    chosen[end] = null // null piece => <unk>
                }
            }
        }

        val out = ArrayDeque<String>()
        var pos = n
        while (pos > 0) {
            out.addFirst(chosen[pos] ?: "<unk>")
            pos = prev[pos]
        }
        return out.toList()
    }
}
