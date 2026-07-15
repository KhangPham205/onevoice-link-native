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
import com.onevoice.link.core.TranslationDirection
import com.onevoice.link.ui.theme.OverlineMono
import com.onevoice.link.ui.theme.Spacing

/**
 * Conversation direction switch (VI→EN / EN→VI). Hot-switchable while the
 * engine is RUNNING — each speaker flips it before their turn.
 */
@Composable
fun DirectionToggle(
    direction: TranslationDirection,
    enabled: Boolean,
    onSelect: (TranslationDirection) -> Unit,
    modifier: Modifier = Modifier,
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
                    enabled = enabled,
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
    }
}
