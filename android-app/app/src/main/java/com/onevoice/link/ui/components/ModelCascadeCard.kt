package com.onevoice.link.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.onevoice.link.R
import com.onevoice.link.core.StageState
import com.onevoice.link.core.StageStatus
import com.onevoice.link.ui.theme.OneVoiceTheme
import com.onevoice.link.ui.theme.OverlineMono
import com.onevoice.link.ui.theme.Spacing

/**
 * Per-stage view of the QNN cascade: one row per context binary, showing whether
 * it parsed, placed onto the HTP and resolved its graph — plus how long that
 * took. This is the payload of the load-and-verify build, so it is the card the
 * screen is built around.
 */
@Composable
fun ModelCascadeCard(
    stages: List<StageStatus>,
    targetSoc: String,
    hexagon: String,
    modelRootPath: String,
    modifier: Modifier = Modifier,
) {
    if (stages.isEmpty()) return
    val loaded = stages.count { it.state == StageState.LOADED }
    val anyMissing = stages.any { it.state == StageState.MISSING }
    val totalBytes = stages.sumOf { it.spec.sizeBytes }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(Spacing.lg)) {
            Text(
                text = stringResource(R.string.cascade_overline),
                style = OverlineMono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = stringResource(R.string.cascade_summary, loaded, stages.size,
                    formatBytes(totalBytes)),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.cascade_target, targetSoc, hexagon),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(Spacing.md))
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                stages.forEach { StageRow(it) }
            }

            if (anyMissing) {
                Spacer(Modifier.height(Spacing.md))
                Text(
                    text = stringResource(R.string.cascade_missing_hint, modelRootPath),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun StageRow(status: StageStatus) {
    val colors = OneVoiceTheme.colors
    val tint: Color = when (status.state) {
        StageState.LOADED -> colors.live.indicator
        StageState.LOADING -> colors.standby.indicator
        StageState.MISSING, StageState.FAILED -> MaterialTheme.colorScheme.error
        StageState.PENDING -> colors.neutral.indicator
    }
    val detail = when (status.state) {
        StageState.PENDING -> stringResource(R.string.stage_state_pending)
        StageState.LOADING -> stringResource(R.string.stage_state_loading)
        StageState.LOADED -> stringResource(
            R.string.stage_state_loaded,
            formatBytes(status.spec.sizeBytes),
            status.loadMs,
        )
        StageState.MISSING -> stringResource(R.string.stage_state_missing)
        StageState.FAILED -> status.error ?: stringResource(R.string.stage_state_failed)
    }

    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "${status.spec.label}, $detail"
            },
    ) {
        // Shape is not the only cue here — the detail line states the state in
        // words too, so this stays readable without color vision.
        if (status.state == StageState.LOADING) {
            CircularProgressIndicator(
                modifier = Modifier.size(10.dp),
                strokeWidth = 2.dp,
                color = tint,
            )
        } else {
            Box(
                Modifier
                    .padding(top = 4.dp)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(tint),
            )
        }
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f)) {
            Text(
                text = status.spec.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (status.state == StageState.FAILED ||
                    status.state == StageState.MISSING
                ) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

/** Compact size label; the cascade spans tens of MB to over a GB. */
internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.2f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> "%.0f MB".format(bytes / 1_048_576.0)
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
