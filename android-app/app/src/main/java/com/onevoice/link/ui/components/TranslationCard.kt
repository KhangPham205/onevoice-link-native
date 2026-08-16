package com.onevoice.link.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.onevoice.link.R
import com.onevoice.link.core.ExecutionPath
import com.onevoice.link.core.TranslationDirection
import com.onevoice.link.ui.theme.OverlineMono
import com.onevoice.link.ui.theme.Spacing
import kotlin.math.roundToInt

/** Latest auto-routed utterance, its translation, and the route actually used. */
@Composable
fun TranslationCard(
    transcript: String?,
    translation: String?,
    detectedDirection: TranslationDirection?,
    lidConfidence: Float?,
    executionPath: ExecutionPath?,
    canReplay: Boolean,
    onReplay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sourceLabel = stringResource(
        when (detectedDirection) {
            TranslationDirection.VI_TO_EN -> R.string.lang_vi
            TranslationDirection.EN_TO_VI -> R.string.lang_en
            null -> R.string.lang_source
        },
    )
    val targetLabel = stringResource(
        when (detectedDirection) {
            TranslationDirection.VI_TO_EN -> R.string.lang_en
            TranslationDirection.EN_TO_VI -> R.string.lang_vi
            null -> R.string.lang_translation
        },
    )
    val detection = when (detectedDirection) {
        TranslationDirection.VI_TO_EN -> stringResource(
            R.string.language_detected_vi_en,
            ((lidConfidence ?: 0f).coerceIn(0f, 1f) * 100f).roundToInt(),
        )
        TranslationDirection.EN_TO_VI -> stringResource(
            R.string.language_detected_en_vi,
            ((lidConfidence ?: 0f).coerceIn(0f, 1f) * 100f).roundToInt(),
        )
        null -> stringResource(R.string.language_auto_detecting)
    }
    val path = executionPath?.let {
        stringResource(
            when (it) {
                ExecutionPath.HYBRID_NPU -> R.string.execution_path_hybrid_npu
                ExecutionPath.CPU_FALLBACK -> R.string.execution_path_cpu_fallback
            },
        )
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(Spacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.translation_card_overline),
                    style = OverlineMono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onReplay,
                    enabled = canReplay,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_volume_up_24),
                        contentDescription = stringResource(R.string.a11y_replay),
                    )
                }
            }
            Text(
                text = detection,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics {
                    liveRegion = LiveRegionMode.Polite
                },
            )
            if (path != null) {
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(Spacing.md))
            TranslationLine(
                language = sourceLabel,
                text = transcript?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.translation_waiting_source),
                emphasized = false,
            )
            Spacer(Modifier.height(Spacing.md))
            TranslationLine(
                language = targetLabel,
                text = translation?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.translation_waiting_target),
                emphasized = true,
            )
        }
    }
}

@Composable
private fun TranslationLine(
    language: String,
    text: String,
    emphasized: Boolean,
) {
    Column(
        Modifier.semantics {
            liveRegion = LiveRegionMode.Polite
        },
    ) {
        Text(
            text = language,
            style = OverlineMono,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = text,
            style = if (emphasized) {
                MaterialTheme.typography.titleMedium
            } else {
                MaterialTheme.typography.bodyLarge
            },
            color = if (text.isBlank()) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}
