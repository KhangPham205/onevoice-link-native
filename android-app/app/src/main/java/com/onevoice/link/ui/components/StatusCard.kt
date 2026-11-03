package com.onevoice.link.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.onevoice.link.R
import com.onevoice.link.ui.screens.EngineStage
import com.onevoice.link.ui.theme.OneVoiceMotion
import com.onevoice.link.ui.theme.OneVoiceTheme
import com.onevoice.link.ui.theme.OverlineMono
import com.onevoice.link.ui.theme.Spacing
import com.onevoice.link.ui.theme.StatusColors

/**
 * Hero card: the engine state IS the interface. The container washes with the
 * stage's tonal color, the dot shape-codes the state, and the message line is
 * a polite live region so TalkBack announces every transition.
 */
@Composable
fun StatusCard(
    stage: EngineStage,
    busy: Boolean,
    message: String,
    isError: Boolean,
    modifier: Modifier = Modifier,
) {
    val status = stage.statusColors()
    val container by animateColorAsState(
        targetValue = status.container,
        animationSpec = tween(OneVoiceMotion.DurationColor, easing = OneVoiceMotion.StandardEasing),
        label = "statusContainer",
    )
    val onContainer by animateColorAsState(
        targetValue = status.onContainer,
        animationSpec = tween(OneVoiceMotion.DurationColor, easing = OneVoiceMotion.StandardEasing),
        label = "statusOnContainer",
    )
    val indicator by animateColorAsState(
        targetValue = status.indicator,
        animationSpec = tween(OneVoiceMotion.DurationColor, easing = OneVoiceMotion.StandardEasing),
        label = "statusIndicator",
    )

    val stageLabel = stringResource(stage.labelRes)
    val statusA11y = stringResource(R.string.a11y_engine_status, stageLabel)
    val busyA11y = stringResource(R.string.a11y_busy)

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = container, contentColor = onContainer),
    ) {
        Column(
            Modifier
                .padding(Spacing.xl)
                .animateContentSize(
                    tween(OneVoiceMotion.DurationMedium, easing = OneVoiceMotion.StandardEasing),
                ),
        ) {
            Text(
                text = stringResource(R.string.status_card_overline),
                style = OverlineMono,
                color = onContainer.copy(alpha = 0.7f),
            )
            Spacer(Modifier.height(Spacing.md))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                // One merged node: TalkBack reads "Engine status: Running / Translating".
                modifier = Modifier.semantics(mergeDescendants = true) {
                    contentDescription = statusA11y
                },
            ) {
                StatusDot(
                    color = indicator,
                    size = 14.dp,
                    filled = stage != EngineStage.NOT_INITIALIZED,
                    pulsing = stage == EngineStage.RUNNING,
                )
                Spacer(Modifier.width(Spacing.md))
                AnimatedContent(
                    targetState = stageLabel,
                    transitionSpec = {
                        fadeIn(tween(200, delayMillis = 100)) togetherWith fadeOut(tween(100))
                    },
                    label = "stageLabel",
                ) { label ->
                    Text(text = label, style = MaterialTheme.typography.headlineSmall)
                }
                AnimatedVisibility(
                    visible = busy,
                    enter = fadeIn(tween(OneVoiceMotion.DurationShort)),
                    exit = fadeOut(tween(OneVoiceMotion.DurationShort)),
                ) {
                    Row {
                        Spacer(Modifier.width(Spacing.md))
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(20.dp)
                                .semantics { contentDescription = busyA11y },
                            strokeWidth = 2.5.dp,
                            color = indicator,
                        )
                    }
                }
            }
            Spacer(Modifier.height(Spacing.sm))
            AnimatedContent(
                targetState = message,
                transitionSpec = {
                    fadeIn(tween(200, delayMillis = 100)) togetherWith fadeOut(tween(100))
                },
                label = "statusMessage",
            ) { msg ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isError) {
                        Icon(
                            painter = painterResource(R.drawable.ic_warning_24),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(Spacing.sm))
                    }
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            onContainer.copy(alpha = 0.8f)
                        },
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        }
    }
}

@Composable
private fun EngineStage.statusColors(): StatusColors = when (this) {
    EngineStage.NOT_INITIALIZED -> OneVoiceTheme.colors.neutral
    EngineStage.INITIALIZED -> OneVoiceTheme.colors.standby
    EngineStage.RUNNING -> OneVoiceTheme.colors.live
}
