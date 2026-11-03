package com.onevoice.link.headset

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.onevoice.link.core.TranslationDirection

/** Immutable speaker route captured when VAD opens an utterance. */
data class VerifiedSpeakerRoute(
    val speakerId: String,
    val spokenLanguage: HeadsetLanguage,
    val direction: TranslationDirection,
    val deviceName: String,
)

enum class SpeakerRouteFailure {
    BLUETOOTH_PERMISSION_REQUIRED,
    NO_ROUTED_INPUT,
    INPUT_IS_NOT_BLUETOOTH_HEADSET,
    STABLE_IDENTITY_UNAVAILABLE,
    HEADSET_LANGUAGE_UNASSIGNED,
    NO_ASSIGNED_COMMUNICATION_HEADSET,
    MULTIPLE_ASSIGNED_COMMUNICATION_HEADSETS,
    COMMUNICATION_ROUTE_SELECTION_FAILED,
}

sealed interface SpeakerRouteResolution {
    data class Ready(val route: VerifiedSpeakerRoute) : SpeakerRouteResolution
    data class Rejected(val reason: SpeakerRouteFailure) : SpeakerRouteResolution
}

sealed interface SpeakerCommunicationRoute {
    data class Ready(
        val outputDevice: AudioDeviceInfo,
        val alreadySelected: Boolean,
    ) : SpeakerCommunicationRoute

    data class Rejected(val reason: SpeakerRouteFailure) : SpeakerCommunicationRoute
}

/**
 * Resolves direction from the microphone Android actually routed to AudioRecord.
 *
 * Auracast is output-only. The talker must therefore use a Bluetooth HFP or
 * LE Audio unicast microphone whose stable identity matches the language saved
 * in Settings. Any ambiguity fails closed instead of falling back to a model or
 * a default direction.
 */
class BluetoothSpeakerRouteResolver(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = HeadsetLanguagePreferences(appContext)

    fun resolve(routedInput: AudioDeviceInfo?): SpeakerRouteResolution {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.BLUETOOTH_CONNECT,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return SpeakerRouteResolution.Rejected(
                SpeakerRouteFailure.BLUETOOTH_PERMISSION_REQUIRED,
            )
        }
        val device = routedInput ?: return SpeakerRouteResolution.Rejected(
            SpeakerRouteFailure.NO_ROUTED_INPUT,
        )
        if (!HeadsetIdentity.isSupportedInput(device)) {
            return SpeakerRouteResolution.Rejected(
                SpeakerRouteFailure.INPUT_IS_NOT_BLUETOOTH_HEADSET,
            )
        }
        val speakerId = HeadsetIdentity.stableId(device)
            ?: return SpeakerRouteResolution.Rejected(
                SpeakerRouteFailure.STABLE_IDENTITY_UNAVAILABLE,
            )
        val language = preferences.get(speakerId)
            ?: return SpeakerRouteResolution.Rejected(
                SpeakerRouteFailure.HEADSET_LANGUAGE_UNASSIGNED,
            )
        return SpeakerRouteResolution.Ready(
            VerifiedSpeakerRoute(
                speakerId = speakerId,
                spokenLanguage = language,
                direction = when (language) {
                    HeadsetLanguage.VI -> TranslationDirection.VI_TO_EN
                    HeadsetLanguage.EN -> TranslationDirection.EN_TO_VI
                },
                deviceName = device.productName.toString().trim()
                    .ifEmpty { "Bluetooth headset" },
            ),
        )
    }

    /**
     * Select the sole assigned HFP/LE Audio communication endpoint.
     *
     * Passive Auracast listeners do not appear here. This list is only the
     * unicast microphone uplink used to establish the exclusive speaker floor.
     */
    fun communicationRoute(audioManager: AudioManager): SpeakerCommunicationRoute {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return SpeakerCommunicationRoute.Rejected(
                SpeakerRouteFailure.COMMUNICATION_ROUTE_SELECTION_FAILED,
            )
        }
        if (ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.BLUETOOTH_CONNECT,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return SpeakerCommunicationRoute.Rejected(
                SpeakerRouteFailure.BLUETOOTH_PERMISSION_REQUIRED,
            )
        }
        return try {
            val candidates = audioManager.availableCommunicationDevices.filter { device ->
                isSupportedCommunicationOutput(device) &&
                    HeadsetIdentity.stableId(device)?.let(preferences::get) != null
            }
            val current = audioManager.communicationDevice
            val selected = current?.takeIf { active ->
                candidates.any { it.id == active.id }
            }
            when {
                selected != null -> SpeakerCommunicationRoute.Ready(selected, true)
                candidates.isEmpty() -> SpeakerCommunicationRoute.Rejected(
                    SpeakerRouteFailure.NO_ASSIGNED_COMMUNICATION_HEADSET,
                )
                candidates.size > 1 -> SpeakerCommunicationRoute.Rejected(
                    SpeakerRouteFailure.MULTIPLE_ASSIGNED_COMMUNICATION_HEADSETS,
                )
                else -> SpeakerCommunicationRoute.Ready(candidates.single(), false)
            }
        } catch (_: SecurityException) {
            SpeakerCommunicationRoute.Rejected(
                SpeakerRouteFailure.BLUETOOTH_PERMISSION_REQUIRED,
            )
        }
    }

    private fun isSupportedCommunicationOutput(device: AudioDeviceInfo): Boolean =
        device.isSink && when (device.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            -> true
            else -> false
        }
}
