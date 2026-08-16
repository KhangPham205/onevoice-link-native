package com.onevoice.link.ui.screens

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.onevoice.link.headset.ConnectedHeadset
import com.onevoice.link.headset.HeadsetInventoryState
import com.onevoice.link.headset.HeadsetLanguage
import com.onevoice.link.headset.HeadsetTransport
import com.onevoice.link.ui.theme.OneVoiceLinkTheme

private val SETTINGS_HEADSETS = listOf(
    ConnectedHeadset(
        id = "preview-one",
        displayName = "Production headset A",
        transports = setOf(
            HeadsetTransport.BLUETOOTH_CALL,
            HeadsetTransport.BLUETOOTH_MEDIA,
        ),
        language = HeadsetLanguage.VI,
        assignmentPersistent = true,
    ),
    ConnectedHeadset(
        id = "preview-two",
        displayName = "LE Audio headset B",
        transports = setOf(HeadsetTransport.LE_AUDIO),
        language = HeadsetLanguage.EN,
        assignmentPersistent = true,
    ),
    ConnectedHeadset(
        id = "preview-three",
        displayName = "Temporary audio endpoint",
        transports = setOf(HeadsetTransport.HEARING_AID),
        language = null,
        assignmentPersistent = false,
    ),
)

@Preview(name = "Connected headsets", showBackground = true)
@Preview(
    name = "Connected headsets dark",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ConnectedHeadsetsPreview() {
    OneVoiceLinkTheme(dynamicColor = false) {
        SettingsScreen(
            state = HeadsetInventoryState.Ready(SETTINGS_HEADSETS),
            onBack = {},
            onRequestBluetoothConnect = {},
            onRefresh = {},
            onLanguageChanged = { _, _ -> },
        )
    }
}

@Preview(name = "Permission required", showBackground = true)
@Composable
private fun HeadsetPermissionPreview() {
    OneVoiceLinkTheme(dynamicColor = false) {
        SettingsScreen(
            state = HeadsetInventoryState.PermissionRequired,
            onBack = {},
            onRequestBluetoothConnect = {},
            onRefresh = {},
            onLanguageChanged = { _, _ -> },
        )
    }
}

@Preview(name = "Large font", showBackground = true, fontScale = 1.5f)
@Composable
private fun HeadsetLargeFontPreview() {
    OneVoiceLinkTheme(dynamicColor = false) {
        SettingsScreen(
            state = HeadsetInventoryState.Ready(SETTINGS_HEADSETS.take(1)),
            onBack = {},
            onRequestBluetoothConnect = {},
            onRefresh = {},
            onLanguageChanged = { _, _ -> },
        )
    }
}
