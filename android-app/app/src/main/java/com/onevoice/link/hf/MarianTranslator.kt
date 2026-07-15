package com.onevoice.link.hf

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONObject
import java.io.File
import java.nio.LongBuffer

/**
 * MarianMT (Helsinki-NLP/opus-mt-vi-en) on ONNX Runtime, greedy decoding.
 *
 * Expects an optimum-exported model directory (produced by
 * tools/models/convert_marian.py):
 *   encoder_model.onnx   inputs: input_ids, attention_mask → last_hidden_state
 *   decoder_model.onnx   inputs: input_ids, encoder_hidden_states,
 *                                encoder_attention_mask → logits
 *   source.spm / target.spm / vocab.json / mt_config.json
 *
 * The decoder is the no-KV-cache variant: O(T²) per sentence but dependency-free
 * and plenty fast for short conversational segments on the emulator.
 */
class MarianTranslator private constructor(
    private val env: OrtEnvironment,
    private val encoder: OrtSession,
    private val decoder: OrtSession,
    private val sourceSp: SentencePieceUnigram,
    private val vocab: HashMap<String, Long>,
    private val idToToken: HashMap<Long, String>,
    private val eosId: Long,
    private val padId: Long,
    private val decoderStartId: Long,
    private val maxLength: Int,
) {
    companion object {
        fun load(dir: File): MarianTranslator {
            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
            val encoder = env.createSession(File(dir, "encoder_model.onnx").absolutePath, opts)
            val decoder = env.createSession(File(dir, "decoder_model.onnx").absolutePath, opts)

            val vocabJson = JSONObject(File(dir, "vocab.json").readText())
            val vocab = HashMap<String, Long>(vocabJson.length() * 2)
            val idToToken = HashMap<Long, String>(vocabJson.length() * 2)
            for (key in vocabJson.keys()) {
                val id = vocabJson.getLong(key)
                vocab[key] = id
                idToToken[id] = key
            }

            val cfg = JSONObject(File(dir, "mt_config.json").readText())
            return MarianTranslator(
                env = env,
                encoder = encoder,
                decoder = decoder,
                sourceSp = SentencePieceUnigram.load(File(dir, "source.spm")),
                vocab = vocab,
                idToToken = idToToken,
                eosId = cfg.getLong("eos_token_id"),
                padId = cfg.getLong("pad_token_id"),
                decoderStartId = cfg.getLong("decoder_start_token_id"),
                maxLength = cfg.optInt("max_length", 128),
            )
        }
    }

    /** Translate one Vietnamese segment to English. Blocking; call off-main. */
    fun translate(text: String): String {
        if (text.isBlank()) return ""
        val unkId = vocab["<unk>"] ?: 1L
        val srcIds = sourceSp.encodeToPieces(text).map { vocab[it] ?: unkId } + eosId
        if (srcIds.size <= 1) return ""

        val srcShape = longArrayOf(1, srcIds.size.toLong())
        val srcArr = srcIds.toLongArray()
        val maskArr = LongArray(srcIds.size) { 1L }

        OnnxTensor.createTensor(env, LongBuffer.wrap(srcArr), srcShape).use { inputIds ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(maskArr), srcShape).use { attnMask ->
                encoder.run(
                    mapOf("input_ids" to inputIds, "attention_mask" to attnMask),
                ).use { encOut ->
                    val hidden = encOut.get(0) as OnnxTensor

                    val generated = ArrayList<Long>(maxLength).apply { add(decoderStartId) }
                    while (generated.size < maxLength) {
                        val decShape = longArrayOf(1, generated.size.toLong())
                        val nextId = OnnxTensor.createTensor(
                            env, LongBuffer.wrap(generated.toLongArray()), decShape,
                        ).use { decIds ->
                            decoder.run(
                                mapOf(
                                    "input_ids" to decIds,
                                    "encoder_hidden_states" to hidden,
                                    "encoder_attention_mask" to attnMask,
                                ),
                            ).use { decOut ->
                                @Suppress("UNCHECKED_CAST")
                                val logits = (decOut.get(0) as OnnxTensor).value
                                    as Array<Array<FloatArray>>
                                argmax(logits[0].last())
                            }
                        }
                        if (nextId == eosId) break
                        generated.add(nextId)
                    }
                    return detokenize(generated.drop(1))
                }
            }
        }
    }

    private fun argmax(logits: FloatArray): Long {
        var best = 0
        for (i in 1 until logits.size) if (logits[i] > logits[best]) best = i
        return best.toLong()
    }

    private fun detokenize(ids: List<Long>): String {
        val sb = StringBuilder()
        for (id in ids) {
            if (id == padId || id == eosId) continue
            val tok = idToToken[id] ?: continue
            if (tok == "<unk>") continue
            sb.append(tok)
        }
        return sb.toString().replace('▁', ' ').trim()
    }

    fun close() {
        runCatching { encoder.close() }
        runCatching { decoder.close() }
    }
}
