package com.onevoice.link.core

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.Random
import kotlin.math.exp
import kotlin.math.roundToInt

private const val TAG = "OneVoiceVieNeu"

/** VieNeu-TTS v3 Nano files under en-vi/, as prepare-vieneu-nano.py stages them. */
internal object VieNeuFiles {
    const val DIR = "tts_vieneu"
    const val TEXT_ENCODER = "text_encoder.onnx"
    const val DURATION_PREDICTOR = "duration_predictor.onnx"
    const val VECTOR_ESTIMATOR = "vector_estimator.onnx"
    const val CODEC_DECODER = "codec_decoder.onnx"
    const val CONFIG = "nano.json"
    const val NULL_SPEAKER = "null_spk.bin"
    const val NULL_STYLE = "null_style.bin"
    const val VOICES = "voices.bin"
    const val DICTIONARY = "sea_g2p.bin"

    val ALL = listOf(
        TEXT_ENCODER, DURATION_PREDICTOR, VECTOR_ESTIMATOR, CODEC_DECODER,
        CONFIG, NULL_SPEAKER, NULL_STYLE, VOICES, DICTIONARY,
    )
}

/**
 * VieNeu-TTS v3 Nano (pnnbao-ump/VieNeu-TTS-v3-Nano, Apache-2.0): a 48M-parameter
 * conditional flow-matching Vietnamese TTS, ported from the reference SDK's
 * `OnnxV3NanoEngine.infer` (vieneu 3.8.3).
 *
 * Per chunk: sea-g2p phonemes -> text encoder (conditioned on the voice's 50
 * style tokens) -> duration predictor gives the length in latent frames ->
 * Gaussian noise is integrated over [steps] Euler steps of the vector
 * estimator with classifier-free guidance -> codec decoder -> 24 kHz.
 *
 * Unlike the SDK, which draws fresh noise per call, the noise is seeded per
 * chunk so the same text always renders the same waveform and a benchmark is
 * reproducible. The SDK's optional audio watermark is not applied.
 */
internal class VieNeuNanoTts private constructor(
    private val environment: OrtEnvironment,
    private val textEncoder: OrtSession,
    private val durationPredictor: OrtSession,
    private val vectorEstimator: OrtSession,
    private val codecDecoder: OrtSession,
    private val g2p: SeaG2p,
    private val vocab: Map<Int, Long>,
    private val bosId: Long,
    private val eosId: Long,
    private val flowFps: Double,
    private val latentChannels: Int,
    private val speaker: FloatArray,
    private val style: FloatArray,
    private val nullSpeaker: FloatArray,
    private val nullStyle: FloatArray,
    private val steps: Int,
    private val cfg: Float,
    private val speed: Float,
    private val seed: Long,
) : Closeable {
    val sampleRate = SAMPLE_RATE

    /** The unconditional text context is voice- and text-independent: built once. */
    private val nullContext: FloatArray
    private val nullContextLength: Int

    init {
        val (context, length) = encodeText(longArrayOf(bosId, eosId), nullStyle)
        nullContext = context
        nullContextLength = length
    }

    /** Whole text -> one mono waveform at [SAMPLE_RATE]; chunks joined with a pause. */
    @Synchronized
    fun synthesize(text: String): FloatArray {
        val chunks = chunkText(text.trim())
        val waves = chunks.mapIndexed { index, chunk -> synthesizeChunk(chunk, seed + index) }
            .filter { it.isNotEmpty() }
        if (waves.size <= 1) return waves.firstOrNull() ?: FloatArray(0)
        val pause = (SAMPLE_RATE * CHUNK_PAUSE_SECONDS).toInt()
        val out = FloatArray(waves.sumOf { it.size } + pause * (waves.size - 1))
        var offset = 0
        waves.forEachIndexed { index, wave ->
            wave.copyInto(out, offset)
            offset += wave.size + if (index < waves.lastIndex) pause else 0
        }
        return out
    }

    private fun synthesizeChunk(chunk: String, chunkSeed: Long): FloatArray {
        val phones = g2p.phonemize(chunk, puncNorm = true)
        val ids = encodePhones(phones)
        val (context, length) = encodeText(ids, style)
        val frames = predictFrames(context, length)
        val x = FloatArray(latentChannels * frames)
        val random = Random(chunkSeed)
        for (i in x.indices) x[i] = random.nextGaussian().toFloat()

        // Uniform Euler schedule t_i = i / steps (the SDK's sway = 0 default).
        val schedule = DoubleArray(steps + 1) { it.toDouble() / steps }
        // The conditioning tensors are reused by every Euler step.
        val conditioning = mutableListOf<OnnxTensor>()
        fun keep(tensor: OnnxTensor) = tensor.also(conditioning::add)
        try {
            val ctx = keep(tensor(context, longArrayOf(1, length.toLong(), STYLE_DIM.toLong())))
            val ctxMask = keep(mask(length))
            val spk = keep(tensor(speaker, longArrayOf(1, SPEAKER_DIM.toLong())))
            val sty = keep(tensor(style, styleShape()))
            val nullCtx = keep(
                tensor(nullContext, longArrayOf(1, nullContextLength.toLong(), STYLE_DIM.toLong())),
            )
            val nullMask = keep(mask(nullContextLength))
            val nullSpk = keep(tensor(nullSpeaker, longArrayOf(1, SPEAKER_DIM.toLong())))
            val nullSty = keep(tensor(nullStyle, styleShape()))
            for (step in 0 until steps) {
                val dt = (schedule[step + 1] - schedule[step]).toFloat()
                val t = floatArrayOf(schedule[step].toFloat())
                val v = estimate(x, frames, t, ctx, ctxMask, spk, sty)
                if (cfg > 0f) {
                    // Classifier-free guidance: v = v_uncond + cfg * (v_cond - v_uncond).
                    val vu = estimate(x, frames, t, nullCtx, nullMask, nullSpk, nullSty)
                    for (i in x.indices) x[i] += dt * (vu[i] + cfg * (v[i] - vu[i]))
                } else {
                    for (i in x.indices) x[i] += dt * v[i]
                }
            }
        } finally {
            conditioning.forEach { it.close() }
        }
        val wave = tensor(x, longArrayOf(1, latentChannels.toLong(), frames.toLong())).use { latent ->
            codecDecoder.run(mapOf("x" to latent)).use { result ->
                floats(result[0] as OnnxTensor)
            }
        }
        for (i in wave.indices) wave[i] = wave[i].coerceIn(-1f, 1f)
        Log.i(
            TAG,
            "chunk chars=${chunk.length} phones=${phones.length} ids=${ids.size} " +
                "frames=$frames audioMs=${wave.size * 1_000L / SAMPLE_RATE}",
        )
        return wave
    }

    /** Phone string -> [bos, ids..., eos]; characters outside the vocab are dropped. */
    private fun encodePhones(phones: String): LongArray {
        val ids = ArrayList<Long>(phones.length + 2)
        ids += bosId
        var dropped = 0
        phones.codePoints().forEach { cp ->
            val id = vocab[cp]
            if (id == null) dropped++ else ids += id
        }
        ids += eosId
        if (dropped > 0) Log.w(TAG, "dropped $dropped phone characters outside the Nano vocab")
        return ids.toLongArray()
    }

    private fun encodeText(ids: LongArray, styleTokens: FloatArray): Pair<FloatArray, Int> {
        val idTensor = OnnxTensor.createTensor(
            environment, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong()),
        )
        return idTensor.use { idsIn ->
            tensor(styleTokens, styleShape()).use { sty ->
                textEncoder.run(mapOf("ids" to idsIn, "style" to sty)).use { result ->
                    floats(result[0] as OnnxTensor) to ids.size
                }
            }
        }
    }

    /** Duration predictor -> latent frame count, capped like the SDK at 15 s. */
    private fun predictFrames(context: FloatArray, length: Int): Int {
        val logSeconds = tensor(context, longArrayOf(1, length.toLong(), STYLE_DIM.toLong())).use { ctx ->
            mask(length).use { ctxMask ->
                tensor(speaker, longArrayOf(1, SPEAKER_DIM.toLong())).use { spk ->
                    durationPredictor.run(
                        mapOf("ctx" to ctx, "ctx_mask" to ctxMask, "spk" to spk),
                    ).use { result -> floats(result[0] as OnnxTensor)[0] }
                }
            }
        }
        val seconds = minOf(exp(logSeconds.toDouble()) / speed.coerceAtLeast(1e-3f), MAX_CHUNK_SECONDS)
        return maxOf(MIN_FRAMES, (seconds * flowFps).roundToInt())
    }

    private fun estimate(
        x: FloatArray,
        frames: Int,
        t: FloatArray,
        ctx: OnnxTensor,
        ctxMask: OnnxTensor,
        spk: OnnxTensor,
        sty: OnnxTensor,
    ): FloatArray =
        tensor(x, longArrayOf(1, latentChannels.toLong(), frames.toLong())).use { xIn ->
            tensor(t, longArrayOf(1)).use { tIn ->
                vectorEstimator.run(
                    mapOf(
                        "x" to xIn, "t" to tIn, "ctx" to ctx, "ctx_mask" to ctxMask,
                        "spk" to spk, "style" to sty,
                    ),
                ).use { result -> floats(result[0] as OnnxTensor) }
            }
        }

    private fun tensor(values: FloatArray, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(values), shape)

    /** Every phone is real (no padding inside a single unbatched sequence). */
    private fun mask(length: Int): OnnxTensor =
        OnnxTensor.createTensor(environment, arrayOf(BooleanArray(length) { true }))

    private fun styleShape() = longArrayOf(1, N_STYLE.toLong(), STYLE_DIM.toLong())

    private fun floats(tensor: OnnxTensor): FloatArray {
        val buffer = tensor.floatBuffer
        return FloatArray(buffer.remaining()).also(buffer::get)
    }

    override fun close() {
        runCatching { textEncoder.close() }
        runCatching { durationPredictor.close() }
        runCatching { vectorEstimator.close() }
        runCatching { codecDecoder.close() }
        runCatching { g2p.close() }
    }

    companion object {
        const val SAMPLE_RATE = 24_000
        private const val N_STYLE = 50
        private const val STYLE_DIM = 256
        private const val SPEAKER_DIM = 192
        private const val MIN_FRAMES = 2
        /** Nano was trained on clips of at most 15 s; longer targets drift. */
        private const val MAX_CHUNK_SECONDS = 15.0
        /** The SDK keeps chunks to 140 characters for the same reason. */
        private const val MAX_CHUNK_CHARS = 140
        private const val CHUNK_PAUSE_SECONDS = 0.25

        fun load(
            directionRoot: File,
            voice: String,
            steps: Int,
            cfg: Float,
            speed: Float,
            threads: Int,
            seed: Long,
        ): VieNeuNanoTts {
            val dir = File(directionRoot, VieNeuFiles.DIR)
            val config = JSONObject(File(dir, VieNeuFiles.CONFIG).readText())
            val vocabJson = config.getJSONObject("vocab")
            val vocab = HashMap<Int, Long>()
            vocabJson.keys().forEach { symbol ->
                // Multi-character entries (<pad>, <bos>, <eos>) never occur in phones.
                if (symbol.codePointCount(0, symbol.length) == 1) {
                    vocab[symbol.codePointAt(0)] = vocabJson.getLong(symbol)
                }
            }
            val voices = config.getJSONArray("voices")
            val voiceIndex = (0 until voices.length())
                .firstOrNull { voices.getJSONObject(it).getString("id") == voice }
            requireNotNull(voiceIndex) {
                "VieNeu voice '$voice' not in ${VieNeuFiles.CONFIG}; available: " +
                    (0 until voices.length()).joinToString { voices.getJSONObject(it).getString("id") }
            }
            val row = SPEAKER_DIM + N_STYLE * STYLE_DIM
            val allVoices = readFloats(File(dir, VieNeuFiles.VOICES))
            require(allVoices.size == row * voices.length()) {
                "voices.bin holds ${allVoices.size} floats, expected ${row * voices.length()}"
            }
            val base = voiceIndex * row
            val speaker = allVoices.copyOfRange(base, base + SPEAKER_DIM)
            val style = allVoices.copyOfRange(base + SPEAKER_DIM, base + row)
            val nullSpeaker = readFloats(File(dir, VieNeuFiles.NULL_SPEAKER))
            val nullStyle = readFloats(File(dir, VieNeuFiles.NULL_STYLE))
            require(nullSpeaker.size == SPEAKER_DIM && nullStyle.size == N_STYLE * STYLE_DIM) {
                "VieNeu null conditioning has unexpected size"
            }

            val environment = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setIntraOpNumThreads(threads)
                setInterOpNumThreads(1)
                // The SDK's setting: no busy-wait between the many short calls.
                addConfigEntry("session.intra_op.allow_spinning", "0")
            }
            val opened = mutableListOf<AutoCloseable>()
            try {
                fun session(name: String) =
                    environment.createSession(File(dir, name).absolutePath, options)
                        .also(opened::add)
                val textEncoder = session(VieNeuFiles.TEXT_ENCODER)
                val durationPredictor = session(VieNeuFiles.DURATION_PREDICTOR)
                val vectorEstimator = session(VieNeuFiles.VECTOR_ESTIMATOR)
                val codecDecoder = session(VieNeuFiles.CODEC_DECODER)
                val g2p = SeaG2p.open(File(dir, VieNeuFiles.DICTIONARY)).also(opened::add)
                return VieNeuNanoTts(
                    environment = environment,
                    textEncoder = textEncoder,
                    durationPredictor = durationPredictor,
                    vectorEstimator = vectorEstimator,
                    codecDecoder = codecDecoder,
                    g2p = g2p,
                    vocab = vocab,
                    bosId = config.getLong("bos_id"),
                    eosId = config.getLong("eos_id"),
                    flowFps = config.getDouble("flow_fps"),
                    latentChannels = config.getInt("latent_channels"),
                    speaker = speaker,
                    style = style,
                    nullSpeaker = nullSpeaker,
                    nullStyle = nullStyle,
                    steps = steps.coerceAtLeast(1),
                    cfg = cfg,
                    speed = speed,
                    seed = seed,
                )
            } catch (failure: Throwable) {
                opened.forEach { runCatching { it.close() } }
                throw failure
            }
        }

        private fun readFloats(file: File): FloatArray {
            val bytes = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(bytes.remaining() / Float.SIZE_BYTES).also { bytes.asFloatBuffer().get(it) }
        }

        /**
         * Split at sentence ends, then commas, then spaces, so no chunk exceeds
         * [MAX_CHUNK_CHARS]. A single MT turn is normally one chunk.
         */
        internal fun chunkText(text: String): List<String> {
            if (text.length <= MAX_CHUNK_CHARS) return listOf(text).filter { it.isNotBlank() }
            val pieces = mutableListOf<String>()
            for (separator in listOf(Regex("(?<=[.!?;])\\s+"), Regex("(?<=,)\\s+"), Regex("\\s+"))) {
                val source = if (pieces.isEmpty()) listOf(text) else pieces.toList()
                pieces.clear()
                for (piece in source) {
                    if (piece.length <= MAX_CHUNK_CHARS) {
                        pieces += piece
                        continue
                    }
                    val merged = StringBuilder()
                    for (part in piece.split(separator)) {
                        if (merged.isNotEmpty() && merged.length + 1 + part.length > MAX_CHUNK_CHARS) {
                            pieces += merged.toString()
                            merged.clear()
                        }
                        if (merged.isNotEmpty()) merged.append(' ')
                        merged.append(part)
                    }
                    if (merged.isNotEmpty()) pieces += merged.toString()
                }
                if (pieces.all { it.length <= MAX_CHUNK_CHARS }) break
            }
            return pieces.map(String::trim).filter(String::isNotEmpty)
        }
    }
}
