package com.onevoice.link.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.onevoice.link.R
import com.onevoice.link.core.AudioInputMode
import com.onevoice.link.core.AudioRoutePreference
import com.onevoice.link.ui.theme.Spacing

/**
 * Audio I/O selector. It picks microphone/speaker devices only; the spoken
 * language of each turn is still decided by the on-device router. A build
 * with a fixed [inputMode] (`audioInput=PHONE` or `USB`) shows its microphone
 * as text instead: there is nothing to tap, so a stray touch cannot move the
 * input to a Bluetooth headset.
 */
@Composable
fun AudioRouteSelector(
    selected: AudioRoutePreference,
    activeRouteLabel: String?,
    enabled: Boolean,
    onSelected: (AudioRoutePreference) -> Unit,
    modifier: Modifier = Modifier,
    inputMode: AudioInputMode = AudioInputMode.DASHBOARD,
) {
    val options = AudioRoutePreference.entries
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md)) {
            Text(
                text = stringResource(R.string.audio_route_title),
                style = MaterialTheme.typography.titleSmall,
            )
            if (inputMode == AudioInputMode.DASHBOARD) {
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.sm),
                ) {
                    options.forEachIndexed { index, option ->
                        SegmentedButton(
                            selected = option == selected,
                            onClick = { onSelected(option) },
                            enabled = enabled,
                            shape = SegmentedButtonDefaults.itemShape(index, options.size),
                        ) {
                            Text(stringResource(option.labelRes()))
                        }
                    }
                }
            } else {
                Text(
                    text = stringResource(inputMode.fixedLabelRes()),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = Spacing.sm),
                )
            }
            Text(
                text = stringResource(
                    if (inputMode == AudioInputMode.DASHBOARD) selected.hintRes() else inputMode.fixedHintRes(),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.sm),
            )
            if (activeRouteLabel != null) {
                Text(
                    text = stringResource(R.string.audio_route_active, activeRouteLabel),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = Spacing.xs),
                )
            }
        }
    }
}

private fun AudioRoutePreference.labelRes(): Int = when (this) {
    AudioRoutePreference.AUTO -> R.string.audio_route_auto
    AudioRoutePreference.PHONE -> R.string.audio_route_phone
    AudioRoutePreference.BLUETOOTH -> R.string.audio_route_bluetooth
}

private fun AudioInputMode.fixedLabelRes(): Int =
    if (this == AudioInputMode.USB) R.string.audio_route_fixed_usb else R.string.audio_route_fixed_phone

private fun AudioInputMode.fixedHintRes(): Int =
    if (this == AudioInputMode.USB) R.string.audio_route_fixed_usb_hint else R.string.audio_route_fixed_phone_hint

private fun AudioRoutePreference.hintRes(): Int = when (this) {
    AudioRoutePreference.AUTO -> R.string.audio_route_auto_hint
    AudioRoutePreference.PHONE -> R.string.audio_route_phone_hint
    AudioRoutePreference.BLUETOOTH -> R.string.audio_route_bluetooth_hint
}
