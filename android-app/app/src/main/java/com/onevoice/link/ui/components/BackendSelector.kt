package com.onevoice.link.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.onevoice.link.R
import com.onevoice.link.ui.screens.BackendKind
import com.onevoice.link.ui.theme.OverlineMono
import com.onevoice.link.ui.theme.Spacing

/**
 * Engine backend picker: native QNN (.bin on the NPU) vs the HuggingFace CPU
 * cascade used on the emulator. Only switchable while nothing is initialized.
 */
@Composable
fun BackendSelector(
    selected: BackendKind,
    enabled: Boolean,
    onSelect: (BackendKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(
            text = stringResource(R.string.backend_selector_overline),
            style = OverlineMono,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.sm))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            BackendKind.entries.forEach { kind ->
                FilterChip(
                    selected = selected == kind,
                    enabled = enabled || selected == kind,
                    onClick = { onSelect(kind) },
                    label = { Text(stringResource(kind.labelRes)) },
                )
            }
        }
    }
}
