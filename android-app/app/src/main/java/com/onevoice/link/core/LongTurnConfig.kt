package com.onevoice.link.core

import com.onevoice.link.BuildConfig
import org.json.JSONObject

/**
 * How a live turn longer than one ASR window is captured.
 *
 * LEGACY is the strict one-window turn: the 10 s ceiling closes the microphone
 * and whatever the speaker says while the translation is produced is lost.
 * ADAPTIVE keeps capturing across ASR windows; one turn becomes several chunks
 * that are recognized while the speaker is still talking. It is an APK-level
 * flag (`-PlongTurnMode`), never a UI mode, so a rollback is one rebuild.
 */
internal enum class LongTurnMode {
    LEGACY,
    ADAPTIVE,
    ;

    companion object {
        fun parse(value: String): LongTurnMode =
            entries.firstOrNull { it.name == value }
                ?: throw IllegalArgumentException(
                    "longTurnMode must be LEGACY or ADAPTIVE (got '$value')",
                )
    }
}

/** Where a window is cut when the turn has not ended yet. */
internal enum class SplitPolicy {
    /** Cut only at the ASR window ceiling, always with overlap (baseline B1). */
    FIXED,
    /** Prefer a non-speech pause after the soft minimum; overlap only when forced (B2+). */
    ADAPTIVE,
}

/**
 * Every long-turn tuning value in one place, recorded in the session header so
 * a log always names the policy that produced it.
 *
 * The defaults are starting points from docs/LONG_FORM_SPEECH_IMPLEMENTATION.md
 * §5.1, not measured optima. All lengths are in 16 kHz samples: capture frames
 * are 20 ms but Silero decides on 32 ms windows, so counting frames would mix
 * two clocks and miscount partial reads.
 */
internal data class LongTurnConfig(
    val mode: LongTurnMode,
    val splitPolicy: SplitPolicy = SplitPolicy.ADAPTIVE,
    /** Translate and synthesize during capture (B3); false defers both to turn end (B1/B2). */
    val backgroundTranslation: Boolean = true,
    /** A window may be cut at a pause once it holds this much audio. */
    val softSplitSamples: Int = ms(7_000),
    /** Non-speech run long enough to cut a window without overlap. */
    val microPauseSamples: Int = ms(160),
    /** Audio shared by two windows when no reliable pause was found. */
    val overlapSamples: Int = ms(960),
    /**
     * Left context a window gets after a pause cut. The graph is a fixed 10 s
     * either way, so it costs no compute; without it, a short final window
     * ("...or known") was decoded cold and lost its word. Its words before
     * the cut are dropped by the core rule, never committed twice.
     */
    val pauseContextSamples: Int = ms(960),
    /**
     * Widen every later window's left context, when it is emitted, to fill
     * the model's fixed 10 s input: HTP runs the whole graph either way, so
     * the extra context costs no NPU time (the VI RNN-T decoder walks a few
     * more frames on CPU). A short final window then hears the sentence it
     * ends: EV-7496 decoded "or known" as "no" from a 1.9 s window. The
     * context only informs the decode; words outside the core are dropped.
     * [pauseContextSamples] and the forced overlap stay the minimum context.
     */
    val fillLeftContext: Boolean = true,
    /** Skip units of only hesitation words and cut leading ones ([FillerWords]). */
    val dropFillerWords: Boolean = true,
    /** Hard model contract: every ASR call, context included, holds at most this. */
    val maxWindowSamples: Int = NpuEngine.MAX_SEGMENT_SAMPLES,
    /** Trailing non-speech that ends the turn; kept at the legacy 1 s baseline. */
    val endSilenceSamples: Int = NpuEngine.END_SILENCE_FRAMES * NpuEngine.FRAME_SAMPLES,
    /** Longest accepted turn; reaching it ends the turn with reason turn_cap. */
    val maxTurnSamples: Int = ms(120_000),
    /** Two windows' copies of one word may start this far apart and still be one word. */
    val mergeToleranceSamples: Int = ms(120),
    /** Marian source tokens per sentence, prefix and EOS included; output is capped at 128. */
    val maxUnitSourceTokens: Int = 96,
    /**
     * Without ASR punctuation (VI), the first unit of a turn is released at a
     * word gap once the text exceeds this many tokens (~5 s of speech), so the
     * first audio is short to render; later units use [unitTargetTokens]
     * (~9 s, about one window). See [TranslationUnitBuilder].
     */
    val firstUnitTargetTokens: Int = 24,
    val unitTargetTokens: Int = 40,
    /** Soft cap on synthesized audio waiting for playback. */
    val ttsQueueBudgetBytes: Long = 16L * 1024 * 1024,
    /** Everything PCM held for one turn: archive, windows, TTS queue, staging. */
    val pcmBudgetBytes: Long = 32L * 1024 * 1024,
    /** Pause written between two synthesized units of one turn. */
    val unitPauseMs: Int = 150,
    /** Replay-button copy of a long turn (~87 s of 24 kHz mono); longer turns keep none. */
    val replayMaxBytes: Long = 8L * 1024 * 1024,
) {
    init {
        require(overlapSamples % OVERLAP_GRID_SAMPLES == 0) {
            "overlap must be a multiple of 80 ms so CTC/RNN-T frame grids stay aligned"
        }
        require(overlapSamples in OVERLAP_GRID_SAMPLES until softSplitSamples) {
            "overlap must be shorter than the soft split"
        }
        require(softSplitSamples < maxWindowSamples) { "soft split must be below the window ceiling" }
        require(maxWindowSamples <= NpuEngine.MAX_SEGMENT_SAMPLES) {
            "ASR windows cannot exceed the ${NpuEngine.MAX_SEGMENT_SAMPLES}-sample model contract"
        }
        require(microPauseSamples > 0 && endSilenceSamples > microPauseSamples)
        require(pauseContextSamples in 0 until softSplitSamples)
        require(softSplitSamples + maxOf(pauseContextSamples, overlapSamples / 2) < maxWindowSamples) {
            "the soft split plus left context must leave room before the window ceiling"
        }
        require(maxTurnSamples >= maxWindowSamples)
        require(firstUnitTargetTokens in 2..unitTargetTokens && unitTargetTokens <= maxUnitSourceTokens) {
            "unit targets must satisfy 2 <= first <= later <= maxUnitSourceTokens"
        }
        val archiveBytes = (maxTurnSamples + NpuEngine.PRE_ROLL_SAMPLES).toLong() * Short.SIZE_BYTES
        val windowBytes = 2L * maxWindowSamples * Float.SIZE_BYTES
        require(archiveBytes + windowBytes + ttsQueueBudgetBytes + replayMaxBytes <= pcmBudgetBytes) {
            "long-turn PCM buffers exceed the ${pcmBudgetBytes}-byte budget"
        }
    }

    val archiveCapacitySamples: Int get() = maxTurnSamples + NpuEngine.PRE_ROLL_SAMPLES

    fun toJson(): JSONObject = JSONObject().apply {
        put("mode", mode.name)
        put("splitPolicy", splitPolicy.name)
        put("backgroundTranslation", backgroundTranslation)
        put("softSplitMs", toMs(softSplitSamples))
        put("microPauseMs", toMs(microPauseSamples))
        put("overlapMs", toMs(overlapSamples))
        put("pauseContextMs", toMs(pauseContextSamples))
        put("fillLeftContext", fillLeftContext)
        put("dropFillerWords", dropFillerWords)
        put("maxWindowMs", toMs(maxWindowSamples))
        put("endSilenceMs", toMs(endSilenceSamples))
        put("maxTurnMs", toMs(maxTurnSamples))
        put("mergeToleranceMs", toMs(mergeToleranceSamples))
        put("maxUnitSourceTokens", maxUnitSourceTokens)
        put("firstUnitTargetTokens", firstUnitTargetTokens)
        put("unitTargetTokens", unitTargetTokens)
        put("ttsQueueBudgetBytes", ttsQueueBudgetBytes)
        put("pcmBudgetBytes", pcmBudgetBytes)
        put("unitPauseMs", unitPauseMs)
    }

    /** Benchmark baselines of docs/LONG_FORM_SPEECH_IMPLEMENTATION.md §13.1 on the same code. */
    fun forBaseline(name: String): LongTurnConfig = when (name.uppercase()) {
        "B0" -> copy(mode = LongTurnMode.LEGACY)
        "B1" -> copy(
            mode = LongTurnMode.ADAPTIVE,
            splitPolicy = SplitPolicy.FIXED,
            backgroundTranslation = false,
        )
        "B2" -> copy(
            mode = LongTurnMode.ADAPTIVE,
            splitPolicy = SplitPolicy.ADAPTIVE,
            backgroundTranslation = false,
        )
        "B3" -> copy(
            mode = LongTurnMode.ADAPTIVE,
            splitPolicy = SplitPolicy.ADAPTIVE,
            backgroundTranslation = true,
        )
        else -> throw IllegalArgumentException("unknown long-turn baseline '$name' (B0..B3)")
    }

    companion object {
        /** CTC emits every 8 x 10 ms features; RNN-T every 4. 80 ms serves both. */
        const val OVERLAP_GRID_SAMPLES = 1_280

        fun ms(value: Int): Int = value * NpuEngine.SAMPLE_RATE / 1_000
        fun toMs(samples: Int): Long = samples * 1_000L / NpuEngine.SAMPLE_RATE

        fun fromBuildConfig(): LongTurnConfig = LongTurnConfig(
            mode = LongTurnMode.parse(BuildConfig.LONG_TURN_MODE),
            softSplitSamples = ms(BuildConfig.LONG_TURN_SOFT_SPLIT_MS),
            microPauseSamples = ms(BuildConfig.LONG_TURN_MICRO_PAUSE_MS),
            overlapSamples = ms(BuildConfig.LONG_TURN_OVERLAP_MS),
            maxTurnSamples = ms(BuildConfig.LONG_TURN_MAX_TURN_S * 1_000),
            dropFillerWords = BuildConfig.DROP_FILLER_WORDS,
        )
    }
}
