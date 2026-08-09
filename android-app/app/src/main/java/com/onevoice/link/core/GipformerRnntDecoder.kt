package com.onevoice.link.core

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/** CPU decoder/joiner for the Gipformer encoder context that executes on HTP. */
internal class GipformerRnntDecoder private constructor(
    private val environment: OrtEnvironment,
    private val decoder: OrtSession,
    private val joiner: OrtSession,
    private val tokens: List<String>,
) : Closeable {
    companion object {
        const val FEATURE_FRAMES = 1000
        const val FEATURE_DIM = 80
        const val ENCODER_FRAMES = 248
        const val ENCODER_DIM = 512

        private const val CONTEXT_SIZE = 2
        private const val VOCAB_SIZE = 2000
        private const val BLANK_ID = 0
        private const val UNK_ID = 2

        fun load(directionRoot: File): GipformerRnntDecoder {
            val decoderFile = File(directionRoot, "asr_gipformer_decoder.onnx")
            val joinerFile = File(directionRoot, "asr_gipformer_joiner.onnx")
            val tokensFile = File(directionRoot, "asr_gipformer_tokens.txt")
            listOf(decoderFile, joinerFile, tokensFile).forEach { file ->
                require(file.isFile && file.length() > 0L) {
                    "Missing Gipformer artifact: ${file.absolutePath}"
                }
            }

            val environment = OrtEnvironment.getEnvironment()
            var decoder: OrtSession? = null
            var joiner: OrtSession? = null
            try {
                OrtSession.SessionOptions().use { options ->
                    // These two networks are only ~9 MB combined. One thread and
                    // no retained arena keep Hybrid memory bounded while HTP owns
                    // the 65 M-parameter encoder.
                    options.setIntraOpNumThreads(1)
                    options.setInterOpNumThreads(1)
                    options.setMemoryPatternOptimization(false)
                    options.setCPUArenaAllocator(false)
                    decoder = environment.createSession(decoderFile.absolutePath, options)
                    joiner = environment.createSession(joinerFile.absolutePath, options)
                }
                val readyDecoder = checkNotNull(decoder)
                val readyJoiner = checkNotNull(joiner)
                validateDecoder(readyDecoder)
                validateJoiner(readyJoiner)
                val tokens = parseTokens(tokensFile)
                return GipformerRnntDecoder(
                    environment = environment,
                    decoder = readyDecoder,
                    joiner = readyJoiner,
                    tokens = tokens,
                )
            } catch (failure: Throwable) {
                runCatching { joiner?.close() }
                runCatching { decoder?.close() }
                throw failure
            }
        }

        private fun validateDecoder(session: OrtSession) {
            require(session.inputNames == setOf("y") &&
                session.outputNames == setOf("decoder_out")) {
                "Unexpected Gipformer decoder contract: ${session.inputNames} -> " +
                    session.outputNames
            }
            val input = session.inputInfo.getValue("y").info as? TensorInfo
                ?: error("Gipformer decoder y is not a tensor")
            val output = session.outputInfo.getValue("decoder_out").info as? TensorInfo
                ?: error("Gipformer decoder_out is not a tensor")
            require(input.shape.contentEquals(longArrayOf(-1L, CONTEXT_SIZE.toLong()))) {
                "Unexpected Gipformer decoder input shape: ${input.shape.contentToString()}"
            }
            require(output.shape.contentEquals(longArrayOf(-1L, ENCODER_DIM.toLong()))) {
                "Unexpected Gipformer decoder output shape: ${output.shape.contentToString()}"
            }
            require(session.metadata.customMetadata["context_size"] == CONTEXT_SIZE.toString() &&
                session.metadata.customMetadata["vocab_size"] == VOCAB_SIZE.toString()) {
                "Unexpected Gipformer decoder metadata: ${session.metadata.customMetadata}"
            }
        }

        private fun validateJoiner(session: OrtSession) {
            require(session.inputNames == setOf("encoder_out", "decoder_out") &&
                session.outputNames == setOf("logit")) {
                "Unexpected Gipformer joiner contract: ${session.inputNames} -> " +
                    session.outputNames
            }
            session.inputInfo.forEach { (name, node) ->
                val info = node.info as? TensorInfo
                    ?: error("Gipformer joiner $name is not a tensor")
                require(info.shape.contentEquals(longArrayOf(-1L, ENCODER_DIM.toLong()))) {
                    "Unexpected Gipformer joiner $name shape: ${info.shape.contentToString()}"
                }
            }
            val output = session.outputInfo.getValue("logit").info as? TensorInfo
                ?: error("Gipformer joiner logit is not a tensor")
            require(output.shape.contentEquals(longArrayOf(-1L, VOCAB_SIZE.toLong()))) {
                "Unexpected Gipformer joiner output shape: ${output.shape.contentToString()}"
            }
        }

        private fun parseTokens(file: File): List<String> {
            val indexed = arrayOfNulls<String>(VOCAB_SIZE)
            file.forEachLine { line ->
                val separator = line.lastIndexOf(' ')
                require(separator > 0) { "Bad Gipformer token row: $line" }
                val id = line.substring(separator + 1).trim().toInt()
                require(id in indexed.indices && indexed[id] == null) {
                    "Invalid/duplicate Gipformer token ID $id"
                }
                indexed[id] = line.substring(0, separator)
            }
            require(indexed.all { it != null } && indexed[BLANK_ID] == "<blk>" &&
                indexed[UNK_ID] == "<unk>") {
                "Unexpected Gipformer vocabulary in ${file.absolutePath}"
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

    /** sherpa-compatible greedy search: at most one emitted symbol per frame. */
    fun decode(encoderOutput: ByteBuffer, validFrames: Int): String {
        require(encoderOutput.isDirect &&
            encoderOutput.capacity() >= ENCODER_FRAMES * ENCODER_DIM * Float.SIZE_BYTES) {
            "Gipformer encoder output must hold [1,248,512] FLOAT32"
        }
        require(validFrames in 1..ENCODER_FRAMES) {
            "Invalid Gipformer encoder length $validFrames"
        }
        val source = encoderOutput.order(ByteOrder.nativeOrder()).asFloatBuffer()
        val emitted = ArrayList<Int>(validFrames / 2)

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
            val token = runJoiner()
            // sherpa treats both blank and unknown as non-emitting symbols.
            if (token != BLANK_ID && token != UNK_ID) {
                emitted += token
                context.put(0, context.get(1))
                context.put(1, token.toLong())
                runDecoder()
            }
        }

        return buildString {
            emitted.forEach { id ->
                val piece = tokens[id]
                if (piece != "<blk>" && piece != "<unk>" && piece != "<sos/eos>") {
                    append(piece)
                }
            }
        }.replace('▁', ' ').trim().lowercase(Locale.ROOT)
    }

    private fun runDecoder() {
        decoder.run(mapOf("y" to contextTensor)).use { result ->
            val output = result.get(0) as? OnnxTensor
                ?: error("Gipformer decoder returned a non-tensor")
            val values = output.floatBuffer
            require(values.remaining() == ENCODER_DIM) {
                "Gipformer decoder returned ${values.remaining()} values"
            }
            repeat(ENCODER_DIM) { index -> decoderFrame.put(index, values.get(index)) }
        }
    }

    private fun runJoiner(): Int = joiner.run(
        mapOf("encoder_out" to encoderTensor, "decoder_out" to decoderTensor),
    ).use { result ->
        val output = result.get(0) as? OnnxTensor
            ?: error("Gipformer joiner returned a non-tensor")
        val logits = output.floatBuffer
        require(logits.remaining() == VOCAB_SIZE) {
            "Gipformer joiner returned ${logits.remaining()} logits"
        }
        var best = BLANK_ID
        var bestScore = Float.NEGATIVE_INFINITY
        repeat(VOCAB_SIZE) { id ->
            val score = logits.get(id)
            if (!score.isNaN() && score > bestScore) {
                best = id
                bestScore = score
            }
        }
        best
    }

    override fun close() {
        decoderTensor.close()
        encoderTensor.close()
        contextTensor.close()
        joiner.close()
        decoder.close()
    }
}

private fun direct(bytes: Int): ByteBuffer =
    ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
