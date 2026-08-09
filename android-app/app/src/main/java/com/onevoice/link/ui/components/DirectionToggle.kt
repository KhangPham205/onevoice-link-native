package com.onevoice.link.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.onevoice.link.R
import com.onevoice.link.core.TranslationDirection
import com.onevoice.link.ui.theme.OverlineMono
import com.onevoice.link.ui.theme.Spacing

/**
 * Conversation direction switch (VI→EN / EN→VI).
 *
 * A direction can be individually unavailable for the selected backend. Rather
 * than hiding a missing QNN deliverable, the option stays visible, disabled,
 * with the reason spelled out underneath. The offline backend supplies both.
 */
@Composable
fun DirectionToggle(
    direction: TranslationDirection,
    enabled: Boolean,
    onSelect: (TranslationDirection) -> Unit,
    modifier: Modifier = Modifier,
    unavailable: Set<TranslationDirection> = emptySet(),
    unavailableReason: String? = null,
) {
    Column(modifier) {
        Text(
            text = stringResource(R.string.direction_overline),
            style = OverlineMono,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.sm))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val entries = TranslationDirection.entries
            entries.forEachIndexed { index, dir ->
                SegmentedButton(
                    selected = direction == dir,
                    enabled = enabled && dir !in unavailable,
                    onClick = { onSelect(dir) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = entries.size),
                ) {
                    Text(
                        stringResource(
                            when (dir) {
                                TranslationDirection.VI_TO_EN -> R.string.direction_vi_en
                                TranslationDirection.EN_TO_VI -> R.string.direction_en_vi
                            },
                        ),
                    )
                }
            }
        }
        if (unavailableReason != null) {
            Spacer(Modifier.height(Spacing.sm))
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    painter = painterResource(R.drawable.ic_warning_24),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    // The text right next to it already carries the meaning.
                    modifier = Modifier
                        .size(16.dp)
                        .clearAndSetSemantics {},
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = unavailableReason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
