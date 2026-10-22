package com.onevoice.link.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.onevoice.link.R
import com.onevoice.link.headset.AuracastCapability
import com.onevoice.link.headset.ConnectedHeadset
import com.onevoice.link.headset.HeadsetInventoryState
import com.onevoice.link.headset.HeadsetLanguage
import com.onevoice.link.headset.HeadsetTransport
import com.onevoice.link.ui.theme.Spacing

/** Settings is configuration-only; it never claims or mutates an audio route. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: HeadsetInventoryState,
    onBack: () -> Unit,
    onRequestBluetoothConnect: () -> Unit,
    onRefresh: () -> Unit,
    onLanguageChanged: (String, HeadsetLanguage?) -> Unit,
    auracastCapability: AuracastCapability,
    onOpenBluetoothSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.settings_title),
                        modifier = Modifier.semantics { heading() },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back_24),
                            contentDescription = stringResource(R.string.a11y_back_to_dashboard),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        SettingsContent(
            state = state,
            onRequestBluetoothConnect = onRequestBluetoothConnect,
            onRefresh = onRefresh,
            onLanguageChanged = onLanguageChanged,
            auracastCapability = auracastCapability,
            onOpenBluetoothSettings = onOpenBluetoothSettings,
            modifier = Modifier.padding(innerPadding),
        )
    }
}

@Composable
fun SettingsContent(
    state: HeadsetInventoryState,
    onRequestBluetoothConnect: () -> Unit,
    onRefresh: () -> Unit,
    onLanguageChanged: (String, HeadsetLanguage?) -> Unit,
    auracastCapability: AuracastCapability,
    onOpenBluetoothSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.xl),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            top = Spacing.md,
            bottom = Spacing.xxl,
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        item {
            Text(
                text = stringResource(R.string.settings_headset_intro),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            AuracastBoundaryCard(
                capability = auracastCapability,
                onOpenBluetoothSettings = onOpenBluetoothSettings,
            )
        }

        when (state) {
            HeadsetInventoryState.Loading -> item {
                StatusPanel(message = stringResource(R.string.headset_loading), busy = true)
            }

            HeadsetInventoryState.PermissionRequired -> item {
                StatusPanel(
                    message = stringResource(R.string.headset_permission_required),
                    actionLabel = stringResource(R.string.action_grant_nearby_devices),
                    onAction = onRequestBluetoothConnect,
                )
            }

            HeadsetInventoryState.BluetoothOff -> item {
                StatusPanel(
                    message = stringResource(R.string.headset_bluetooth_off),
                    actionLabel = stringResource(R.string.action_refresh_headsets),
                    onAction = onRefresh,
                )
            }

            HeadsetInventoryState.Empty -> item {
                StatusPanel(
                    message = stringResource(R.string.headset_none_connected),
                    actionLabel = stringResource(R.string.action_refresh_headsets),
                    onAction = onRefresh,
                )
            }

            is HeadsetInventoryState.Error -> item {
                StatusPanel(
                    message = stringResource(R.string.headset_inventory_error, state.detail),
                    actionLabel = stringResource(R.string.action_refresh_headsets),
                    onAction = onRefresh,
                )
            }

            is HeadsetInventoryState.Ready -> {
                item {
                    Text(
                        text = stringResource(
                            R.string.headset_connected_count,
                            state.headsets.size,
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics {
                            heading()
                            liveRegion = LiveRegionMode.Polite
                        },
                    )
                }
                items(state.headsets, key = ConnectedHeadset::id) { headset ->
                    HeadsetAssignmentCard(
                        headset = headset,
                        onLanguageChanged = { language ->
                            onLanguageChanged(headset.id, language)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AuracastBoundaryCard(
    capability: AuracastCapability,
    onOpenBluetoothSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(
                text = stringResource(R.string.auracast_configuration_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.auracast_configuration_only),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(
                    when {
                        !capability.permissionGranted ->
                            R.string.auracast_capability_permission_required
                        capability.sourceSupported && capability.assistantSupported ->
                            R.string.auracast_capability_source_and_assistant
                        capability.sourceSupported -> R.string.auracast_capability_source
                        else -> R.string.auracast_capability_unavailable
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onOpenBluetoothSettings) {
                Text(stringResource(R.string.action_open_bluetooth_settings))
            }
        }
    }
}

@Composable
private fun StatusPanel(
    message: String,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.xl)
                .semantics { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 3.dp,
                )
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (actionLabel != null) {
                Button(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

@Composable
private fun HeadsetAssignmentCard(
    headset: ConnectedHeadset,
    onLanguageChanged: (HeadsetLanguage?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val transportLabels = mutableListOf<String>()
    for (transport in headset.transports) {
        transportLabels += stringResource(transport.labelRes())
    }
    val transportText = transportLabels.joinToString(separator = " · ")
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(
                text = headset.displayName,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = transportText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.headset_preferred_receive_language),
                style = MaterialTheme.typography.labelLarge,
            )
            Column(Modifier.selectableGroup()) {
                LanguageChoice(
                    label = stringResource(R.string.headset_language_unassigned),
                    selected = headset.language == null,
                    onClick = { onLanguageChanged(null) },
                )
                LanguageChoice(
                    label = stringResource(R.string.headset_language_vi),
                    selected = headset.language == HeadsetLanguage.VI,
                    onClick = { onLanguageChanged(HeadsetLanguage.VI) },
                )
                LanguageChoice(
                    label = stringResource(R.string.headset_language_en),
                    selected = headset.language == HeadsetLanguage.EN,
                    onClick = { onLanguageChanged(HeadsetLanguage.EN) },
                )
            }
            if (!headset.assignmentPersistent) {
                Text(
                    text = stringResource(R.string.headset_assignment_session_only),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun LanguageChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(Spacing.sm))
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}

@StringRes
private fun HeadsetTransport.labelRes(): Int = when (this) {
    HeadsetTransport.BLUETOOTH_CALL -> R.string.headset_transport_bluetooth_call
    HeadsetTransport.BLUETOOTH_MEDIA -> R.string.headset_transport_bluetooth_media
    HeadsetTransport.LE_AUDIO -> R.string.headset_transport_le_audio
    HeadsetTransport.HEARING_AID -> R.string.headset_transport_hearing_aid
}
