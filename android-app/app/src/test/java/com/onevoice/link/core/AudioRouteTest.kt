package com.onevoice.link.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRouteTest {
    @Test
    fun usbLockIgnoresTheDashboardAndConnectedHeadsets() {
        for (preference in AudioRoutePreference.entries) {
            assertEquals(
                RouteChoice.Phone,
                chooseAudioRoute(
                    preference,
                    listOf("jbl"),
                    "jbl",
                    AudioInputMode.USB,
                    usbMicrophoneAttached = true,
                ),
            )
        }
    }

    @Test
    fun usbLockFailsClosedWithoutAUsbMicrophone() {
        val choice = chooseAudioRoute(
            AudioRoutePreference.AUTO,
            emptyList<String>(),
            null,
            AudioInputMode.USB,
            usbMicrophoneAttached = false,
        )
        assertTrue(choice is RouteChoice.Unavailable)
        assertTrue((choice as RouteChoice.Unavailable).reason.contains("USB-C microphone"))
    }

    @Test
    fun dashboardLockKeepsTheStoredChoice() {
        assertEquals(
            RouteChoice.Bluetooth("jbl"),
            chooseAudioRoute(
                AudioRoutePreference.BLUETOOTH,
                listOf("jbl"),
                null,
                AudioInputMode.DASHBOARD,
                usbMicrophoneAttached = true,
            ),
        )
    }

    @Test
    fun phoneModeNeverUsesAHeadsetOrTheUsbMicChoice() {
        for (preference in AudioRoutePreference.entries) {
            assertEquals(
                RouteChoice.Phone,
                chooseAudioRoute(
                    preference,
                    listOf("jbl"),
                    "jbl",
                    AudioInputMode.PHONE,
                    usbMicrophoneAttached = false,
                ),
            )
        }
    }

    @Test
    fun dashboardMicChoiceMapsToItsModeAndDefaultsToThePhone() {
        assertEquals(AudioInputMode.PHONE, MicInputPreference.PHONE.mode)
        assertEquals(AudioInputMode.USB, MicInputPreference.USB.mode)
        assertEquals(MicInputPreference.USB, MicInputPreference.fromPersistedValue("usb"))
        assertEquals(MicInputPreference.PHONE, MicInputPreference.fromPersistedValue(null))
        assertEquals(MicInputPreference.PHONE, MicInputPreference.fromPersistedValue("bluetooth"))
    }

    @Test
    fun inputModeParsesOnlyKnownValues() {
        assertEquals(AudioInputMode.SELECT, AudioInputMode.parse("SELECT"))
        assertEquals(AudioInputMode.PHONE, AudioInputMode.parse("PHONE"))
        assertEquals(AudioInputMode.USB, AudioInputMode.parse("USB"))
        assertEquals(AudioInputMode.DASHBOARD, AudioInputMode.parse("DASHBOARD"))
        assertTrue(runCatching { AudioInputMode.parse("usb") }.isFailure)
    }

    @Test
    fun phonePreferenceIgnoresConnectedHeadsets() {
        assertEquals(
            RouteChoice.Phone,
            chooseAudioRoute(AudioRoutePreference.PHONE, listOf("jbl"), "jbl"),
        )
    }

    @Test
    fun autoUsesTheSoleHeadset() {
        assertEquals(
            RouteChoice.Bluetooth("jbl"),
            chooseAudioRoute(AudioRoutePreference.AUTO, listOf("jbl"), null),
        )
    }

    @Test
    fun autoFallsBackToPhoneWithoutHeadset() {
        assertEquals(
            RouteChoice.Phone,
            chooseAudioRoute(AudioRoutePreference.AUTO, emptyList<String>(), null),
        )
    }

    @Test
    fun autoNeverGuessesBetweenHeadsets() {
        assertEquals(
            RouteChoice.Phone,
            chooseAudioRoute(AudioRoutePreference.AUTO, listOf("jbl", "g435"), null),
        )
    }

    @Test
    fun androidSelectedHeadsetResolvesAmbiguity() {
        assertEquals(
            RouteChoice.Bluetooth("g435"),
            chooseAudioRoute(AudioRoutePreference.BLUETOOTH, listOf("jbl", "g435"), "g435"),
        )
    }

    @Test
    fun explicitBluetoothFailsClosedWithoutHeadset() {
        assertTrue(
            chooseAudioRoute(AudioRoutePreference.BLUETOOTH, emptyList<String>(), null)
                is RouteChoice.Unavailable,
        )
        assertTrue(
            chooseAudioRoute(AudioRoutePreference.BLUETOOTH, listOf("jbl", "g435"), null)
                is RouteChoice.Unavailable,
        )
    }

    @Test
    fun unknownPersistedValueMeansAuto() {
        assertEquals(AudioRoutePreference.AUTO, AudioRoutePreference.fromPersistedValue(null))
        assertEquals(AudioRoutePreference.AUTO, AudioRoutePreference.fromPersistedValue("bogus"))
        assertEquals(AudioRoutePreference.PHONE, AudioRoutePreference.fromPersistedValue("phone"))
    }

    @Test
    fun phonePlaybackDuplicatesMonoOnBothChannels() {
        assertArrayEquals(
            floatArrayOf(0.1f, 0.1f, -0.2f, -0.2f),
            duplicateMonoStereo(floatArrayOf(0.1f, -0.2f)),
            0f,
        )
    }
}
