package com.onevoice.link.headset

import androidx.compose.runtime.Immutable

/**
 * The language a person associated with an audio endpoint prefers to hear.
 *
 * Route-planning v1 also treats this as that person's spoken/source language.
 * If bilingual users need different values, split the persisted contract into
 * spokenLanguage and listeningLanguage before enabling real delivery.
 *
 * This is deliberately not a translation-mode switch. Until a future speaker-floor
 * transport identifies the one active talker, the value is configuration only.
 */
enum class HeadsetLanguage(val persistedValue: String) {
    VI("vi"),
    EN("en"),
    ;

    companion object {
        fun fromPersistedValue(value: String?): HeadsetLanguage? =
            entries.firstOrNull { it.persistedValue == value }
    }
}

/** Android audio transports that can surface a connected Bluetooth listening endpoint. */
enum class HeadsetTransport {
    BLUETOOTH_CALL,
    BLUETOOTH_MEDIA,
    LE_AUDIO,
    HEARING_AID,
}

/**
 * One physical or logical Bluetooth audio endpoint currently exposed by Android.
 *
 * [id] is an irreversible hash of the Bluetooth address whenever Android exposes
 * one. Android's transient AudioDeviceInfo.id and the device name are never used
 * as persistent preference keys.
 */
@Immutable
data class ConnectedHeadset(
    val id: String,
    val displayName: String,
    val transports: Set<HeadsetTransport>,
    val language: HeadsetLanguage?,
    val assignmentPersistent: Boolean,
)

/** Inventory state shown on Settings; no state implies that routing is active. */
sealed interface HeadsetInventoryState {
    data object Loading : HeadsetInventoryState
    data object PermissionRequired : HeadsetInventoryState
    data object BluetoothOff : HeadsetInventoryState
    data object Empty : HeadsetInventoryState

    @Immutable
    data class Ready(val headsets: List<ConnectedHeadset>) : HeadsetInventoryState

    @Immutable
    data class Error(val detail: String) : HeadsetInventoryState
}
