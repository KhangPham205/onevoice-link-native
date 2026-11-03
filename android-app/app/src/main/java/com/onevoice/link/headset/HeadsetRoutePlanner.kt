package com.onevoice.link.headset

import com.onevoice.link.core.TranslationDirection

/**
 * Pure one-speaker/many-listener plan. It neither opens Bluetooth nor routes audio.
 * A future floor-control transport may consume this only after authenticating the
 * active speaker endpoint.
 */
data class HeadsetRoutePlan(
    val speakerId: String,
    val direction: TranslationDirection,
    val outputLanguage: HeadsetLanguage,
    val listenerIds: Set<String>,
)

enum class RoutePlanFailure {
    NO_ACTIVE_SPEAKER,
    MULTIPLE_ACTIVE_SPEAKERS,
    UNKNOWN_ACTIVE_SPEAKER,
    ACTIVE_SPEAKER_UNASSIGNED,
    NO_ASSIGNED_LISTENERS,
}

sealed interface HeadsetRoutePlanResult {
    data class Ready(val plan: HeadsetRoutePlan) : HeadsetRoutePlanResult
    data class Rejected(val reason: RoutePlanFailure) : HeadsetRoutePlanResult
}

object HeadsetRoutePlanner {
    /**
     * Snapshot assignments at the utterance/floor boundary. Callers must retain
     * the returned immutable plan until that utterance finishes.
     */
    fun plan(
        activeSpeakerIds: Set<String>,
        connectedHeadsets: List<ConnectedHeadset>,
    ): HeadsetRoutePlanResult {
        if (activeSpeakerIds.isEmpty()) {
            return HeadsetRoutePlanResult.Rejected(RoutePlanFailure.NO_ACTIVE_SPEAKER)
        }
        if (activeSpeakerIds.size != 1) {
            return HeadsetRoutePlanResult.Rejected(RoutePlanFailure.MULTIPLE_ACTIVE_SPEAKERS)
        }

        val speakerId = activeSpeakerIds.single()
        val speaker = connectedHeadsets.singleOrNull { it.id == speakerId }
            ?: return HeadsetRoutePlanResult.Rejected(RoutePlanFailure.UNKNOWN_ACTIVE_SPEAKER)
        val speakerLanguage = speaker.language
            ?: return HeadsetRoutePlanResult.Rejected(RoutePlanFailure.ACTIVE_SPEAKER_UNASSIGNED)
        val outputLanguage = when (speakerLanguage) {
            HeadsetLanguage.VI -> HeadsetLanguage.EN
            HeadsetLanguage.EN -> HeadsetLanguage.VI
        }
        val listenerIds = connectedHeadsets.asSequence()
            .filter { it.id != speakerId && it.language == outputLanguage }
            .map { it.id }
            .toSet()
        if (listenerIds.isEmpty()) {
            return HeadsetRoutePlanResult.Rejected(RoutePlanFailure.NO_ASSIGNED_LISTENERS)
        }

        return HeadsetRoutePlanResult.Ready(
            HeadsetRoutePlan(
                speakerId = speakerId,
                direction = when (speakerLanguage) {
                    HeadsetLanguage.VI -> TranslationDirection.VI_TO_EN
                    HeadsetLanguage.EN -> TranslationDirection.EN_TO_VI
                },
                outputLanguage = outputLanguage,
                listenerIds = listenerIds,
            ),
        )
    }
}
