package com.onevoice.link.headset

import android.media.AudioDeviceInfo
import java.security.MessageDigest
import java.util.Locale

/** Stable identity shared by the Settings inventory and the live microphone route. */
internal object HeadsetIdentity {
    fun stableId(device: AudioDeviceInfo): String? {
        val address = device.address.trim().lowercase(Locale.ROOT)
        if (isRedactedAddress(address)) return null
        return sha256("bluetooth-address:$address")
    }

    fun sessionId(device: AudioDeviceInfo): String =
        sha256("audio-session:${device.id}")

    fun isSupportedInput(device: AudioDeviceInfo): Boolean =
        device.isSource && when (device.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            -> true
            else -> false
        }

    private fun isRedactedAddress(address: String): Boolean =
        address.isBlank() ||
            address == "00:00:00:00:00:00" ||
            address == "02:00:00:00:00:00"

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        val alphabet = "0123456789abcdef"
        return buildString(bytes.size * 2) {
            bytes.forEach { byte ->
                val unsigned = byte.toInt() and 0xff
                append(alphabet[unsigned ushr 4])
                append(alphabet[unsigned and 0x0f])
            }
        }
    }
}
