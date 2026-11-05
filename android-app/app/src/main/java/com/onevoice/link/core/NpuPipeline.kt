package com.onevoice.link.core

import android.util.Log
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.onevoice.link.BuildConfig
import com.onevoice.link.hf.SentencePieceUnigram
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt

/** Host glue for either direction's separately compiled QNN ASR and MT contexts. */
internal class NpuPipeline private constructor(
    private val stagePrefix: String,
    private val asrArtifactPrefix: String,
    private val asrMode: NpuAsrMode,
    private val asrTokens: List<String>,
    /** UFIXED_16 encodings of the Parakeet CTC graph; null for float graphs. */
    private val asrInputQuant: Q16Params?,
    private val asrOutputQuant: Q16Params?,
    private val rnntDecoder: ZipformerFamilyRnntDecoder?,
    private val translator: QnnMarianTranslator,
    private val ttsTokenIds: Set<Int>,
    private val voices: FileChannel?,
    private val ttsFrontend: OfflineTts?,
    private val cpuTts: OfflineTts?,
    /** Which model [cpuTts] holds; Supertonic also needs [cpuTtsGeneration]. */
    private val cpuTtsEngine: CpuTtsEngine,
    private val cpuTtsGeneration: GenerationConfig?,
    /** Set instead of [cpuTts] when [cpuTtsEngine] is VIENEU (not a sherpa model). */
    private val vieneuTts: VieNeuNanoTts? = null,
) : Closeable {
    companion object {
        private const val PARAKEET_FEATURE_BYTES = 1 * 1001 * 80 * Float.SIZE_BYTES
        private const val PARAKEET_LOGIT_BYTES = 1 * 126 * 1025 * Float.SIZE_BYTES
        private const val PARAKEET_Q16_INPUT_BYTES =
            ParakeetCtcContract.FEATURE_FRAMES * ParakeetCtcContract.FEATURE_DIM * Short.SIZE_BYTES
        private const val PARAKEET_Q16_OUTPUT_BYTES =
            ParakeetCtcContract.OUTPUT_FRAMES * ParakeetCtcContract.VOCAB_SIZE * Short.SIZE_BYTES
        private const val PARAKEET_UNK_ID = 0
        private const val RNNT_FEATURE_BYTES =
            1 * ZipformerFamilyRnntDecoder.FEATURE_FRAMES *
                ZipformerFamilyRnntDecoder.FEATURE_DIM *
                Float.SIZE_BYTES
        private const val RNNT_OUTPUT_BYTES =
            1 * ZipformerFamilyRnntDecoder.ENCODER_FRAMES *
                ZipformerFamilyRnntDecoder.ENCODER_DIM *
                Float.SIZE_BYTES
        // Leaves ~0.9 dB of headroom below full scale, so a hot voice is
        // attenuated rather than clamped sample-by-sample by AudioTrack.
        private const val PEAK_CEILING = 0.90f
        private const val ASR_HOP_SAMPLES = 160
        private const val ASR_SUBSAMPLING = 8
        private const val ASR_OUTPUT_FRAMES = 126
        private const val MAX_TTS_FRAMES = 750 // 18.75 seconds @ 24 kHz
        private const val TTS_BUCKET_FRAMES = 25
        private const val TTS_BUCKET_STRIDE_FRAMES = 20
        private const val TTS_SAMPLES_PER_FRAME = 600
        private const val BETWEEN_CHUNK_SILENCE_SAMPLES = 2_880 // 120 ms at 24 kHz
        private const val TAG = "OneVoiceNpuPipeline"
        private const val PLAYBACK_SAMPLE_RATE = NpuEngine.TTS_SAMPLE_RATE

        /** Allocate the one [1,1000,80] FP32 frontend tensor shared by both RNN-Ts. */
        fun allocateSharedRnntFeatures(): ByteBuffer = direct(RNNT_FEATURE_BYTES)

        /**
         * Run the common Kaldi/icefall frontend exactly once for an utterance.
         * The returned frame count must travel with [destination] to both
         * [recognizeSharedRnntFeatures] calls.
         */
        fun prepareSharedRnntFeatures(
            samples: FloatArray,
            destination: ByteBuffer,
        ): Int {
            require(samples.isNotEmpty() && samples.size <= 160_000) {
                "NPU ASR accepts 1..160000 samples"
            }
            require(destination.isDirect && destination.capacity() >= RNNT_FEATURE_BYTES) {
                "Shared RNN-T features must hold [1,1000,80] FLOAT32"
            }
            val writableView = destination.duplicate().order(ByteOrder.nativeOrder()).apply {
                clear()
            }
            val error = OneVoiceNative.nativeExtractGipformerFeatures(samples, writableView)
            check(error == null) { error ?: "unknown RNN-T frontend error" }
            // Kaldi snip_edges=false creates round(sampleCount / 160) frames.
            return ((samples.size + ASR_HOP_SAMPLES / 2) / ASR_HOP_SAMPLES)
                .coerceIn(1, ZipformerFamilyRnntDecoder.FEATURE_FRAMES)
        }

        fun load(
            directionRoot: File,
            asrStage: StageSpec,
            stagePrefix: String,
            asrArtifactPrefix: String,
            useCpuTts: Boolean = false,
            cpuTtsModel: File? = null,
            cpuTtsEngine: CpuTtsEngine = CpuTtsEngine.KOKORO,
        ): NpuPipeline {
            require(stagePrefix in setOf("vi-en", "en-vi")) {
                "Unsupported Hybrid direction key: $stagePrefix"
            }
            val asrMode = NpuAsrMode.from(asrStage)
            require(useCpuTts || cpuTtsEngine == CpuTtsEngine.KOKORO) {
                "Only CPU TTS can use ${cpuTtsEngine.name}"
            }
            val supertonic = cpuTtsEngine == CpuTtsEngine.SUPERTONIC
            val vieneu = cpuTtsEngine == CpuTtsEngine.VIENEU
            require(!vieneu || stagePrefix == "en-vi") { "VieNeu speaks Vietnamese only" }
            val kokoro = cpuTtsEngine.usesKokoroAssets
            val requiredNames = buildList {
                addAll(listOf("mt_source.spm", "mt_vocab.json", "mt_config.json"))
                when {
                    supertonic -> SupertonicFiles.ALL.forEach { add("${SupertonicFiles.DIR}/$it") }
                    vieneu -> VieNeuFiles.ALL.forEach { add("${VieNeuFiles.DIR}/$it") }
                    else -> addAll(listOf("tts_tokens.txt", "tts_voices.bin"))
                }
                when (asrMode) {
                    NpuAsrMode.PARAKEET_CTC -> add("asr_tokens.txt")
                    NpuAsrMode.PARAKEET_CTC_Q16 -> add("${asrArtifactPrefix}_tokens.txt")
                    NpuAsrMode.ZIPFORMER_FAMILY_RNNT -> addAll(
                        listOf(
                            "${asrArtifactPrefix}_decoder.onnx",
                            "${asrArtifactPrefix}_joiner.onnx",
                            "${asrArtifactPrefix}_tokens.txt",
                        ),
                    )
                }
                if (!useCpuTts) add("tts_frontend.onnx")
            }
            val required = requiredNames.map { File(directionRoot, it) }.toMutableList()
            if (useCpuTts && kokoro) {
                required += cpuTtsModel ?: File(directionRoot, "tts_cpu.onnx")
            }
            val missing = required.filterNot(File::isFile)
            val espeakData = File(directionRoot, "espeak-ng-data")
            val targetDictionary = if (stagePrefix == "vi-en") "en_dict" else "vi_dict"
            // Supertonic reads graphemes and VieNeu has sea-g2p; only Kokoro
            // needs the eSpeak frontend.
            val espeakMissing = if (!kokoro) {
                emptyList()
            } else {
                listOf("phontab", "phondata", "phonindex", targetDictionary)
                    .map { File(espeakData, it) }
                    .filterNot(File::isFile)
            }
            require(missing.isEmpty()) {
                "Missing NPU frontend assets: ${missing.joinToString { it.name }}. " +
                    "Run tools/models/push-npu-models.sh auto <adb-serial> " +
                    "(missing direction: $stagePrefix)."
            }
            require(espeakMissing.isEmpty()) {
                "Missing eSpeak frontend data: ${espeakMissing.joinToString { it.name }}. " +
                    "Run tools/models/push-npu-models.sh auto <adb-serial> " +
                    "(missing direction: $stagePrefix)."
            }
            val asrTokens = if (asrMode == NpuAsrMode.PARAKEET_CTC) {
                val asrIndex = parseIndexedTokens(File(directionRoot, "asr_tokens.txt"))
                val indexed = arrayOfNulls<String>(asrIndex.size)
                asrIndex.forEach { (token, id) ->
                    if (id in indexed.indices) indexed[id] = token
                }
                require(indexed.size == 1025 && indexed.last() == "<blk>") {
                    "Unexpected ASR vocabulary (${indexed.size} tokens)"
                }
                indexed.map { it ?: "<unk>" }
            } else if (asrMode == NpuAsrMode.PARAKEET_CTC_Q16) {
                val index = parseIndexedTokens(File(directionRoot, "${asrArtifactPrefix}_tokens.txt"))
                val indexed = arrayOfNulls<String>(index.size)
                index.forEach { (token, id) -> if (id in indexed.indices) indexed[id] = token }
                require(
                    indexed.size == ParakeetCtcContract.VOCAB_SIZE &&
                        indexed[ParakeetCtcContract.BLANK_ID] == "<blk>",
                ) { "Unexpected Parakeet CTC vocabulary (${indexed.size} tokens)" }
                indexed.map { it ?: "<unk>" }
            } else {
                emptyList()
            }
            val (asrInputQuant, asrOutputQuant) = if (asrMode == NpuAsrMode.PARAKEET_CTC_Q16) {
                // From the manifest, which records them from the binary itself.
                requireNotNull(asrStage.quantization["x"]) {
                    "Manifest lacks the Parakeet input encoding; regenerate npu_manifest.json"
                } to requireNotNull(asrStage.quantization["log_probs"]) {
                    "Manifest lacks the Parakeet output encoding; regenerate npu_manifest.json"
                }
            } else {
                null to null
            }
            val ttsTokens = if (!kokoro) {
                emptyMap()
            } else {
                parseIndexedTokens(File(directionRoot, "tts_tokens.txt"))
            }
            val kokoroConfig = { modelPath: String ->
                OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        kokoro = OfflineTtsKokoroModelConfig(
                            model = modelPath,
                            voices = File(directionRoot, "tts_voices.bin").absolutePath,
                            tokens = File(directionRoot, "tts_tokens.txt").absolutePath,
                            dataDir = espeakData.absolutePath,
                        ),
                        numThreads = if (useCpuTts) BuildConfig.KOKORO_THREADS else 1,
                    ),
                    maxNumSentences = 1,
                    silenceScale = 1.0f,
                )
            }
            var rnntDecoder: ZipformerFamilyRnntDecoder? = null
            var translator: QnnMarianTranslator? = null
            var voices: FileChannel? = null
            var ttsFrontend: OfflineTts? = null
            var cpuTts: OfflineTts? = null
            var cpuTtsGeneration: GenerationConfig? = null
            var vieneuTts: VieNeuNanoTts? = null
            try {
                if (asrMode == NpuAsrMode.ZIPFORMER_FAMILY_RNNT) {
                    rnntDecoder = ZipformerFamilyRnntDecoder.load(
                        directionRoot,
                        asrArtifactPrefix,
                    )
                }
                translator = QnnMarianTranslator.load(directionRoot, stagePrefix)
                if (supertonic) {
                    val synthesizer = OfflineTts(
                        config = SupertonicFiles.config(directionRoot, BuildConfig.SUPERTONIC_THREADS),
                    )
                    cpuTts = synthesizer
                    val generation = SupertonicFiles.generation(
                        stagePrefix,
                        BuildConfig.SUPERTONIC_VOICE,
                        BuildConfig.SUPERTONIC_STEPS,
                        BuildConfig.SUPERTONIC_SPEED,
                    )
                    // A voice.bin with fewer styles would silently remap sids.
                    check(generation.sid < synthesizer.numSpeakers()) {
                        "Supertonic voice.bin holds ${synthesizer.numSpeakers()} styles; " +
                            "${BuildConfig.SUPERTONIC_VOICE} needs sid ${generation.sid}"
                    }
                    cpuTtsGeneration = generation
                    Log.i(
                        TAG,
                        "Supertonic TTS loaded direction=$stagePrefix " +
                            "voice=${BuildConfig.SUPERTONIC_VOICE} sid=${generation.sid} " +
                            "steps=${generation.numSteps} speed=${generation.speed} " +
                            "threads=${BuildConfig.SUPERTONIC_THREADS} " +
                            "sampleRate=${synthesizer.sampleRate()} styles=${synthesizer.numSpeakers()}",
                    )
                } else if (vieneu) {
                    vieneuTts = VieNeuNanoTts.load(
                        directionRoot = directionRoot,
                        voice = BuildConfig.VIENEU_VOICE,
                        steps = BuildConfig.VIENEU_STEPS,
                        cfg = BuildConfig.VIENEU_CFG,
                        speed = BuildConfig.VIENEU_SPEED,
                        threads = BuildConfig.VIENEU_THREADS,
                        seed = BuildConfig.VIENEU_SEED,
                    )
                    Log.i(
                        TAG,
                        "VieNeu v3 Nano TTS loaded direction=$stagePrefix " +
                            "voice=${BuildConfig.VIENEU_VOICE} steps=${BuildConfig.VIENEU_STEPS} " +
                            "cfg=${BuildConfig.VIENEU_CFG} speed=${BuildConfig.VIENEU_SPEED} " +
                            "threads=${BuildConfig.VIENEU_THREADS} seed=${BuildConfig.VIENEU_SEED}",
                    )
                } else if (useCpuTts) {
                    cpuTts = OfflineTts(
                        config = kokoroConfig(requireNotNull(cpuTtsModel).absolutePath),
                    )
                } else {
                    voices = FileInputStream(File(directionRoot, "tts_voices.bin")).channel
                    ttsFrontend = OfflineTts(
                        config = kokoroConfig(
                            File(directionRoot, "tts_frontend.onnx").absolutePath,
                        ),
                    )
                }
                return NpuPipeline(
                    stagePrefix = stagePrefix,
                    asrArtifactPrefix = asrArtifactPrefix,
                    asrMode = asrMode,
                    asrTokens = asrTokens,
                    asrInputQuant = asrInputQuant,
                    asrOutputQuant = asrOutputQuant,
                    rnntDecoder = rnntDecoder,
                    translator = checkNotNull(translator),
                    ttsTokenIds = ttsTokens.values.toSet(),
                    voices = voices,
                    ttsFrontend = ttsFrontend,
                    cpuTts = cpuTts,
                    cpuTtsEngine = cpuTtsEngine,
                    cpuTtsGeneration = cpuTtsGeneration,
                    vieneuTts = vieneuTts,
                )
            } catch (failure: Throwable) {
                runCatching { vieneuTts?.close() }
                runCatching { cpuTts?.release() }
                runCatching { ttsFrontend?.release() }
                runCatching { voices?.close() }
                runCatching { translator?.close() }
                runCatching { rnntDecoder?.close() }
                throw failure
            }
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

    private val asrFeatures = direct(
        if (asrMode == NpuAsrMode.ZIPFORMER_FAMILY_RNNT) {
            RNNT_FEATURE_BYTES
        } else {
            // Both Parakeet paths take the NeMo [1001,80] float frontend.
            PARAKEET_FEATURE_BYTES
        },
    )
    private val asrOutput = direct(
        when (asrMode) {
            NpuAsrMode.ZIPFORMER_FAMILY_RNNT -> RNNT_OUTPUT_BYTES
            NpuAsrMode.PARAKEET_CTC -> PARAKEET_LOGIT_BYTES
            NpuAsrMode.PARAKEET_CTC_Q16 -> PARAKEET_Q16_OUTPUT_BYTES
        },
    )
    /** Quantized graph input; only the 16-bit Parakeet CTC graph needs it. */
    private val asrQuantizedInput: ByteBuffer? =
        if (asrMode == NpuAsrMode.PARAKEET_CTC_Q16) direct(PARAKEET_Q16_INPUT_BYTES) else null

    /** True when this branch consumes the shared Kaldi fbank tensor. */
    val usesSharedRnntFeatures: Boolean get() = asrMode == NpuAsrMode.ZIPFORMER_FAMILY_RNNT

    /**
     * Parakeet-TDT-CTC emits punctuation and casing; the GIPFormer/Zipformer
     * RNN-T transcripts are lowercase without punctuation. Long turns use
     * sentence ends as translation-unit boundaries only where they exist.
     */
    val asrEmitsPunctuation: Boolean get() = asrMode != NpuAsrMode.ZIPFORMER_FAMILY_RNNT
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
        return when (asrMode) {
            NpuAsrMode.PARAKEET_CTC -> recognizeParakeet(samples)
            NpuAsrMode.ZIPFORMER_FAMILY_RNNT,
            NpuAsrMode.PARAKEET_CTC_Q16,
            -> recognizeScored(samples).transcript
        }
    }

    /**
     * Scored recognition from PCM. RNN-T callers in the dual router should
     * prepare the shared frontend once instead; Parakeet always needs its own
     * NeMo frontend, so this is also its dual-router entry point.
     */
    fun recognizeScored(samples: FloatArray): AsrHypothesis {
        require(samples.isNotEmpty() && samples.size <= 160_000) {
            "NPU ASR accepts 1..160000 samples"
        }
        return when (asrMode) {
            NpuAsrMode.ZIPFORMER_FAMILY_RNNT -> {
                val validFeatureFrames = prepareSharedRnntFeatures(samples, asrFeatures)
                recognizeSharedRnntFeatures(asrFeatures, validFeatureFrames)
            }
            NpuAsrMode.PARAKEET_CTC_Q16 -> recognizeParakeetQ16(samples)
            NpuAsrMode.PARAKEET_CTC -> error("The float Parakeet CTC graph is not scored")
        }
    }

    /**
     * sherpa-onnx's SM8550 Parakeet-TDT-CTC-110M (CTC head): NeMo log-mel,
     * per-feature normalized over the real frames and zero padded, quantized
     * to UFIXED_16; the graph emits quantized log-softmax per 80 ms step.
     */
    private fun recognizeParakeetQ16(samples: FloatArray): AsrHypothesis {
        val inputQuant = checkNotNull(asrInputQuant)
        val outputQuant = checkNotNull(asrOutputQuant)
        val quantized = checkNotNull(asrQuantizedInput)
        // [1001,80] NeMo center=true frames; for <=10 s the 1001st frame is
        // always zero padding, so the graph's first 1000 rows are exact.
        checkNative(OneVoiceNative.nativeExtractAsrFeatures(samples, asrFeatures))
        val features = asrFeatures.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
        val codes = quantized.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer()
        val values = ParakeetCtcContract.FEATURE_FRAMES * ParakeetCtcContract.FEATURE_DIM
        for (index in 0 until values) {
            codes.put(index, inputQuant.quantize(features.get(index)).toShort())
        }
        execute("$stagePrefix/asr", arrayOf(quantized), arrayOf(asrOutput))

        // Steps that cover only the zero-padded tail are not decoded: a CTC
        // head can repeat an earlier phrase there after the speaker stopped.
        val validFeatureFrames = ((samples.size + ASR_HOP_SAMPLES - 1) / ASR_HOP_SAMPLES)
            .coerceIn(1, ParakeetCtcContract.FEATURE_FRAMES)
        val validSteps = ((validFeatureFrames + ParakeetCtcContract.SUBSAMPLING - 1) /
            ParakeetCtcContract.SUBSAMPLING).coerceIn(1, ParakeetCtcContract.OUTPUT_FRAMES)
        val decoded = ctcGreedyDecodeQ16(
            logProbs = asrOutput.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer(),
            frames = validSteps,
            vocabSize = ParakeetCtcContract.VOCAB_SIZE,
            blankId = ParakeetCtcContract.BLANK_ID,
            params = outputQuant,
            skippedIds = setOf(PARAKEET_UNK_ID),
        )
        val transcript = detokenizeSentencePiece(decoded.ids, asrTokens)
        val tokenCount = decoded.ids.size
        val hypothesis = AsrHypothesis(
            transcript = transcript,
            rawLogProb = decoded.rawLogProb,
            tokenCount = tokenCount,
            normalizedLogProb = if (tokenCount == 0) {
                Double.NEGATIVE_INFINITY
            } else {
                decoded.rawLogProb / tokenCount
            },
            tokens = decoded.ids.indices.mapNotNull { index ->
                asrTokens.getOrNull(decoded.ids[index])?.let { piece ->
                    AsrToken(piece, decoded.startFrames[index] * ParakeetCtcContract.SAMPLES_PER_STEP)
                }
            },
        )
        Log.i(
            TAG,
            "ASR model=$asrArtifactPrefix featureFrames=$validFeatureFrames " +
                "ctcSteps=$validSteps rawLogProb=${hypothesis.rawLogProb} " +
                // Calibration candidates: CTC peak scores are overconfident.
                "frameMeanLogProb=${decoded.frameLogProbSum / validSteps} " +
                "nonBlankFrames=${decoded.nonBlankFrames} " +
                "tokenCount=$tokenCount normalizedLogProb=${hypothesis.normalizedLogProb} " +
                "transcript=\"$transcript\"",
        )
        return hypothesis
    }

    /**
     * Execute this branch's encoder and CPU decoder against a shared frontend
     * tensor. A read-only direct duplicate prevents either branch from changing
     * the shared buffer cursor while retaining the same underlying allocation.
     */
    fun recognizeSharedRnntFeatures(
        features: ByteBuffer,
        validFeatureFrames: Int,
    ): AsrHypothesis {
        check(asrMode == NpuAsrMode.ZIPFORMER_FAMILY_RNNT) {
            "Shared features require an RNN-T encoder"
        }
        require(features.isDirect && features.capacity() >= RNNT_FEATURE_BYTES) {
            "Shared RNN-T features must hold [1,1000,80] FLOAT32"
        }
        require(validFeatureFrames in 1..ZipformerFamilyRnntDecoder.FEATURE_FRAMES) {
            "Invalid RNN-T feature length $validFeatureFrames"
        }
        val branchInput = features.asReadOnlyBuffer().order(ByteOrder.nativeOrder()).apply {
            clear()
        }
        execute("$stagePrefix/asr", arrayOf(branchInput), arrayOf(asrOutput))
        // The encoder's exported length graph is (((T - 7) / 2) + 1) / 2.
        val validEncoderFrames = ((((validFeatureFrames - 7).coerceAtLeast(0) / 2) + 1) / 2)
            .coerceIn(1, ZipformerFamilyRnntDecoder.ENCODER_FRAMES)
        val hypothesis = checkNotNull(rnntDecoder) {
            "RNN-T host decoder is unavailable"
        }.decodeScored(asrOutput, validEncoderFrames)
        Log.i(
            TAG,
            "ASR model=$asrArtifactPrefix featureFrames=$validFeatureFrames " +
                "encoderFrames=$validEncoderFrames rawLogProb=${hypothesis.rawLogProb} " +
                "tokenCount=${hypothesis.tokenCount} " +
                "normalizedLogProb=${hypothesis.normalizedLogProb} " +
                "transcript=\"${hypothesis.transcript}\"",
        )
        return hypothesis
    }

    private fun recognizeParakeet(samples: FloatArray): String {
        checkNative(OneVoiceNative.nativeExtractAsrFeatures(samples, asrFeatures))
        execute("$stagePrefix/asr", arrayOf(asrFeatures), arrayOf(asrOutput))
        val logits = asrOutput.asFloatBuffer()
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
                "ASR model=parakeet-ctc samples=${samples.size} " +
                    "featureFrames=$validFeatureFrames " +
                    "ctcFrames=$validLogitFrames transcript=\"$transcript\"",
            )
        }
    }

    /** One Marian pass per sentence; see [splitSentences] for why. */
    fun translate(text: String): String {
        var tokens = 0
        return translateBySentence(text) { sentence ->
            translator.translate(sentence).also { tokens += translator.lastOutputTokens }
        }.also { lastMtOutputTokens = tokens }
    }

    /**
     * [translate] plus whether any sentence's output hit the decoder length
     * cap without EOS. The long-turn path splits and retries such a unit
     * instead of playing a cut translation.
     */
    fun translateChecked(text: String): MtOutput {
        var tokens = 0
        var truncated = false
        val translation = translateBySentence(text) { sentence ->
            translator.translate(sentence).also {
                tokens += translator.lastOutputTokens
                truncated = truncated || translator.lastOutputHitLimit
            }
        }
        lastMtOutputTokens = tokens
        return MtOutput(translation, tokens, truncated)
    }

    /** Largest Marian input among [text]'s sentences, as [translate] will feed them. */
    fun mtSourceTokens(text: String): Int =
        splitSentences(text).maxOfOrNull(translator::sourceTokenCount) ?: 0

    /** Tokens the Marian decoder emitted for the last [translate], summed over sentences. */
    @Volatile
    var lastMtOutputTokens: Int = 0
        private set

    /**
     * Scale a waveform in place so nothing leaves [-1, 1], returning the peak
     * that was measured.
     *
     * Kokoro-Vietnamese is far hotter than the English voice: measured on the
     * shipped models, a 60-token utterance peaks at 1.11 with 132 samples past
     * full scale, against 0.32 for English. AudioTrack's PCM_FLOAT path hard
     * clamps instead of scaling, so those samples come out as audible crackle
     * that grows with sentence length. Attenuating the whole utterance by one
     * factor keeps the waveform shape — and therefore the prosody — intact.
     */
    private fun limitPeak(samples: FloatArray): Float {
        var peak = 0f
        for (sample in samples) {
            val magnitude = abs(sample)
            if (magnitude > peak) peak = magnitude
        }
        if (peak > PEAK_CEILING) {
            val gain = PEAK_CEILING / peak
            for (index in samples.indices) samples[index] *= gain
        }
        return peak
    }

    /** Whole-utterance target speech at [PLAYBACK_SAMPLE_RATE], mono. */
    fun synthesize(text: String): FloatArray {
        vieneuTts?.let { synthesizer ->
            val startedNs = System.nanoTime()
            val generated = synthesizer.synthesize(text)
            val inferenceMs = (System.nanoTime() - startedNs) / 1_000_000L
            require(generated.all(Float::isFinite)) { "VieNeu returned non-finite waveform samples" }
            val resampled = if (synthesizer.sampleRate == PLAYBACK_SAMPLE_RATE) {
                generated
            } else {
                resamplePcm(generated, synthesizer.sampleRate, PLAYBACK_SAMPLE_RATE)
            }
            // Same edge trim as Supertonic: lead silence is pure time-to-first-audio.
            val samples = trimSilence(resampled, PLAYBACK_SAMPLE_RATE)
            val peak = limitPeak(samples)
            Log.i(
                TAG,
                "CPU whole-utterance TTS engine=vieneu textChars=${text.length} " +
                    "audioSamples=${samples.size} " +
                    "durationMs=${samples.size * 1_000L / PLAYBACK_SAMPLE_RATE} " +
                    "untrimmedMs=${resampled.size * 1_000L / PLAYBACK_SAMPLE_RATE} " +
                    "rawPeak=${"%.3f".format(peak)} inferenceMs=$inferenceMs " +
                    "totalMs=${(System.nanoTime() - startedNs) / 1_000_000L}",
            )
            return samples
        }
        cpuTts?.let { synthesizer ->
            val startedNs = System.nanoTime()
            val generated = cpuTtsGeneration
                ?.let { synthesizer.generateWithConfig(SupertonicFiles.prepareText(text), it) }
                ?: synthesizer.generate(text, 0, 1.0f)
            val inferenceMs = (System.nanoTime() - startedNs) / 1_000_000L
            require(cpuTtsEngine != CpuTtsEngine.KOKORO || generated.sampleRate == PLAYBACK_SAMPLE_RATE) {
                "CPU Kokoro returned ${generated.sampleRate} Hz, expected $PLAYBACK_SAMPLE_RATE Hz"
            }
            require(generated.samples.all(Float::isFinite)) {
                "CPU ${cpuTtsEngine.name} returned non-finite waveform samples"
            }
            // Playback, route priming and latency accounting all run at 24 kHz.
            val resampled = if (generated.sampleRate == PLAYBACK_SAMPLE_RATE) {
                generated.samples
            } else {
                resamplePcm(generated.samples, generated.sampleRate, PLAYBACK_SAMPLE_RATE)
            }
            // Kokoro's ~0.2 s natural lead is left alone; Supertonic's padding is not.
            val samples = if (cpuTtsEngine == CpuTtsEngine.SUPERTONIC) {
                trimSilence(resampled, PLAYBACK_SAMPLE_RATE)
            } else {
                resampled
            }
            val peak = limitPeak(samples)
            Log.i(
                TAG,
                "CPU whole-utterance TTS engine=${cpuTtsEngine.name.lowercase()} " +
                    "textChars=${text.length} " +
                    "audioSamples=${samples.size} " +
                    "durationMs=${samples.size * 1_000L / PLAYBACK_SAMPLE_RATE} " +
                    "sourceRate=${generated.sampleRate} " +
                    "untrimmedMs=${resampled.size * 1_000L / PLAYBACK_SAMPLE_RATE} " +
                    "rawPeak=${"%.3f".format(peak)} " +
                    "inferenceMs=$inferenceMs " +
                    "totalMs=${(System.nanoTime() - startedNs) / 1_000_000L}",
            )
            return samples
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
            "$stagePrefix/tts_encoder",
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
                "$stagePrefix/tts_prosody",
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
                "$stagePrefix/tts_decoder",
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
        vieneuTts?.close()
        cpuTts?.release()
        ttsFrontend?.release()
        voices?.close()
        translator.close()
        rnntDecoder?.close()
    }

    private fun execute(stage: String, inputs: Array<ByteBuffer>, outputs: Array<ByteBuffer>) {
        checkNative(OneVoiceNative.nativeExecuteGraph(stage, inputs, outputs))
    }

    private fun checkNative(error: String?) {
        check(error == null) { error ?: "unknown QNN execution error" }
    }

}

private enum class NpuAsrMode {
    PARAKEET_CTC,
    /** sherpa-onnx SM8550 Parakeet-TDT-CTC-110M CTC head, UFIXED_16 I/O. */
    PARAKEET_CTC_Q16,
    ZIPFORMER_FAMILY_RNNT;

    companion object {
        fun from(stage: StageSpec): NpuAsrMode {
            val input = stage.inputs.singleOrNull()
            val output = stage.outputs.singleOrNull()
            return when {
                input?.name == "x" && input.dtype == "FLOAT_32" &&
                    input.dims == listOf(1, 1000, 80) &&
                    output?.name == "output_0" && output.dtype == "FLOAT_32" &&
                    output.dims == listOf(1, 248, 512) -> ZIPFORMER_FAMILY_RNNT

                input?.name == "input_features" && input.dtype == "FLOAT_32" &&
                    input.dims == listOf(1, 1001, 80) &&
                    output?.name == "output_0" && output.dtype == "FLOAT_32" &&
                    output.dims == listOf(1, 126, 1025) -> PARAKEET_CTC

                input?.name == "x" && input.dtype == "UFIXED_16" &&
                    input.dims == listOf(
                        1,
                        ParakeetCtcContract.FEATURE_FRAMES,
                        ParakeetCtcContract.FEATURE_DIM,
                    ) &&
                    output?.name == "log_probs" && output.dtype == "UFIXED_16" &&
                    output.dims == listOf(
                        1,
                        ParakeetCtcContract.OUTPUT_FRAMES,
                        ParakeetCtcContract.VOCAB_SIZE,
                    ) -> PARAKEET_CTC_Q16

                else -> error(
                    "Unsupported NPU ASR contract: ${stage.inputs} -> ${stage.outputs}",
                )
            }
        }
    }
}

/** Fixed-shape cached Marian decoder driven one token at a time on HTP. */
internal class QnnMarianTranslator private constructor(
    private val stagePrefix: String,
    private val sentencePiece: SentencePieceUnigram,
    private val vocab: Map<String, Int>,
    private val idToToken: Array<String?>,
    private val eosId: Int,
    private val padId: Int,
    private val decoderStartId: Int,
    private val targetPrefixId: Int?,
    private val maxLength: Int,
) : Closeable {
    private val ids = direct(256 * Int.SIZE_BYTES)
    private val mask = direct(256 * Int.SIZE_BYTES)
    private val cross = Array(12) { direct(1 * 8 * 256 * 64 * Float.SIZE_BYTES) }
    private val past = Array(12) { direct(1 * 8 * 255 * 64 * Float.SIZE_BYTES) }

    /**
     * Tokens emitted by the most recent [translate].
     *
     * Read by the benchmark path right after the call, on the same thread —
     * translate() is only ever invoked from the single inference worker, so a
     * plain field is sufficient and adds nothing to the hot loop.
     */
    @Volatile
    var lastOutputTokens: Int = 0
        private set
    private val present = Array(12) { direct(1 * 8 * 1 * 64 * Float.SIZE_BYTES) }
    private val logits = direct(idToToken.size * Float.SIZE_BYTES)
    private val decoderId = direct(Int.SIZE_BYTES)
    private val position = direct(Int.SIZE_BYTES)

    companion object {
        /** Fixed encoder input: target prefix + pieces + EOS. */
        private const val MAX_SOURCE_SLOTS = 256

        fun load(root: File, stagePrefix: String): QnnMarianTranslator {
            val vocabObject = JSONObject(File(root, "mt_vocab.json").readText())
            val vocab = HashMap<String, Int>(vocabObject.length() * 2)
            val idToToken = arrayOfNulls<String>(vocabObject.length())
            vocabObject.keys().forEach { token ->
                val id = vocabObject.getInt(token)
                vocab[token] = id
                if (id in idToToken.indices) idToToken[id] = token
            }
            val config = JSONObject(File(root, "mt_config.json").readText())
            val targetPrefixId = if (config.has("target_prefix_token_id") &&
                !config.isNull("target_prefix_token_id")) {
                config.getInt("target_prefix_token_id").also { id ->
                    require(id in idToToken.indices && idToToken[id] != null) {
                        "Invalid Marian target prefix token ID $id"
                    }
                }
            } else {
                null
            }
            return QnnMarianTranslator(
                stagePrefix = stagePrefix,
                sentencePiece = SentencePieceUnigram.load(File(root, "mt_source.spm")),
                vocab = vocab,
                idToToken = idToToken,
                eosId = config.getInt("eos_token_id"),
                padId = config.getInt("pad_token_id"),
                decoderStartId = config.getInt("decoder_start_token_id"),
                targetPrefixId = targetPrefixId,
                maxLength = config.optInt("max_length", 128).coerceIn(8, 128),
            )
        }
    }

    /**
     * True when the last [translate] stopped at [maxLength] without EOS: the
     * output is cut, and a caller must not present it as a full translation.
     */
    @Volatile
    var lastOutputHitLimit: Boolean = false
        private set

    /** Encoder input slots [text] needs: target prefix, pieces and EOS. */
    fun sourceTokenCount(text: String): Int {
        val pieces = sentencePiece.encodeToPieces(text).size
        return if (pieces == 0) 0 else pieces + (if (targetPrefixId != null) 1 else 0) + 1
    }

    fun translate(text: String): String {
        val pieces = sentencePiece.encodeToPieces(text)
        lastOutputHitLimit = false
        if (pieces.isEmpty()) return ""
        val idsView = ids.asIntBuffer()
        val maskView = mask.asIntBuffer()
        var sourceCount = if (targetPrefixId != null) 1 else 0
        // The encoder tensor is 256 slots: prefix + pieces + EOS. Truncating
        // here used to drop the tail of the sentence silently.
        if (pieces.size > MAX_SOURCE_SLOTS - 1 - sourceCount) {
            throw MarianInputTooLongException(
                "Marian $stagePrefix input needs ${pieces.size + sourceCount + 1} slots, " +
                    "the graph holds $MAX_SOURCE_SLOTS",
            )
        }
        repeat(MAX_SOURCE_SLOTS) { idsView.put(it, padId) }
        zero(mask)
        sourceCount = 0
        targetPrefixId?.let { prefix ->
            idsView.put(sourceCount, prefix)
            maskView.put(sourceCount, 1)
            sourceCount++
        }
        val pieceCount = pieces.size
        repeat(pieceCount) { index ->
            idsView.put(sourceCount, vocab[pieces[index]] ?: 1)
            maskView.put(sourceCount, 1)
            sourceCount++
        }
        idsView.put(sourceCount, eosId)
        maskView.put(sourceCount, 1)

        execute("$stagePrefix/mt_encoder", arrayOf(ids, mask), cross)
        past.forEach(::zero)
        val generated = IntArray(maxLength)
        var generatedSize = 0
        var current = decoderStartId
        var reachedEos = false

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
            execute(
                "$stagePrefix/mt_decoder",
                decoderInputs.toTypedArray(),
                arrayOf(logits, *present),
            )
            val values = logits.asFloatBuffer()
            var next = -1
            var score = Float.NEGATIVE_INFINITY
            repeat(idToToken.size) { id ->
                val value = values.get(id)
                if (id != padId && !value.isNaN() && value > score) {
                    next = id
                    score = value
                }
            }
            check(next >= 0) { "Marian decoder returned no finite token" }
            if (next == eosId) {
                reachedEos = true
                break
            }
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
        // The decoder is autoregressive: mt_ms is proportional to this count, so
        // a latency row without it cannot be compared against another row.
        lastOutputTokens = generatedSize
        lastOutputHitLimit = !reachedEos
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

/** A Marian input that does not fit the fixed encoder tensor; it is never truncated. */
internal class MarianInputTooLongException(message: String) : IllegalArgumentException(message)

private fun direct(bytes: Int): ByteBuffer =
    ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())

private fun zero(buffer: ByteBuffer) {
    val longs = buffer.asLongBuffer()
    for (index in 0 until longs.capacity()) longs.put(index, 0L)
    val covered = longs.capacity() * Long.SIZE_BYTES
    for (index in covered until buffer.capacity()) buffer.put(index, 0)
}
