package com.onevoice.link.core

import android.util.Log
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.onevoice.link.hf.SentencePieceUnigram
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt

/** Host glue around the separately compiled QNN contexts for VI -> EN. */
internal class NpuPipeline private constructor(
    private val asrTokens: List<String>,
    private val translator: QnnMarianTranslator,
    private val ttsTokenIds: Set<Int>,
    private val voices: FileChannel?,
    private val ttsFrontend: OfflineTts?,
    private val cpuTts: OfflineTts?,
) : Closeable {
    companion object {
        private const val ASR_FEATURE_BYTES = 1 * 1001 * 80 * Float.SIZE_BYTES
        private const val ASR_LOGIT_BYTES = 1 * 126 * 1025 * Float.SIZE_BYTES
        private const val ASR_HOP_SAMPLES = 160
        private const val ASR_SUBSAMPLING = 8
        private const val ASR_OUTPUT_FRAMES = 126
        private const val MAX_TTS_FRAMES = 750 // 18.75 seconds @ 24 kHz
        private const val TTS_BUCKET_FRAMES = 25
        private const val TTS_BUCKET_STRIDE_FRAMES = 20
        private const val TTS_SAMPLES_PER_FRAME = 600
        private const val BETWEEN_CHUNK_SILENCE_SAMPLES = 2_880 // 120 ms at 24 kHz
        private const val TAG = "OneVoiceNpuPipeline"

        fun load(
            directionRoot: File,
            useCpuTts: Boolean = false,
            cpuTtsModel: File? = null,
        ): NpuPipeline {
            val requiredNames = buildList {
                addAll(
                    listOf(
                        "asr_tokens.txt", "mt_source.spm", "mt_vocab.json", "mt_config.json",
                        "tts_tokens.txt", "tts_voices.bin",
                    ),
                )
                if (!useCpuTts) add("tts_frontend.onnx")
            }
            val required = requiredNames.map { File(directionRoot, it) }.toMutableList()
            if (useCpuTts) {
                required += cpuTtsModel ?: File(directionRoot, "tts_cpu.onnx")
            }
            val missing = required.filterNot(File::isFile)
            val espeakData = File(directionRoot, "espeak-ng-data")
            val espeakMissing = listOf("phontab", "phondata", "phonindex", "en_dict")
                .map { File(espeakData, it) }
                .filterNot(File::isFile)
            require(missing.isEmpty()) {
                "Missing NPU frontend assets: ${missing.joinToString { it.name }}. " +
                    "Run tools/models/push-npu-models.sh vi-en R5CW72TP4EP."
            }
            require(espeakMissing.isEmpty()) {
                "Missing eSpeak frontend data: ${espeakMissing.joinToString { it.name }}. " +
                    "Run tools/models/push-npu-models.sh vi-en R5CW72TP4EP."
            }
            val asrIndex = parseIndexedTokens(File(directionRoot, "asr_tokens.txt"))
            val asrTokens = arrayOfNulls<String>(asrIndex.size)
            asrIndex.forEach { (token, id) ->
                if (id in asrTokens.indices) asrTokens[id] = token
            }
            require(asrTokens.size == 1025 && asrTokens.last() == "<blk>") {
                "Unexpected ASR vocabulary (${asrTokens.size} tokens)"
            }
            val ttsTokens = parseIndexedTokens(File(directionRoot, "tts_tokens.txt"))
            val kokoroConfig = { modelPath: String ->
                OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        kokoro = OfflineTtsKokoroModelConfig(
                            model = modelPath,
                            voices = File(directionRoot, "tts_voices.bin").absolutePath,
                            tokens = File(directionRoot, "tts_tokens.txt").absolutePath,
                            dataDir = espeakData.absolutePath,
                        ),
                        numThreads = if (useCpuTts) 2 else 1,
                    ),
                    maxNumSentences = 1,
                    silenceScale = 1.0f,
                )
            }
            return NpuPipeline(
                asrTokens = asrTokens.map { it ?: "<unk>" },
                translator = QnnMarianTranslator.load(directionRoot),
                ttsTokenIds = ttsTokens.values.toSet(),
                voices = if (useCpuTts) null else
                    FileInputStream(File(directionRoot, "tts_voices.bin")).channel,
                ttsFrontend = if (useCpuTts) null else OfflineTts(
                    config = kokoroConfig(File(directionRoot, "tts_frontend.onnx").absolutePath),
                ),
                cpuTts = if (useCpuTts) OfflineTts(
                    config = kokoroConfig(requireNotNull(cpuTtsModel).absolutePath),
                ) else null,
            )
        }

        private fun parseIndexedTokens(file: File): Map<String, Int> = buildMap {
            file.forEachLine { line ->
                val separator = line.lastIndexOf(' ')
                require(separator >= 0) { "Bad token row in ${file.name}: $line" }
                val token = line.substring(0, separator)
                val id = line.substring(separator + 1).trim().toInt()
                put(token, id)
            }
        }
    }

    private val asrFeatures = direct(ASR_FEATURE_BYTES)
    private val asrLogits = direct(ASR_LOGIT_BYTES)
    private val ttsIds = direct(512 * Int.SIZE_BYTES)
    private val ttsStyle = direct(256 * Float.SIZE_BYTES)
    private val ttsD = direct(512 * 640 * Float.SIZE_BYTES)
    private val ttsDurations = direct(512 * Float.SIZE_BYTES)
    private val ttsTEn = direct(512 * 512 * Float.SIZE_BYTES)
    private val ttsStyleHead = direct(128 * Float.SIZE_BYTES)
    private val ttsStyleTail = direct(128 * Float.SIZE_BYTES)
    private val ttsEn = direct(25 * 640 * Float.SIZE_BYTES)
    private val ttsAsr = direct(512 * 25 * Float.SIZE_BYTES)
    private val ttsF0 = direct(50 * Float.SIZE_BYTES)
    private val ttsNoise = direct(50 * Float.SIZE_BYTES)
    private val ttsHar = direct(22 * 3001 * Float.SIZE_BYTES)
    private val ttsWaveform = direct(15_000 * Float.SIZE_BYTES)
    private val voiceScratch = direct(256 * Float.SIZE_BYTES)

    fun recognize(samples: FloatArray): String {
        require(samples.isNotEmpty() && samples.size <= 160_000) {
            "NPU ASR accepts 1..160000 samples"
        }
        checkNative(OneVoiceNative.nativeExtractAsrFeatures(samples, asrFeatures))
        execute("asr", arrayOf(asrFeatures), arrayOf(asrLogits))
        val logits = asrLogits.asFloatBuffer()
        // The graph is statically padded to 10 seconds/126 CTC steps and has no
        // external length tensor. Do not decode logits belonging solely to the
        // zero-padded tail; those steps can repeat an earlier phrase after the
        // user has already stopped speaking.
        val validFeatureFrames = (samples.size + ASR_HOP_SAMPLES - 1) / ASR_HOP_SAMPLES
        val validLogitFrames = ((validFeatureFrames + ASR_SUBSAMPLING - 1) /
            ASR_SUBSAMPLING).coerceIn(1, ASR_OUTPUT_FRAMES)
        val ids = ArrayList<Int>(validLogitFrames)
        var previous = -1
        repeat(validLogitFrames) { time ->
            val offset = time * 1025
            var best = 0
            var bestScore = Float.NEGATIVE_INFINITY
            repeat(1025) { id ->
                val score = logits.get(offset + id)
                if (!score.isNaN() && score > bestScore) {
                    best = id
                    bestScore = score
                }
            }
            if (best != 1024 && best != previous) ids += best
            // Blank resets an adjacent-repeat run, per CTC greedy decoding.
            previous = best
        }
        return buildString {
            ids.forEach { id ->
                val piece = asrTokens.getOrNull(id) ?: return@forEach
                if (piece != "<unk>" && piece != "<blk>") append(piece)
            }
        }.replace('▁', ' ').trim().also { transcript ->
            Log.i(
                TAG,
                "ASR samples=${samples.size} featureFrames=$validFeatureFrames " +
                    "ctcFrames=$validLogitFrames transcript=\"$transcript\"",
            )
        }
    }

    fun translate(text: String): String = translator.translate(text)

    fun synthesize(text: String): FloatArray {
        cpuTts?.let { synthesizer ->
            val startedNs = System.nanoTime()
            val generated = synthesizer.generate(text, 0, 1.0f)
            require(generated.sampleRate == 24_000) {
                "CPU Kokoro returned ${generated.sampleRate} Hz, expected 24000 Hz"
            }
            require(generated.samples.all(Float::isFinite)) {
                "CPU Kokoro returned non-finite waveform samples"
            }
            Log.i(
                TAG,
                "CPU whole-utterance TTS textChars=${text.length} " +
                    "audioSamples=${generated.samples.size} " +
                    "durationMs=${generated.samples.size * 1_000L / generated.sampleRate} " +
                    "inferenceMs=${(System.nanoTime() - startedNs) / 1_000_000L}",
            )
            return generated.samples
        }
        val tokenChunks = tokenizeTts(text)
        if (tokenChunks.isEmpty()) return FloatArray(0)
        val audioChunks = tokenChunks.map(::synthesizeTokens)
        val silenceSamples = BETWEEN_CHUNK_SILENCE_SAMPLES *
            (audioChunks.size - 1).coerceAtLeast(0)
        val result = FloatArray(audioChunks.sumOf(FloatArray::size) + silenceSamples)
        var offset = 0
        audioChunks.forEachIndexed { index, audio ->
            audio.copyInto(result, offset)
            offset += audio.size
            if (index != audioChunks.lastIndex) offset += BETWEEN_CHUNK_SILENCE_SAMPLES
        }
        Log.i(
            TAG,
            "TTS textChars=${text.length} tokenChunks=${tokenChunks.size} " +
                "audioSamples=${result.size} durationMs=${result.size * 1_000L / 24_000L}",
        )
        return result
    }

    private fun synthesizeTokens(tokenIds: IntArray): FloatArray {
        require(tokenIds.size in 3..512 && tokenIds.first() == 0 && tokenIds.last() == 0) {
            "Bad Kokoro token sequence (${tokenIds.size} IDs)"
        }
        val ids = IntArray(512)
        tokenIds.copyInto(ids)
        val payloadSize = tokenIds.size - 2
        val validTokens = tokenIds.size

        val style = readStyle(payloadSize)
        ttsIds.asIntBuffer().put(ids)
        ttsStyle.asFloatBuffer().put(style)
        execute(
            "tts_encoder",
            arrayOf(ttsIds, ttsStyle),
            arrayOf(ttsD, ttsDurations, ttsTEn),
        )

        val tokenForFrame = IntArray(MAX_TTS_FRAMES)
        var frameCount = 0
        val durationValues = ttsDurations.asFloatBuffer()
        for (token in 0 until validTokens) {
            val count = durationValues.get(token).coerceAtLeast(1f).roundToInt()
                .coerceIn(1, 50)
            repeat(minOf(count, MAX_TTS_FRAMES - frameCount)) {
                tokenForFrame[frameCount++] = token
            }
            if (frameCount == MAX_TTS_FRAMES) break
        }
        if (frameCount == 0) return FloatArray(0)

        val dValues = ttsD.asFloatBuffer()
        val tEnValues = ttsTEn.asFloatBuffer()
        ttsStyleHead.asFloatBuffer().put(style, 0, 128)
        ttsStyleTail.asFloatBuffer().put(style, 128, 128)
        // The decoder is compiled for 25 alignment frames. Adjacent calls use
        // a 20-frame stride, so five input frames really overlap. Cross-fading
        // that corresponding 3000-sample region suppresses decoder edge
        // artifacts without deleting time from the utterance.
        val result = FloatArray(frameCount * TTS_SAMPLES_PER_FRAME)
        var resultSize = 0
        var bucketStart = 0
        var bucketCount = 0

        while (bucketStart < frameCount) {
            val validFrames = minOf(TTS_BUCKET_FRAMES, frameCount - bucketStart)
            zero(ttsEn)
            zero(ttsAsr)
            val enValues = ttsEn.asFloatBuffer()
            val asrValues = ttsAsr.asFloatBuffer()
            for (frame in 0 until validFrames) {
                val token = tokenForFrame[bucketStart + frame]
                for (channel in 0 until 640) {
                    enValues.put(frame * 640 + channel, dValues.get(token * 640 + channel))
                }
                for (channel in 0 until 512) {
                    asrValues.put(channel * 25 + frame, tEnValues.get(channel * 512 + token))
                }
            }

            execute(
                "tts_prosody",
                arrayOf(ttsEn, ttsStyleTail),
                arrayOf(ttsF0, ttsNoise),
            )
            checkNative(
                OneVoiceNative.nativeBuildKokoroHar(
                    ttsF0,
                    ttsHar,
                    resetPhase = bucketStart == 0,
                ),
            )
            execute(
                "tts_decoder",
                arrayOf(ttsF0, ttsNoise, ttsAsr, ttsStyleHead, ttsHar),
                arrayOf(ttsWaveform),
            )
            val samplesToKeep = validFrames * TTS_SAMPLES_PER_FRAME
            val waveform = ttsWaveform.asFloatBuffer()
            val destinationStart = bucketStart * TTS_SAMPLES_PER_FRAME
            val overlap = (resultSize - destinationStart)
                .coerceIn(0, samplesToKeep)
            if (overlap > 0) {
                for (index in 0 until overlap) {
                    val x = (index + 1.0) / (overlap + 1.0)
                    // Raised-cosine complementary weights preserve gain for
                    // the highly correlated renderings of the same frames.
                    val incoming = (0.5 - 0.5 * cos(PI * x)).toFloat()
                    result[destinationStart + index] =
                        result[destinationStart + index] * (1f - incoming) +
                        waveform.get(index) * incoming
                }
            }
            waveform.position(overlap)
            waveform.get(
                result,
                destinationStart + overlap,
                samplesToKeep - overlap,
            )
            resultSize = maxOf(resultSize, destinationStart + samplesToKeep)
            bucketCount++
            if (bucketStart + validFrames >= frameCount) break
            bucketStart += TTS_BUCKET_STRIDE_FRAMES
        }
        Log.i(
            TAG,
            "TTS chunk tokens=$validTokens frames=$frameCount buckets=$bucketCount " +
                "samples=$resultSize",
        )
        return result.copyOf(resultSize)
    }

    private fun tokenizeTts(text: String): List<IntArray> {
        if (text.isBlank()) return emptyList()
        // The 1 KB bridge returns eSpeak/Kokoro token IDs as float "audio".
        // No CPU Kokoro weights are loaded; all learned TTS stages remain QNN.
        val generated = checkNotNull(ttsFrontend) {
            "QNN Kokoro frontend is unavailable in hybrid mode"
        }.generateWithConfig(
            text,
            GenerationConfig(silenceScale = 1.0f, speed = 1.0f, sid = 0),
        )
        require(generated.samples.isNotEmpty()) { "eSpeak produced no Kokoro tokens" }
        val tokenIds = generated.samples.map { value ->
            val id = value.roundToInt()
            require(kotlin.math.abs(value - id) < 1.0e-4f && id in ttsTokenIds) {
                "Kokoro token bridge returned invalid value $value"
            }
            id
        }

        // sherpa processes long/multi-sentence text in independent Kokoro
        // chunks. The bridge concatenates them, with ID 0 delimiting each
        // [BOS ... EOS] sequence; recover those boundaries before QNN TTS.
        val chunks = ArrayList<IntArray>()
        val current = ArrayList<Int>(512)
        tokenIds.forEach { id ->
            if (id == 0) {
                if (current.size > 1) {
                    current += 0
                    chunks += current.toIntArray()
                    current.clear()
                }
                if (current.isEmpty()) current += 0
            } else if (current.size < 511) {
                current += id
            }
        }
        if (current.size > 1) {
            current += 0
            chunks += current.toIntArray()
        }
        return chunks
    }

    private fun readStyle(payloadLength: Int): FloatArray {
        require(payloadLength in 1..510)
        val voiceFile = checkNotNull(voices) {
            "QNN Kokoro voice table is unavailable in hybrid mode"
        }
        val bytes = voiceScratch
        bytes.clear()
        // Speaker 0, row indexed by payload length: [speaker][511][256].
        val offset = payloadLength.toLong() * 256L * Float.SIZE_BYTES
        var read = 0
        while (bytes.hasRemaining()) {
            val count = voiceFile.read(bytes, offset + read)
            check(count > 0) { "voices.bin ended at style row $payloadLength" }
            read += count
        }
        bytes.flip()
        return FloatArray(256).also { bytes.asFloatBuffer().get(it) }
    }

    override fun close() {
        translator.close()
        ttsFrontend?.release()
        cpuTts?.release()
        voices?.close()
    }

    private fun execute(stage: String, inputs: Array<ByteBuffer>, outputs: Array<ByteBuffer>) {
        checkNative(OneVoiceNative.nativeExecuteGraph(stage, inputs, outputs))
    }

    private fun checkNative(error: String?) {
        check(error == null) { error ?: "unknown QNN execution error" }
    }

}

/** Fixed-shape cached Marian decoder driven one token at a time on HTP. */
private class QnnMarianTranslator(
    private val sentencePiece: SentencePieceUnigram,
    private val vocab: Map<String, Int>,
    private val idToToken: Array<String?>,
    private val eosId: Int,
    private val padId: Int,
    private val decoderStartId: Int,
    private val maxLength: Int,
) : Closeable {
    private val ids = direct(256 * Int.SIZE_BYTES)
    private val mask = direct(256 * Int.SIZE_BYTES)
    private val cross = Array(12) { direct(1 * 8 * 256 * 64 * Float.SIZE_BYTES) }
    private val past = Array(12) { direct(1 * 8 * 255 * 64 * Float.SIZE_BYTES) }
    private val present = Array(12) { direct(1 * 8 * 1 * 64 * Float.SIZE_BYTES) }
    private val logits = direct(53_739 * Float.SIZE_BYTES)
    private val decoderId = direct(Int.SIZE_BYTES)
    private val position = direct(Int.SIZE_BYTES)

    companion object {
        fun load(root: File): QnnMarianTranslator {
            val vocabObject = JSONObject(File(root, "mt_vocab.json").readText())
            val vocab = HashMap<String, Int>(vocabObject.length() * 2)
            val idToToken = arrayOfNulls<String>(vocabObject.length())
            vocabObject.keys().forEach { token ->
                val id = vocabObject.getInt(token)
                vocab[token] = id
                if (id in idToToken.indices) idToToken[id] = token
            }
            val config = JSONObject(File(root, "mt_config.json").readText())
            return QnnMarianTranslator(
                sentencePiece = SentencePieceUnigram.load(File(root, "mt_source.spm")),
                vocab = vocab,
                idToToken = idToToken,
                eosId = config.getInt("eos_token_id"),
                padId = config.getInt("pad_token_id"),
                decoderStartId = config.getInt("decoder_start_token_id"),
                maxLength = config.optInt("max_length", 128).coerceIn(8, 128),
            )
        }
    }

    fun translate(text: String): String {
        val pieces = sentencePiece.encodeToPieces(text)
        if (pieces.isEmpty()) return ""
        val idsView = ids.asIntBuffer()
        val maskView = mask.asIntBuffer()
        repeat(256) { idsView.put(it, padId) }
        zero(mask)
        val sourceCount = minOf(255, pieces.size)
        repeat(sourceCount) { index ->
            idsView.put(index, vocab[pieces[index]] ?: 1)
            maskView.put(index, 1)
        }
        idsView.put(sourceCount, eosId)
        maskView.put(sourceCount, 1)

        execute("mt_encoder", arrayOf(ids, mask), cross)
        past.forEach(::zero)
        val generated = IntArray(maxLength)
        var generatedSize = 0
        var current = decoderStartId

        for (step in 0 until minOf(maxLength, 255)) {
            decoderId.putInt(0, current)
            position.putInt(0, step)
            val decoderInputs = ArrayList<ByteBuffer>(27)
            decoderInputs += decoderId
            decoderInputs += position
            decoderInputs += mask
            repeat(6) { layer ->
                decoderInputs += past[layer * 2]
                decoderInputs += past[layer * 2 + 1]
                decoderInputs += cross[layer * 2]
                decoderInputs += cross[layer * 2 + 1]
            }
            execute("mt_decoder", decoderInputs.toTypedArray(), arrayOf(logits, *present))
            val values = logits.asFloatBuffer()
            var next = -1
            var score = Float.NEGATIVE_INFINITY
            repeat(53_739) { id ->
                val value = values.get(id)
                if (id != padId && !value.isNaN() && value > score) {
                    next = id
                    score = value
                }
            }
            check(next >= 0) { "Marian decoder returned no finite token" }
            if (next == eosId) break
            generated[generatedSize++] = next

            repeat(12) { tensor ->
                val source = present[tensor].asFloatBuffer()
                val destination = past[tensor].asFloatBuffer()
                repeat(8) { head ->
                    repeat(64) { channel ->
                        destination.put((head * 255 + step) * 64 + channel,
                            source.get(head * 64 + channel))
                    }
                }
            }
            current = next
        }
        return buildString {
            repeat(generatedSize) { index ->
                val token = idToToken.getOrNull(generated[index]) ?: return@repeat
                if (token != "<unk>" && token != "<pad>" && token != "</s>") append(token)
            }
        }.replace('▁', ' ').trim()
    }

    private fun execute(stage: String, inputs: Array<ByteBuffer>, outputs: Array<ByteBuffer>) {
        val error = OneVoiceNative.nativeExecuteGraph(stage, inputs, outputs)
        check(error == null) { error ?: "unknown QNN execution error" }
    }

    override fun close() = Unit
}

private fun direct(bytes: Int): ByteBuffer =
    ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())

private fun zero(buffer: ByteBuffer) {
    val longs = buffer.asLongBuffer()
    for (index in 0 until longs.capacity()) longs.put(index, 0L)
    val covered = longs.capacity() * Long.SIZE_BYTES
    for (index in covered until buffer.capacity()) buffer.put(index, 0)
}
