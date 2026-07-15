package com.onevoice.link.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.onevoice.link.R
import com.onevoice.link.ui.theme.OneVoiceMotion
import com.onevoice.link.ui.theme.OneVoiceTheme
import com.onevoice.link.ui.theme.Spacing

/**
 * Microphone permission list item. The mic / mic-off glyph plus the label
 * wording (not color alone) convey the state; the Grant button relaunches the
 * same system permission dialog as before.
 */
@Composable
fun MicPermissionRow(
    granted: Boolean,
    onRequestMic: () -> Unit,
    modifier: Modifier = Modifier,
    permanentlyDenied: Boolean = false,
) {
    val grantA11y = stringResource(R.string.a11y_grant_mic)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        .semantics(mergeDescendants = true) {},
                ) {
                    Icon(
                        painter = painterResource(
                            if (granted) R.drawable.ic_mic_24 else R.drawable.ic_mic_off_24,
                        ),
                        contentDescription = null,
                        tint = if (granted) {
                            OneVoiceTheme.colors.live.indicator
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(Spacing.md))
                    Text(
                        text = stringResource(
                            if (granted) R.string.mic_granted else R.string.mic_denied,
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (!granted) {
                    FilledTonalButton(
                        onClick = onRequestMic,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .semantics { contentDescription = grantA11y },
                    ) {
                        Text(stringResource(R.string.action_grant))
                    }
                }
            }
            AnimatedVisibility(
                visible = permanentlyDenied && !granted,
                enter = fadeIn(tween(200, delayMillis = 50)) +
                    expandVertically(tween(OneVoiceMotion.DurationMedium, easing = OneVoiceMotion.StandardEasing)),
                exit = fadeOut(tween(120)) + shrinkVertically(tween(200)),
            ) {
                Text(
                    text = stringResource(R.string.mic_denied_permanent_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.sm),
                )
            }
        }
    }
}
