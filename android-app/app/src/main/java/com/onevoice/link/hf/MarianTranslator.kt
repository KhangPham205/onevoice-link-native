package com.onevoice.link.hf

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/** MarianMT on CPU/ORT with greedy decoding for short conversational turns. */
internal class MarianTranslator private constructor(
    private val environment: OrtEnvironment,
    private val encoder: OrtSession,
    private val decoder: OrtSession,
    private val sourceSentencePiece: SentencePieceUnigram,
    private val vocab: Map<String, Long>,
    private val idToToken: Map<Long, String>,
    private val eosId: Long,
    private val padId: Long,
    private val decoderStartId: Long,
    private val maxLength: Int,
) : AutoCloseable {
    companion object {
        private const val MAX_SOURCE_PIECES = 255

        fun load(directory: File): MarianTranslator {
            val environment = OrtEnvironment.getEnvironment()
            var encoder: OrtSession? = null
            var decoder: OrtSession? = null
            try {
                OrtSession.SessionOptions().use { options ->
                    options.setIntraOpNumThreads(2)
                    // Both sequence lengths change on every utterance/decode step.
                    // Do not let ORT retain arena/memory-pattern allocations sized
                    // for the largest prefix seen so far.
                    options.setMemoryPatternOptimization(false)
                    options.setCPUArenaAllocator(false)
                    encoder = environment.createSession(
                        File(directory, "encoder_model.onnx").absolutePath,
                        options,
                    )
                    decoder = environment.createSession(
                        File(directory, "decoder_model.onnx").absolutePath,
                        options,
                    )
                }

                val readyEncoder = checkNotNull(encoder)
                val readyDecoder = checkNotNull(decoder)
                require(
                    readyEncoder.inputNames.containsAll(setOf("input_ids", "attention_mask")),
                ) { "Unexpected Marian encoder inputs: ${readyEncoder.inputNames}" }
                require(
                    readyDecoder.inputNames.containsAll(
                        setOf("input_ids", "encoder_hidden_states", "encoder_attention_mask"),
                    ),
                ) { "Unexpected Marian decoder inputs: ${readyDecoder.inputNames}" }
                require(readyDecoder.outputNames.contains("logits")) {
                    "Unexpected Marian decoder outputs: ${readyDecoder.outputNames}"
                }

                val vocabJson = JSONObject(File(directory, "vocab.json").readText())
                val vocab = HashMap<String, Long>(vocabJson.length() * 2)
                val idToToken = HashMap<Long, String>(vocabJson.length() * 2)
                for (token in vocabJson.keys()) {
                    val id = vocabJson.getLong(token)
                    vocab[token] = id
                    idToToken[id] = token
                }
                val config = JSONObject(File(directory, "mt_config.json").readText())
                return MarianTranslator(
                    environment = environment,
                    encoder = readyEncoder,
                    decoder = readyDecoder,
                    sourceSentencePiece = SentencePieceUnigram.load(File(directory, "source.spm")),
                    vocab = vocab,
                    idToToken = idToToken,
                    eosId = config.getLong("eos_token_id"),
                    padId = config.getLong("pad_token_id"),
                    decoderStartId = config.getLong("decoder_start_token_id"),
                    // A VAD segment is conversational speech, not a document. This
                    // cap prevents a malformed decode from holding the pipeline for
                    // hundreds of CPU passes.
                    maxLength = config.optInt("max_length", 128).coerceIn(8, 128),
                )
            } catch (failure: Throwable) {
                runCatching { decoder?.close() }
                runCatching { encoder?.close() }
                throw failure
            }
        }
    }

    fun translate(text: String): String {
        if (text.isBlank()) return ""
        val unknownId = vocab["<unk>"] ?: 1L
        val sourcePieces = sourceSentencePiece.encodeToPieces(text)
        if (sourcePieces.isEmpty()) return ""
        val pieceCount = minOf(sourcePieces.size, MAX_SOURCE_PIECES)
        val sourceArray = LongArray(pieceCount + 1)
        for (index in 0 until pieceCount) {
            sourceArray[index] = vocab[sourcePieces[index]] ?: unknownId
        }
        sourceArray[pieceCount] = eosId

        val sourceShape = longArrayOf(1, sourceArray.size.toLong())
        val maskArray = LongArray(sourceArray.size) { 1L }

        OnnxTensor.createTensor(environment, LongBuffer.wrap(sourceArray), sourceShape).use { ids ->
            OnnxTensor.createTensor(environment, LongBuffer.wrap(maskArray), sourceShape).use { mask ->
                encoder.run(mapOf("input_ids" to ids, "attention_mask" to mask)).use { encoded ->
                    val hidden = encoded.get(0) as? OnnxTensor
                        ?: error("Marian encoder output is not a tensor")
                    val generated = LongArray(maxLength)
                    generated[0] = decoderStartId
                    var generatedSize = 1
                    while (generatedSize < maxLength) {
                        val decoderShape = longArrayOf(1, generatedSize.toLong())
                        val next = OnnxTensor.createTensor(
                            environment,
                            LongBuffer.wrap(generated, 0, generatedSize),
                            decoderShape,
                        ).use { decoderIds ->
                            decoder.run(
                                mapOf(
                                    "input_ids" to decoderIds,
                                    "encoder_hidden_states" to hidden,
                                    "encoder_attention_mask" to mask,
                                ),
                            ).use { output ->
                                val logits = output.get(0) as? OnnxTensor
                                    ?: error("Marian decoder output is not a tensor")
                                argmaxLastToken(logits)
                            }
                        }
                        if (next == eosId) break
                        generated[generatedSize++] = next
                    }
                    return detokenize(generated, generatedSize)
                }
            }
        }
    }

    private fun argmaxLastToken(logits: OnnxTensor): Long {
        val shape = logits.info.shape
        require(shape.size == 3 && shape[0] == 1L && shape[1] > 0L) {
            "Unexpected Marian logits shape: ${shape.contentToString()}"
        }
        require(shape[2] in 1L..Int.MAX_VALUE.toLong()) {
            "Invalid Marian vocabulary dimension: ${shape[2]}"
        }
        val vocabularySize = shape[2].toInt()
        val offsetLong = (shape[1] - 1L) * shape[2]
        require(offsetLong <= Int.MAX_VALUE - vocabularySize.toLong()) {
            "Marian logits tensor is too large"
        }
        val offset = offsetLong.toInt()
        val buffer = logits.floatBuffer
        require(offset + vocabularySize <= buffer.limit()) {
            "Marian logits buffer is shorter than its declared shape"
        }
        return argmax(buffer, offset, vocabularySize)
    }

    private fun argmax(logits: FloatBuffer, offset: Int, size: Int): Long {
        var best = -1
        var bestScore = Float.NEGATIVE_INFINITY
        for (index in 0 until size) {
            if (index.toLong() == padId) continue
            val score = logits.get(offset + index)
            if (!score.isNaN() && (best < 0 || score > bestScore)) {
                best = index
                bestScore = score
            }
        }
        check(best >= 0) { "Marian decoder returned no usable logits" }
        return best.toLong()
    }

    private fun detokenize(ids: LongArray, size: Int): String = buildString {
        for (index in 1 until size) {
            val id = ids[index]
            if (id == padId || id == eosId) continue
            val token = idToToken[id] ?: continue
            if (token != "<unk>") append(token)
        }
    }.replace('▁', ' ').trim()

    override fun close() {
        runCatching { decoder.close() }
        runCatching { encoder.close() }
    }
}
