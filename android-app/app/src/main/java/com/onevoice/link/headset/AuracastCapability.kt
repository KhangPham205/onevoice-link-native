package com.onevoice.link.headset

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.Immutable
import androidx.core.content.ContextCompat

@Immutable
data class AuracastCapability(
    val sourceSupported: Boolean,
    val assistantSupported: Boolean,
    val permissionGranted: Boolean,
)

/** Public capability probe only; broadcast control remains a system/OEM API. */
object AuracastCapabilityProbe {
    fun read(context: Context): AuracastCapability {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED
        if (!granted || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return AuracastCapability(false, false, granted)
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return AuracastCapability(false, false, true)
        return runCatching {
            AuracastCapability(
                sourceSupported = adapter.isLeAudioBroadcastSourceSupported ==
                    BluetoothStatusCodes.FEATURE_SUPPORTED,
                assistantSupported = adapter.isLeAudioBroadcastAssistantSupported ==
                    BluetoothStatusCodes.FEATURE_SUPPORTED,
                permissionGranted = true,
            )
        }.getOrDefault(AuracastCapability(false, false, true))
    }
}
