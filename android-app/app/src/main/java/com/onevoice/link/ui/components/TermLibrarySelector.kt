package com.onevoice.link.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.onevoice.link.R
import com.onevoice.link.ui.screens.TermLibraryOption
import com.onevoice.link.ui.theme.Spacing

/**
 * Workplace term library for the Vietnamese ASR ("Xưởng may", later other
 * workshops). The list comes from the libraries staged on the phone, so a new
 * domain appears here without an app change. "Off" is plain recognition.
 * The choice biases recognition only; the router still picks the language.
 */
@Composable
fun TermLibrarySelector(
    libraries: List<TermLibraryOption>,
    selectedId: String?,
    enabled: Boolean,
    onSelected: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md)) {
            Text(
                text = stringResource(R.string.term_library_title),
                style = MaterialTheme.typography.titleSmall,
            )
            if (libraries.isEmpty()) {
                Text(
                    text = stringResource(R.string.term_library_none_staged),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.sm),
                )
                return@Column
            }
            Column(Modifier.padding(top = Spacing.xs).selectableGroup()) {
                libraries.forEach { library ->
                    LibraryRow(
                        title = library.name,
                        subtitle = if (library.terms > 0) {
                            stringResource(R.string.term_library_terms, library.terms)
                        } else {
                            null
                        },
                        selected = library.id == selectedId,
                        enabled = enabled,
                        onClick = { onSelected(library.id) },
                    )
                }
                LibraryRow(
                    title = stringResource(R.string.term_library_off),
                    subtitle = stringResource(R.string.term_library_off_hint),
                    selected = selectedId == null,
                    enabled = enabled,
                    onClick = { onSelected(null) },
                )
            }
            Text(
                text = stringResource(R.string.term_library_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
    }
}

@Composable
private fun LibraryRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = Spacing.xs),
    ) {
        // The row handles the click, so the radio itself is decorative.
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.padding(start = Spacing.sm)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
