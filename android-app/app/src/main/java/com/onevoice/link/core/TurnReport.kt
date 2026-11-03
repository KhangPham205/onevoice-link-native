package com.onevoice.link.core

/** Public mirror of the half-duplex state, for an on-screen live indicator. */
enum class LivePhase {
    IDLE,
    /** Microphone open; speech may not have started yet. */
    CAPTURING,
    PROCESSING,
    PLAYING,
}

/**
 * One bounded utterance as the demo screen and the session log see it.
 *
 * Emitted for every bounded turn, including rejected ones, from the same
 * values as the `OneVoiceRouting` row, so on-screen figures and the logcat
 * calibration contract can never disagree.
 *
 * [firstAudioMs] runs from the utterance bound (speech end + VAD hangover, SCO
 * closed) to the first translated sample handed to AudioTrack. The hangover
 * itself is fixed VAD policy and is reported separately, never folded in.
 */
data class TurnReport(
    val turnId: Long,
    val timestampMs: Long,
    val audioMs: Long,
    val route: String,
    val direction: TranslationDirection?,
    val reason: String,
    val executionPath: ExecutionPath?,
    val transcript: String?,
    val translation: String?,
    val error: String?,
    val viScore: Double?,
    val enScore: Double?,
    val frontendMs: Long,
    val dualAsrWallMs: Long,
    val mtMs: Long,
    val ttsMs: Long,
    val queueWaitMs: Long,
    val ttsAudioMs: Long?,
    val firstAudioMs: Long?,
    val postBoundTotalMs: Long,
) {
    val succeeded: Boolean get() = error == null && firstAudioMs != null
}
