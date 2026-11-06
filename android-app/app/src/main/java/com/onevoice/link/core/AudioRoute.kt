package com.onevoice.link.core

import android.content.Context
import com.onevoice.link.BuildConfig

/**
 * User-facing audio I/O choice. It selects capture/playback devices only; it
 * never selects the translation direction, which stays with the utterance router.
 */
enum class AudioRoutePreference(val persistedValue: String) {
    /** Bluetooth when exactly one communication headset is usable, else the phone. */
    AUTO("auto"),

    /**
     * Built-in microphone and loudspeaker. Lets two people share one handset
     * and lets developers/judges run the full pipeline without a headset.
     */
    PHONE("phone"),

    /** HFP/LE Audio microphone plus A2DP media; fails closed without a headset. */
    BLUETOOTH("bluetooth"),
    ;

    companion object {
        fun fromPersistedValue(value: String?): AudioRoutePreference =
            entries.firstOrNull { it.persistedValue == value } ?: AUTO
    }
}

/**
 * Build-time input mode (`-PaudioInput`): which microphone records.
 *
 * PHONE (default) uses the built-in mic, so nothing has to be connected. USB
 * uses only a plugged-in USB-C mic (for example a wireless lavalier receiver)
 * and fails closed without one. Neither ever records from Bluetooth, so a
 * headset that reconnects, or a stray tap, cannot take over the input; both
 * still play to a connected A2DP headset, else on the loudspeaker. DASHBOARD
 * keeps the older [AudioRoutePreference] selector, Bluetooth mic included.
 * Like the route, the mode picks devices, never the translation direction.
 */
enum class AudioInputMode {
    PHONE,
    USB,
    DASHBOARD,
    ;

    companion object {
        fun parse(value: String): AudioInputMode =
            entries.firstOrNull { it.name == value }
                ?: throw IllegalArgumentException(
                    "audioInput must be PHONE, USB or DASHBOARD (got '$value')",
                )

        fun fromBuildConfig(): AudioInputMode = parse(BuildConfig.AUDIO_INPUT)
    }
}

/** Physical route that one bounded turn actually used. */
enum class AudioRouteKind {
    PHONE,
    BLUETOOTH,
}

/** Route decision kept free of AudioDeviceInfo so the policy is unit-testable. */
internal sealed interface RouteChoice<out T> {
    data object Phone : RouteChoice<Nothing>
    data class Bluetooth<T>(val device: T) : RouteChoice<T>
    data class Unavailable(val reason: String) : RouteChoice<Nothing>
}

/**
 * [currentBluetooth] is Android's already-selected communication device when it
 * is one of [bluetoothCandidates]. AUTO never guesses between several headsets:
 * an ambiguous Bluetooth set falls back to the phone, whereas an explicit
 * BLUETOOTH preference fails closed so a developer sees the misconfiguration.
 *
 * [inputMode] PHONE and USB override [preference] and never record from
 * Bluetooth. USB without an attached USB mic fails closed rather than fall
 * back to the built-in mic: in a build set to a dedicated mic, a silently
 * different microphone is a wrong demo, not a degraded one.
 */
internal fun <T> chooseAudioRoute(
    preference: AudioRoutePreference,
    bluetoothCandidates: List<T>,
    currentBluetooth: T?,
    inputMode: AudioInputMode = AudioInputMode.DASHBOARD,
    usbMicrophoneAttached: Boolean = false,
): RouteChoice<T> {
    when (inputMode) {
        AudioInputMode.PHONE -> return RouteChoice.Phone
        AudioInputMode.USB -> return if (usbMicrophoneAttached) {
            RouteChoice.Phone
        } else {
            RouteChoice.Unavailable(
                "This build records only from a USB-C microphone (audioInput=USB) and none " +
                    "is attached. Plug in the receiver, or build with -PaudioInput=PHONE.",
            )
        }
        AudioInputMode.DASHBOARD -> Unit
    }
    val bluetooth = currentBluetooth ?: bluetoothCandidates.singleOrNull()
    return when (preference) {
        AudioRoutePreference.PHONE -> RouteChoice.Phone
        AudioRoutePreference.AUTO ->
            if (bluetooth != null) RouteChoice.Bluetooth(bluetooth) else RouteChoice.Phone
        AudioRoutePreference.BLUETOOTH -> when {
            bluetooth != null -> RouteChoice.Bluetooth(bluetooth)
            bluetoothCandidates.isEmpty() -> RouteChoice.Unavailable(
                "No Bluetooth HFP/LE Audio communication headset is available. " +
                    "Connect a headset or switch the audio route to Phone/Auto.",
            )
            else -> RouteChoice.Unavailable(
                "Multiple Bluetooth communication headsets are available and Android " +
                    "has not selected one. Select one in system audio or use Phone/Auto.",
            )
        }
    }
}

/** Persists the audio route across launches; absent or unknown values mean AUTO. */
internal class AudioRoutePreferences(context: Context) {
    private val preferences =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun get(): AudioRoutePreference =
        AudioRoutePreference.fromPersistedValue(preferences.getString(KEY, null))

    fun set(value: AudioRoutePreference) {
        preferences.edit().putString(KEY, value.persistedValue).apply()
    }

    private companion object {
        const val FILE_NAME = "audio_route_v1"
        const val KEY = "preference"
    }
}

/**
 * Mono target waveform -> identical left/right frames. A phone loudspeaker is
 * shared by both people, so the per-bud channel split would only lose energy.
 */
internal fun duplicateMonoStereo(mono: FloatArray): FloatArray =
    FloatArray(mono.size * 2).also { stereo ->
        mono.forEachIndexed { frame, sample ->
            stereo[frame * 2] = sample
            stereo[frame * 2 + 1] = sample
        }
    }
