package com.onevoice.link.core

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln

/**
 * Greedy RNN-T hypothesis plus the length-normalized acoustic confidence used
 * to compare the VI and EN recognizers.
 *
 * Only lexical tokens contribute to the score. Blank/unknown are
 * non-emitting, while the combined `<sos/eos>` symbol is excluded from both
 * text and confidence even if the joiner selects it.
 */
internal data class AsrHypothesis(
    val transcript: String,
    val rawLogProb: Double,
    val tokenCount: Int,
    val normalizedLogProb: Double,
    /**
     * Emitted lexical tokens with their window-relative emission sample, for
     * stitching windows of one long turn. Diagnostic alignment only: it never
     * enters [rawLogProb] or [normalizedLogProb], so routing is unchanged.
     */
    val tokens: List<AsrToken> = emptyList(),
)

/** CPU decoder/joiner shared by the GIPFormer-VI and Zipformer-EN HTP encoders. */
internal class ZipformerFamilyRnntDecoder private constructor(
    private val environment: OrtEnvironment,
    private val decoder: OrtSession,
    private val joiner: OrtSession,
    private val tokens: List<String>,
    private val vocabSize: Int,
) : Closeable {
    companion object {
        const val FEATURE_FRAMES = 1000
        const val FEATURE_DIM = 80
        const val ENCODER_FRAMES = 248
        const val ENCODER_DIM = 512

        /**
         * Encoder frame -> window sample. The exported length graph is
         * (((T - 7) / 2) + 1) / 2 over 10 ms features, so one frame spans four
         * features (640 samples) and frame j centres near feature 4j + 3.
         */
        const val SAMPLES_PER_ENCODER_FRAME = 640
        const val ENCODER_FRAME_OFFSET_SAMPLES = 480

        private const val CONTEXT_SIZE = 2
        private const val BLANK_ID = 0
        private const val UNK_ID = 2

        fun load(directionRoot: File, artifactPrefix: String): ZipformerFamilyRnntDecoder {
            require(artifactPrefix.matches(Regex("asr_[a-z0-9_]+"))) {
                "Unsafe RNN-T artifact prefix: $artifactPrefix"
            }
            val decoderFile = File(directionRoot, "${artifactPrefix}_decoder.onnx")
            val joinerFile = File(directionRoot, "${artifactPrefix}_joiner.onnx")
            val tokensFile = File(directionRoot, "${artifactPrefix}_tokens.txt")
            listOf(decoderFile, joinerFile, tokensFile).forEach { file ->
                require(file.isFile && file.length() > 0L) {
                    "Missing RNN-T host artifact: ${file.absolutePath}"
                }
            }

            val environment = OrtEnvironment.getEnvironment()
            var decoder: OrtSession? = null
            var joiner: OrtSession? = null
            try {
                OrtSession.SessionOptions().use { options ->
                    // These two networks are only ~9 MB combined. One thread and
                    // no retained arena keep Hybrid memory bounded while HTP owns
                    // the much larger encoder.
                    options.setIntraOpNumThreads(1)
                    options.setInterOpNumThreads(1)
                    options.setMemoryPatternOptimization(false)
                    options.setCPUArenaAllocator(false)
                    decoder = environment.createSession(decoderFile.absolutePath, options)
                    joiner = environment.createSession(joinerFile.absolutePath, options)
                }
                val readyDecoder = checkNotNull(decoder)
                val readyJoiner = checkNotNull(joiner)
                val tokens = parseTokens(tokensFile)
                val vocabSize = decoderVocabularySize(readyDecoder)
                require(tokens.size >= vocabSize) {
                    "RNN-T token table has ${tokens.size} rows, model needs $vocabSize"
                }
                validateDecoder(readyDecoder, vocabSize)
                validateJoiner(readyJoiner, vocabSize)
                return ZipformerFamilyRnntDecoder(
                    environment = environment,
                    decoder = readyDecoder,
                    joiner = readyJoiner,
                    // Some icefall exports append disambiguation symbols such
                    // as #0/#1 after the joiner vocabulary. They are tokenizer
                    // metadata, not logits, and must never extend argmax.
                    tokens = tokens.take(vocabSize),
                    vocabSize = vocabSize,
                )
            } catch (failure: Throwable) {
                runCatching { joiner?.close() }
                runCatching { decoder?.close() }
                throw failure
            }
        }

        private fun validateDecoder(session: OrtSession, vocabSize: Int) {
            require(session.inputNames == setOf("y") &&
                session.outputNames == setOf("decoder_out")) {
                "Unexpected RNN-T decoder contract: ${session.inputNames} -> " +
                    session.outputNames
            }
            val input = session.inputInfo.getValue("y").info as? TensorInfo
                ?: error("RNN-T decoder y is not a tensor")
            val output = session.outputInfo.getValue("decoder_out").info as? TensorInfo
                ?: error("RNN-T decoder_out is not a tensor")
            require(input.type == OnnxJavaType.INT64 &&
                input.shape.contentEquals(longArrayOf(-1L, CONTEXT_SIZE.toLong()))) {
                "Unexpected RNN-T decoder input: type=${input.type} " +
                    "shape=${input.shape.contentToString()}"
            }
            require(output.type == OnnxJavaType.FLOAT &&
                output.shape.contentEquals(longArrayOf(-1L, ENCODER_DIM.toLong()))) {
                "Unexpected RNN-T decoder output: type=${output.type} " +
                    "shape=${output.shape.contentToString()}"
            }
            require(session.metadata.customMetadata["context_size"] == CONTEXT_SIZE.toString() &&
                session.metadata.customMetadata["vocab_size"] == vocabSize.toString()) {
                "Unexpected RNN-T decoder metadata: ${session.metadata.customMetadata}"
            }
        }

        private fun decoderVocabularySize(session: OrtSession): Int =
            session.metadata.customMetadata["vocab_size"]?.toIntOrNull()
                ?.takeIf { it > UNK_ID }
                ?: error(
                    "Missing/invalid RNN-T vocab_size metadata: " +
                        session.metadata.customMetadata,
                )

        private fun validateJoiner(session: OrtSession, vocabSize: Int) {
            require(session.inputNames == setOf("encoder_out", "decoder_out") &&
                session.outputNames == setOf("logit")) {
                "Unexpected RNN-T joiner contract: ${session.inputNames} -> " +
                    session.outputNames
            }
            session.inputInfo.forEach { (name, node) ->
                val info = node.info as? TensorInfo
                    ?: error("RNN-T joiner $name is not a tensor")
                require(info.type == OnnxJavaType.FLOAT &&
                    info.shape.contentEquals(longArrayOf(-1L, ENCODER_DIM.toLong()))) {
                    "Unexpected RNN-T joiner $name: type=${info.type} " +
                        "shape=${info.shape.contentToString()}"
                }
            }
            val output = session.outputInfo.getValue("logit").info as? TensorInfo
                ?: error("RNN-T joiner logit is not a tensor")
            require(output.type == OnnxJavaType.FLOAT &&
                output.shape.contentEquals(longArrayOf(-1L, vocabSize.toLong()))) {
                "Unexpected RNN-T joiner output: type=${output.type} " +
                    "shape=${output.shape.contentToString()}"
            }
            require(
                session.metadata.customMetadata["joiner_dim"] == ENCODER_DIM.toString(),
            ) {
                "Unexpected RNN-T joiner metadata: ${session.metadata.customMetadata}"
            }
        }

        private fun parseTokens(file: File): List<String> {
            val rows = file.readLines().filter(String::isNotBlank)
            require(rows.size >= 4) { "RNN-T vocabulary is empty: ${file.absolutePath}" }
            val indexed = arrayOfNulls<String>(rows.size)
            rows.forEach { line ->
                val separator = line.lastIndexOf(' ')
                require(separator > 0) { "Bad RNN-T token row: $line" }
                val id = line.substring(separator + 1).trim().toInt()
                require(id in indexed.indices && indexed[id] == null) {
                    "Invalid/duplicate RNN-T token ID $id"
                }
                indexed[id] = line.substring(0, separator)
            }
            require(indexed.all { it != null } && indexed[BLANK_ID] == "<blk>" &&
                indexed[UNK_ID] == "<unk>") {
                "Unexpected RNN-T vocabulary in ${file.absolutePath}"
            }
            return indexed.map { checkNotNull(it) }
        }
    }

    private val contextBytes = direct(CONTEXT_SIZE * Long.SIZE_BYTES)
    private val encoderFrameBytes = direct(ENCODER_DIM * Float.SIZE_BYTES)
    private val decoderFrameBytes = direct(ENCODER_DIM * Float.SIZE_BYTES)
    private val context = contextBytes.asLongBuffer()
    private val encoderFrame = encoderFrameBytes.asFloatBuffer()
    private val decoderFrame = decoderFrameBytes.asFloatBuffer()
    private val contextTensor = OnnxTensor.createTensor(
        environment,
        context,
        longArrayOf(1L, CONTEXT_SIZE.toLong()),
    )
    private val encoderTensor = OnnxTensor.createTensor(
        environment,
        encoderFrame,
        longArrayOf(1L, ENCODER_DIM.toLong()),
    )
    private val decoderTensor = OnnxTensor.createTensor(
        environment,
        decoderFrame,
        longArrayOf(1L, ENCODER_DIM.toLong()),
    )

    /** Compatibility wrapper for callers that only need the transcript. */
    fun decode(encoderOutput: ByteBuffer, validFrames: Int): String =
        decodeScored(encoderOutput, validFrames).transcript

    /** sherpa-compatible greedy search: at most one emitted symbol per frame. */
    fun decodeScored(encoderOutput: ByteBuffer, validFrames: Int): AsrHypothesis {
        require(encoderOutput.isDirect &&
            encoderOutput.capacity() >= ENCODER_FRAMES * ENCODER_DIM * Float.SIZE_BYTES) {
            "RNN-T encoder output must hold [1,248,512] FLOAT32"
        }
        require(validFrames in 1..ENCODER_FRAMES) {
            "Invalid RNN-T encoder length $validFrames"
        }
        val source = encoderOutput.order(ByteOrder.nativeOrder()).asFloatBuffer()
        val emitted = ArrayList<Int>(validFrames / 2)
        val emittedFrames = ArrayList<Int>(validFrames / 2)
        var rawLogProb = 0.0

        // This is the exact stateless-transducer seed used by sherpa-onnx.
        // The exported decoder clips the leading -1 padding internally.
        context.put(0, -1L)
        context.put(1, BLANK_ID.toLong())
        runDecoder()

        repeat(validFrames) { frame ->
            val base = frame * ENCODER_DIM
            repeat(ENCODER_DIM) { index ->
                encoderFrame.put(index, source.get(base + index))
            }
            val selected = runJoiner()
            val token = selected.id
            // sherpa treats both blank and unknown as non-emitting symbols.
            if (token != BLANK_ID && token != UNK_ID) {
                // Preserve the decoder state transition used by the previous
                // implementation, but never let a control symbol inflate the
                // lexical token count or influence language routing.
                if (tokens[token] != "<sos/eos>") {
                    emitted += token
                    emittedFrames += frame
                    rawLogProb += selected.logProbability
                }
                context.put(0, context.get(1))
                context.put(1, token.toLong())
                runDecoder()
            }
        }

        val transcript = buildString {
            emitted.forEach { id ->
                val piece = tokens[id]
                if (piece != "<blk>" && piece != "<unk>" && piece != "<sos/eos>") {
                    append(piece)
                }
            }
        }.replace('▁', ' ').trim().lowercase(Locale.ROOT)
        return AsrHypothesis(
            transcript = transcript,
            rawLogProb = rawLogProb,
            tokenCount = emitted.size,
            normalizedLogProb = if (emitted.isEmpty()) {
                Double.NEGATIVE_INFINITY
            } else {
                rawLogProb / emitted.size
            },
            tokens = emitted.indices.mapNotNull { index ->
                val piece = tokens[emitted[index]]
                if (piece == "<blk>" || piece == "<unk>") return@mapNotNull null
                AsrToken(
                    piece = piece.lowercase(Locale.ROOT),
                    sampleOffset = emittedFrames[index] * SAMPLES_PER_ENCODER_FRAME +
                        ENCODER_FRAME_OFFSET_SAMPLES,
                )
            },
        )
    }

    private fun runDecoder() {
        decoder.run(mapOf("y" to contextTensor)).use { result ->
            val output = result.get(0) as? OnnxTensor
                ?: error("RNN-T decoder returned a non-tensor")
            val values = output.floatBuffer
            require(values.remaining() == ENCODER_DIM) {
                "RNN-T decoder returned ${values.remaining()} values"
            }
            repeat(ENCODER_DIM) { index -> decoderFrame.put(index, values.get(index)) }
        }
    }

    private fun runJoiner(): RnntTokenScore = joiner.run(
        mapOf("encoder_out" to encoderTensor, "decoder_out" to decoderTensor),
    ).use { result ->
        val output = result.get(0) as? OnnxTensor
            ?: error("RNN-T joiner returned a non-tensor")
        val logits = output.floatBuffer
        require(logits.remaining() == vocabSize) {
            "RNN-T joiner returned ${logits.remaining()} logits"
        }
        argmaxLogProbability(logits, vocabSize)
    }

    override fun close() {
        decoderTensor.close()
        encoderTensor.close()
        contextTensor.close()
        joiner.close()
        decoder.close()
    }
}

internal data class RnntTokenScore(
    val id: Int,
    val logProbability: Double,
)

/**
 * Returns the argmax token and its log-softmax probability without ever
 * exponentiating an unshifted logit. Non-finite entries are ignored; an output
 * with no finite entry is treated as an inference failure.
 */
internal fun argmaxLogProbability(logits: FloatBuffer, vocabSize: Int): RnntTokenScore {
    require(vocabSize > 0 && logits.remaining() >= vocabSize) {
        "RNN-T joiner needs $vocabSize logits, got ${logits.remaining()}"
    }
    val offset = logits.position()
    var bestId = -1
    var maximum = Double.NEGATIVE_INFINITY
    repeat(vocabSize) { id ->
        val value = logits.get(offset + id).toDouble()
        if (value.isFinite() && value > maximum) {
            bestId = id
            maximum = value
        }
    }
    check(bestId >= 0) { "RNN-T joiner returned no finite logit" }

    var shiftedExpSum = 0.0
    repeat(vocabSize) { id ->
        val value = logits.get(offset + id).toDouble()
        if (value.isFinite()) shiftedExpSum += exp(value - maximum)
    }
    check(shiftedExpSum.isFinite() && shiftedExpSum >= 1.0) {
        "RNN-T joiner log-sum-exp is invalid: $shiftedExpSum"
    }
    val logNormalizer = maximum + ln(shiftedExpSum)
    return RnntTokenScore(
        id = bestId,
        logProbability = maximum - logNormalizer,
    )
}

private fun direct(bytes: Int): ByteBuffer =
    ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
