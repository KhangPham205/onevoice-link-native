package com.onevoice.link.headset

import com.onevoice.link.core.TranslationDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadsetRoutePlannerTest {
    @Test
    fun vietnameseSpeakerTargetsEveryEnglishListener() {
        val speaker = headset("speaker", HeadsetLanguage.VI)
        val englishA = headset("english-a", HeadsetLanguage.EN)
        val englishB = headset("english-b", HeadsetLanguage.EN)
        val vietnameseListener = headset("vietnamese", HeadsetLanguage.VI)

        val result = HeadsetRoutePlanner.plan(
            activeSpeakerIds = setOf(speaker.id),
            connectedHeadsets = listOf(speaker, englishA, englishB, vietnameseListener),
        )

        assertTrue(result is HeadsetRoutePlanResult.Ready)
        val plan = (result as HeadsetRoutePlanResult.Ready).plan
        assertEquals(TranslationDirection.VI_TO_EN, plan.direction)
        assertEquals(HeadsetLanguage.EN, plan.outputLanguage)
        assertEquals(setOf(englishA.id, englishB.id), plan.listenerIds)
    }

    @Test
    fun moreThanOneSpeakerFailsClosed() {
        val result = HeadsetRoutePlanner.plan(
            activeSpeakerIds = setOf("a", "b"),
            connectedHeadsets = listOf(
                headset("a", HeadsetLanguage.VI),
                headset("b", HeadsetLanguage.EN),
            ),
        )

        assertEquals(
            HeadsetRoutePlanResult.Rejected(RoutePlanFailure.MULTIPLE_ACTIVE_SPEAKERS),
            result,
        )
    }

    @Test
    fun unassignedSpeakerFailsClosed() {
        val result = HeadsetRoutePlanner.plan(
            activeSpeakerIds = setOf("speaker"),
            connectedHeadsets = listOf(
                headset("speaker", null),
                headset("listener", HeadsetLanguage.EN),
            ),
        )

        assertEquals(
            HeadsetRoutePlanResult.Rejected(RoutePlanFailure.ACTIVE_SPEAKER_UNASSIGNED),
            result,
        )
    }

    @Test
    fun ordinaryAudioRouteNeverClaimsNamedHeadsetDelivery() {
        val targeted = DefaultSystemTargetAudioRouter.capabilityFor(
            TargetAudioRoutingRequest(
                outputLanguage = HeadsetLanguage.EN,
                listenerIds = setOf("listener"),
            ),
        )
        val untargeted = DefaultSystemTargetAudioRouter.capabilityFor(
            TargetAudioRoutingRequest(
                outputLanguage = HeadsetLanguage.EN,
                listenerIds = emptySet(),
            ),
        )

        assertEquals(TargetAudioRoutingCapability.REQUIRES_PRIVILEGED_SERVICE, targeted)
        assertEquals(TargetAudioRoutingCapability.DEFAULT_SYSTEM_ROUTE_ONLY, untargeted)
    }

    private fun headset(id: String, language: HeadsetLanguage?) = ConnectedHeadset(
        id = id,
        displayName = id,
        transports = setOf(HeadsetTransport.LE_AUDIO),
        language = language,
        assignmentPersistent = true,
    )
}
