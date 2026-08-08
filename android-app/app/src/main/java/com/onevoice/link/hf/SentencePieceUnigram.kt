package com.onevoice.link.hf

import java.io.File
import java.text.Normalizer

/**
 * Small SentencePiece unigram encoder for the Helsinki-NLP Marian models.
 *
 * Marian maps piece strings through its shared vocab.json, so this parser only
 * needs piece text, score and type from ModelProto. It intentionally has no JNI
 * dependency and runs once per short VAD-delimited utterance.
 */
internal class SentencePieceUnigram private constructor(
    private val pieces: HashMap<String, Float>,
    private val maxPieceLen: Int,
) {
    companion object {
        private const val BOUNDARY = '▁'
        private val WHITESPACE = Regex("\\s+")

        fun load(spmFile: File): SentencePieceUnigram {
            val bytes = spmFile.readBytes()
            val pieces = HashMap<String, Float>(65_536)
            var maxLen = 1
            var i = 0

            fun readVarint(limit: Int = bytes.size): Long {
                var shift = 0
                var result = 0L
                while (true) {
                    check(i < limit) { "Truncated SentencePiece varint" }
                    val b = bytes[i++].toInt() and 0xff
                    result = result or ((b and 0x7f).toLong() shl shift)
                    if (b < 0x80) return result
                    shift += 7
                    check(shift < 64) { "Invalid SentencePiece varint" }
                }
            }

            while (i < bytes.size) {
                val key = readVarint()
                val field = (key shr 3).toInt()
                val wire = (key and 7).toInt()
                check(field > 0) { "Invalid SentencePiece field number" }
                if (field == 1 && wire == 2) {
                    val length = readVarint()
                    check(length <= bytes.size - i.toLong()) {
                        "Truncated SentencePiece entry"
                    }
                    val end = i + length.toInt()
                    var piece: String? = null
                    var score = 0f
                    var type = 1
                    while (i < end) {
                        val nestedKey = readVarint(end)
                        when (((nestedKey shr 3).toInt() shl 3) or (nestedKey and 7).toInt()) {
                            (1 shl 3) or 2 -> {
                                val stringLengthLong = readVarint(end)
                                check(stringLengthLong <= end - i.toLong()) {
                                    "Truncated SentencePiece text"
                                }
                                val stringLength = stringLengthLong.toInt()
                                piece = String(bytes, i, stringLength, Charsets.UTF_8)
                                i += stringLength
                            }
                            (2 shl 3) or 5 -> {
                                check(i + 4 <= end) { "Truncated SentencePiece score" }
                                score = Float.fromBits(
                                    (bytes[i].toInt() and 0xff) or
                                        ((bytes[i + 1].toInt() and 0xff) shl 8) or
                                        ((bytes[i + 2].toInt() and 0xff) shl 16) or
                                        ((bytes[i + 3].toInt() and 0xff) shl 24),
                                )
                                i += 4
                            }
                            (3 shl 3) or 0 -> type = readVarint(end).toInt()
                            else -> i = skipField(nestedKey, bytes, i, end)
                        }
                    }
                    check(i == end) { "SentencePiece entry exceeded its boundary" }
                    if (piece != null && (type == 1 || type == 4)) {
                        pieces[piece] = score
                        maxLen = maxOf(maxLen, piece.length)
                    }
                } else {
                    i = skipField(key, bytes, i, bytes.size)
                }
            }
            check(pieces.isNotEmpty()) { "No unigram pieces in ${spmFile.absolutePath}" }
            return SentencePieceUnigram(pieces, maxLen)
        }

        private fun skipField(
            key: Long,
            bytes: ByteArray,
            position: Int,
            limit: Int,
        ): Int {
            var i = position
            when ((key and 7).toInt()) {
                0 -> {
                    var count = 0
                    while (true) {
                        check(i < limit) { "Truncated SentencePiece varint field" }
                        val done = (bytes[i++].toInt() and 0x80) == 0
                        count++
                        check(count <= 10) { "Invalid SentencePiece varint field" }
                        if (done) break
                    }
                }
                1 -> {
                    check(8 <= limit - i) { "Truncated SentencePiece fixed64 field" }
                    i += 8
                }
                2 -> {
                    var shift = 0
                    var length = 0L
                    while (true) {
                        check(i < limit) { "Truncated SentencePiece field" }
                        val b = bytes[i++].toInt() and 0xff
                        length = length or ((b and 0x7f).toLong() shl shift)
                        if (b < 0x80) break
                        shift += 7
                        check(shift < 64) { "Invalid SentencePiece field length" }
                    }
                    check(length <= limit - i.toLong()) {
                        "Truncated SentencePiece field payload"
                    }
                    i += length.toInt()
                }
                5 -> {
                    check(4 <= limit - i) { "Truncated SentencePiece fixed32 field" }
                    i += 4
                }
                else -> error("Unsupported SentencePiece wire type ${key and 7}")
            }
            return i
        }
    }

    fun encodeToPieces(text: String): List<String> {
        val normalized = Normalizer.normalize(text.trim(), Normalizer.Form.NFKC)
            .replace(WHITESPACE, " ")
        if (normalized.isEmpty()) return emptyList()
        val input = BOUNDARY + normalized.replace(' ', BOUNDARY)

        val best = FloatArray(input.length + 1) { Float.NEGATIVE_INFINITY }
        val previous = IntArray(input.length + 1) { -1 }
        val chosen = arrayOfNulls<String>(input.length + 1)
        best[0] = 0f

        for (position in input.indices) {
            if (best[position] == Float.NEGATIVE_INFINITY) continue
            val maxEnd = minOf(input.length, position + maxPieceLen)
            var matched = false
            for (end in position + 1..maxEnd) {
                val candidate = input.substring(position, end)
                val score = pieces[candidate] ?: continue
                matched = true
                if (best[position] + score > best[end]) {
                    best[end] = best[position] + score
                    previous[end] = position
                    chosen[end] = candidate
                }
            }
            if (!matched) {
                // Consume an entire Unicode code point. Advancing one UTF-16
                // code unit would turn an unknown supplementary character into
                // two <unk> IDs and could leave the Viterbi path on a surrogate.
                val unknownEnd = position + Character.charCount(input.codePointAt(position))
                if (best[position] - 20f > best[unknownEnd]) {
                    best[unknownEnd] = best[position] - 20f
                    previous[unknownEnd] = position
                    chosen[unknownEnd] = null
                }
            }
        }

        val result = ArrayDeque<String>()
        var position = input.length
        while (position > 0) {
            val prior = previous[position]
            check(prior >= 0) { "SentencePiece segmentation failed at $position" }
            result.addFirst(chosen[position] ?: "<unk>")
            position = prior
        }
        return result.toList()
    }
}
