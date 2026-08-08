package com.onevoice.link.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.onevoice.link.R
import com.onevoice.link.ui.screens.BackendKind
import com.onevoice.link.ui.theme.OverlineMono
import com.onevoice.link.ui.theme.Spacing

/** Selects the complete offline pipeline or QNN context verification. */
@Composable
fun BackendSelector(
    selected: BackendKind,
    enabled: Boolean,
    detail: String,
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
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val entries = BackendKind.entries
            entries.forEachIndexed { index, backend ->
                SegmentedButton(
                    selected = selected == backend,
                    enabled = enabled,
                    onClick = { onSelect(backend) },
                    shape = SegmentedButtonDefaults.itemShape(index, entries.size),
                ) {
                    Text(stringResource(backend.labelRes))
                }
            }
        }
        if (detail.isNotBlank()) {
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
