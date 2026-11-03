package com.onevoice.link.core

import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import java.io.File
import java.text.Normalizer

/** Which CPU model renders a direction's target speech; an APK-level A/B knob. */
internal enum class CpuTtsEngine {
    /** Kokoro-82M through sherpa-onnx's espeak-ng phonemizer, 24 kHz. */
    KOKORO,

    /**
     * Supertonic-3 (sherpa-onnx int8 export): Unicode graphemes in, no G2P, so
     * Vietnamese tones and initials reach the model as written. 44.1 kHz out.
     */
    SUPERTONIC,
    ;

    companion object {
        fun parse(value: String): CpuTtsEngine =
            entries.firstOrNull { it.name == value }
                ?: throw IllegalArgumentException(
                    "viTtsEngine must be KOKORO or SUPERTONIC (got '$value')",
                )
    }
}

/** Supertonic-3 files under a direction root, as push-npu-models.sh stages them. */
internal object SupertonicFiles {
    const val DIR = "tts_supertonic"
    const val DURATION_PREDICTOR = "duration_predictor.int8.onnx"
    const val TEXT_ENCODER = "text_encoder.int8.onnx"
    const val VECTOR_ESTIMATOR = "vector_estimator.int8.onnx"
    const val VOCODER = "vocoder.int8.onnx"
    const val TTS_JSON = "tts.json"
    const val UNICODE_INDEXER = "unicode_indexer.bin"
    const val VOICE_STYLE = "voice.bin"

    val ALL = listOf(
        DURATION_PREDICTOR, TEXT_ENCODER, VECTOR_ESTIMATOR, VOCODER,
        TTS_JSON, UNICODE_INDEXER, VOICE_STYLE,
    )

    private val VOICE = Regex("([FM])([1-5])")

    /**
     * sherpa's voice.bin concatenates the upstream style JSONs in sorted file
     * order, so F1..F5 are sids 0..4 and M1..M5 are sids 5..9.
     */
    fun sid(voice: String): Int {
        val match = requireNotNull(VOICE.matchEntire(voice)) {
            "Supertonic voice must be F1..F5 or M1..M5 (got '$voice')"
        }
        val index = match.groupValues[2].toInt() - 1
        return if (match.groupValues[1] == "F") index else 5 + index
    }

    /**
     * Supertonic was trained on NFKD text (base letter + combining marks), and
     * its unicode_indexer has no entry for precomposed Vietnamese letters such
     * as ơ, ư, ạ, ữ, ệ. sherpa-onnx 1.13.4 decomposes only a few Latin-1
     * accents itself, so every other vowel was silently dropped (round-trip
     * CER 31.9%). Decomposing here first is idempotent on newer sherpa.
     */
    fun prepareText(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKD)

    /** Supertonic takes the language as text; it is the direction's target. */
    fun language(stagePrefix: String): String = when (stagePrefix) {
        "en-vi" -> "vi"
        "vi-en" -> "en"
        else -> throw IllegalArgumentException("Unknown direction $stagePrefix")
    }

    fun config(directionRoot: File, threads: Int): OfflineTtsConfig {
        val dir = File(directionRoot, DIR)
        return OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                supertonic = OfflineTtsSupertonicModelConfig(
                    durationPredictor = File(dir, DURATION_PREDICTOR).absolutePath,
                    textEncoder = File(dir, TEXT_ENCODER).absolutePath,
                    vectorEstimator = File(dir, VECTOR_ESTIMATOR).absolutePath,
                    vocoder = File(dir, VOCODER).absolutePath,
                    ttsJson = File(dir, TTS_JSON).absolutePath,
                    unicodeIndexer = File(dir, UNICODE_INDEXER).absolutePath,
                    voiceStyle = File(dir, VOICE_STYLE).absolutePath,
                ),
                numThreads = threads,
            ),
            // One chunk per call: the MT output is a single bounded turn.
            maxNumSentences = 1,
        )
    }

    /** Per-call options; sherpa reads lang and steps for Supertonic from these. */
    fun generation(stagePrefix: String, voice: String, steps: Int, speed: Float): GenerationConfig =
        GenerationConfig(
            speed = speed,
            sid = sid(voice),
            numSteps = steps,
            extra = mapOf("lang" to language(stagePrefix)),
        )
}
