package com.onevoice.link.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.onevoice.link.R
import com.onevoice.link.core.TranslationDirection
import com.onevoice.link.ui.theme.OneVoiceMotion
import com.onevoice.link.ui.theme.OverlineMono
import com.onevoice.link.ui.theme.Spacing

/**
 * Live transcript + translation from the HF cascade (ASR → MT), with a replay
 * button that re-speaks the last synthesized translation. Language labels
 * follow the active [TranslationDirection].
 */
@Composable
fun TranslationCard(
    transcript: String?,
    translation: String?,
    direction: TranslationDirection,
    onReplay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sourceLabel = stringResource(
        if (direction == TranslationDirection.VI_TO_EN) R.string.lang_vi else R.string.lang_en,
    )
    val targetLabel = stringResource(
        if (direction == TranslationDirection.VI_TO_EN) R.string.lang_en else R.string.lang_vi,
    )
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier
                .padding(horizontal = Spacing.lg, vertical = Spacing.md)
                .animateContentSize(
                    tween(OneVoiceMotion.DurationMedium, easing = OneVoiceMotion.StandardEasing),
                ),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.translation_card_overline),
                    style = OverlineMono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onReplay,
                    enabled = !translation.isNullOrBlank(),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_volume_up_24),
                        contentDescription = stringResource(R.string.a11y_replay),
                        tint = if (!translation.isNullOrBlank()) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                        },
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Spacer(Modifier.height(Spacing.xs))
            LanguageLine(
                label = sourceLabel,
                text = transcript ?: stringResource(R.string.translation_waiting),
                emphasized = false,
            )
            Spacer(Modifier.height(Spacing.sm))
            LanguageLine(
                label = targetLabel,
                text = translation.orEmpty(),
                emphasized = true,
            )
        }
    }
}

@Composable
private fun LanguageLine(label: String, text: String, emphasized: Boolean) {
    Row {
        Text(
            text = label,
            style = OverlineMono,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(28.dp),
        )
        Text(
            text = text,
            style = if (emphasized) MaterialTheme.typography.titleMedium
                    else MaterialTheme.typography.bodyMedium,
            color = if (emphasized) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
