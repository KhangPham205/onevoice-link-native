package com.onevoice.link.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.onevoice.link.R
import com.onevoice.link.ui.screens.DashboardUiState
import com.onevoice.link.ui.theme.OneVoiceMotion
import com.onevoice.link.ui.theme.Spacing

private val ControlButtonHeight = 56.dp

/**
 * The two engine actions. Enabled predicates come from [DashboardUiState]
 * (moved verbatim from the original screen) — no logic lives here.
 */
@Composable
fun EngineControls(
    state: DashboardUiState,
    onInitialize: () -> Unit,
    onToggleTranslation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Button(
            onClick = onInitialize,
            enabled = state.canInitialize,
            modifier = Modifier
                .fillMaxWidth()
                .height(ControlButtonHeight),
        ) {
            Text(stringResource(R.string.action_initialize))
        }

        // Container/content calmly cross-fade to the error tones while running
        // (a tonal M3 "stop", replacing the old saturated red).
        val container by animateColorAsState(
            targetValue = if (state.running) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
            animationSpec = tween(OneVoiceMotion.DurationColor, easing = OneVoiceMotion.StandardEasing),
            label = "toggleContainer",
        )
        val content by animateColorAsState(
            targetValue = if (state.running) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onSecondaryContainer
            },
            animationSpec = tween(OneVoiceMotion.DurationColor, easing = OneVoiceMotion.StandardEasing),
            label = "toggleContent",
        )
        FilledTonalButton(
            onClick = onToggleTranslation,
            enabled = state.canToggle,
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = container,
                contentColor = content,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(ControlButtonHeight),
        ) {
            AnimatedContent(
                targetState = state.running,
                transitionSpec = {
                    fadeIn(tween(200, delayMillis = 100)) togetherWith fadeOut(tween(100))
                },
                label = "toggleLabel",
            ) { running ->
                Text(
                    stringResource(
                        if (running) R.string.action_stop_translating
                        else R.string.action_start_translating,
                    ),
                )
            }
        }
    }
}
