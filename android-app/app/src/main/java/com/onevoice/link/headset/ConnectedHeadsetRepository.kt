package com.onevoice.link.headset

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.security.MessageDigest
import java.util.Locale

/**
 * Inventories Bluetooth audio endpoints that Android currently exposes.
 *
 * This is not an Auracast receiver scanner: passive broadcast listeners are
 * connectionless and are not enumerable as individual devices by this API.
 */
class ConnectedHeadsetRepository(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager = requireNotNull(
        appContext.getSystemService(AudioManager::class.java),
    ) { "Android AudioManager is unavailable" }
    private val bluetoothAdapter =
        appContext.getSystemService(BluetoothManager::class.java)?.adapter
    private val preferences = HeadsetLanguagePreferences(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessionAssignments = mutableMapOf<String, HeadsetLanguage?>()

    private var listener: ((HeadsetInventoryState) -> Unit)? = null
    private var started = false
    private var audioCallbackRegistered = false
    private var stateReceiverRegistered = false
    private var currentGroups = emptyMap<String, DeviceGroup>()

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
    }

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) refresh()
        }
    }

    /** Start callbacks while Settings is visible. Safe to call repeatedly. */
    fun start(
        connectPermissionGranted: Boolean,
        listener: (HeadsetInventoryState) -> Unit,
    ) {
        stop()
        this.listener = listener
        started = true
        publish(HeadsetInventoryState.Loading)

        if (requiresConnectPermission() && !connectPermissionGranted) {
            publish(HeadsetInventoryState.PermissionRequired)
            return
        }

        try {
            registerBluetoothStateReceiver()
            audioManager.registerAudioDeviceCallback(audioDeviceCallback, mainHandler)
            audioCallbackRegistered = true
            refresh()
        } catch (_: SecurityException) {
            publish(HeadsetInventoryState.PermissionRequired)
        } catch (failure: RuntimeException) {
            publish(
                HeadsetInventoryState.Error(
                    failure.message ?: failure.javaClass.simpleName,
                ),
            )
        }
    }

    /** Release callbacks without discarding persisted or same-process assignments. */
    fun stop() {
        if (audioCallbackRegistered) {
            runCatching { audioManager.unregisterAudioDeviceCallback(audioDeviceCallback) }
            audioCallbackRegistered = false
        }
        if (stateReceiverRegistered) {
            runCatching { appContext.unregisterReceiver(bluetoothStateReceiver) }
            stateReceiverRegistered = false
        }
        started = false
        listener = null
        currentGroups = emptyMap()
    }

    /** Re-read the endpoint inventory, for example after Bluetooth is enabled. */
    @Suppress("DEPRECATION")
    fun refresh() {
        if (!started) return
        if (!hasConnectPermission()) {
            publish(HeadsetInventoryState.PermissionRequired)
            return
        }

        try {
            val adapter = bluetoothAdapter
            if (adapter == null) {
                currentGroups = emptyMap()
                publish(
                    HeadsetInventoryState.Error(
                        appContext.getString(com.onevoice.link.R.string.headset_bluetooth_unsupported),
                    ),
                )
                return
            }
            if (!adapter.isEnabled) {
                currentGroups = emptyMap()
                publish(HeadsetInventoryState.BluetoothOff)
                return
            }

            val devices = audioManager
                .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .asSequence()
                .filter(AudioDeviceInfo::isSink)
                .mapNotNull(::toDiscoveredDevice)
                .toList()
            val groups = devices
                .groupBy(DiscoveredDevice::identityHash)
                .mapValues { (_, matches) -> merge(matches) }
            currentGroups = groups
            val headsets = groups.values
                .map(::toConnectedHeadset)
                .sortedBy { it.displayName.lowercase(Locale.ROOT) }
            publish(
                if (headsets.isEmpty()) HeadsetInventoryState.Empty
                else HeadsetInventoryState.Ready(headsets),
            )
        } catch (_: SecurityException) {
            publish(HeadsetInventoryState.PermissionRequired)
        } catch (failure: RuntimeException) {
            publish(
                HeadsetInventoryState.Error(
                    failure.message ?: failure.javaClass.simpleName,
                ),
            )
        }
    }

    /** Persist a stable assignment, or keep it in memory when no stable address exists. */
    fun setLanguage(headsetId: String, language: HeadsetLanguage?) {
        val group = currentGroups[headsetId] ?: return
        if (group.assignmentPersistent) {
            preferences.set(group.identityHash, language)
        } else {
            if (language == null) sessionAssignments.remove(group.identityHash)
            else sessionAssignments[group.identityHash] = language
        }
        refresh()
    }

    private fun toDiscoveredDevice(device: AudioDeviceInfo): DiscoveredDevice? {
        val transport = transportFor(device.type) ?: return null
        val rawAddress = device.address.trim().lowercase(Locale.ROOT)
            .takeUnless(::isRedactedAddress)
        val assignmentPersistent = rawAddress != null
        val identityMaterial = if (rawAddress != null) {
            "bluetooth-address:$rawAddress"
        } else {
            // Session-only fallback: never persist by transient Android device id.
            "audio-session:${device.id}"
        }
        val name = device.productName.toString().trim()
            .ifEmpty { appContext.getString(com.onevoice.link.R.string.headset_unknown_name) }
        return DiscoveredDevice(
            identityHash = sha256(identityMaterial),
            displayName = name,
            transport = transport,
            assignmentPersistent = assignmentPersistent,
        )
    }

    private fun merge(devices: List<DiscoveredDevice>): DeviceGroup {
        val first = devices.first()
        return DeviceGroup(
            identityHash = first.identityHash,
            displayName = devices.maxByOrNull { it.displayName.length }?.displayName
                ?: first.displayName,
            transports = devices.mapTo(linkedSetOf(), DiscoveredDevice::transport),
            assignmentPersistent = devices.all(DiscoveredDevice::assignmentPersistent),
        )
    }

    private fun toConnectedHeadset(group: DeviceGroup): ConnectedHeadset {
        val language = if (group.assignmentPersistent) {
            preferences.get(group.identityHash)
        } else {
            sessionAssignments[group.identityHash]
        }
        return ConnectedHeadset(
            id = group.identityHash,
            displayName = group.displayName,
            transports = group.transports,
            language = language,
            assignmentPersistent = group.assignmentPersistent,
        )
    }

    private fun registerBluetoothStateReceiver() {
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(
                bluetoothStateReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED,
            )
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(bluetoothStateReceiver, filter)
        }
        stateReceiverRegistered = true
    }

    private fun hasConnectPermission(): Boolean =
        !requiresConnectPermission() ||
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED

    private fun publish(state: HeadsetInventoryState) {
        if (started) listener?.invoke(state)
    }

    private data class DiscoveredDevice(
        val identityHash: String,
        val displayName: String,
        val transport: HeadsetTransport,
        val assignmentPersistent: Boolean,
    )

    private data class DeviceGroup(
        val identityHash: String,
        val displayName: String,
        val transports: Set<HeadsetTransport>,
        val assignmentPersistent: Boolean,
    )

    private companion object {
        fun requiresConnectPermission(): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

        fun transportFor(type: Int): HeadsetTransport? = when (type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> HeadsetTransport.BLUETOOTH_CALL
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> HeadsetTransport.BLUETOOTH_MEDIA
            AudioDeviceInfo.TYPE_HEARING_AID -> HeadsetTransport.HEARING_AID
            AudioDeviceInfo.TYPE_BLE_HEADSET -> HeadsetTransport.LE_AUDIO
            else -> null
        }

        fun isRedactedAddress(address: String): Boolean =
            address.isBlank() ||
                address == "00:00:00:00:00:00" ||
                address == "02:00:00:00:00:00"

        fun sha256(value: String): String {
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
}
