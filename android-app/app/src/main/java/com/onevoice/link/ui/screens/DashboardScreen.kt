package com.onevoice.link.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.onevoice.link.R
import com.onevoice.link.core.AudioInputMode
import com.onevoice.link.core.AudioRoutePreference
import com.onevoice.link.core.EngineBackend
import com.onevoice.link.core.NpuEngine
import com.onevoice.link.ui.components.AppHeader
import com.onevoice.link.ui.components.AudioRouteSelector
import com.onevoice.link.ui.components.EngineControls
import com.onevoice.link.ui.components.MicPermissionRow
import com.onevoice.link.ui.components.ModelCascadeCard
import com.onevoice.link.ui.components.StatusCard
import com.onevoice.link.ui.components.TranslationCard
import com.onevoice.link.ui.theme.OneVoiceMotion
import com.onevoice.link.ui.theme.Spacing

/**
 * Route for the only production mode: auto-direction Hybrid.
 * [controller] is Activity-root owned so opening Settings never destroys models.
 */
@Composable
fun DashboardScreen(
    micGranted: Boolean,
    micPermanentlyDenied: Boolean,
    engine: EngineBackend,
    controller: EngineController,
    onRequestMic: () -> Unit,
    onOpenMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val npuEngine = engine as? NpuEngine

    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        DashboardContent(
            state = DashboardUiState(
                stage = controller.stage,
                busy = controller.busy,
                message = controller.message,
                micGranted = micGranted,
                micPermanentlyDenied = micPermanentlyDenied,
                supportsLiveTranslation = controller.supportsLiveTranslation,
                transcript = controller.transcript,
                translation = controller.translation,
                replayAvailable = controller.replayAvailable,
                routedDirection = controller.routedDirection,
                executionPath = controller.executionPath,
                stages = controller.stages,
                targetSoc = npuEngine?.manifest?.targetMarketing.orEmpty(),
                hexagon = npuEngine?.manifest?.hexagon.orEmpty(),
                modelRootPath = npuEngine?.modelRootPath.orEmpty(),
                audioRoute = controller.audioRoute,
                activeRouteLabel = controller.activeRouteLabel,
                audioInputMode = npuEngine?.audioInputMode ?: AudioInputMode.DASHBOARD,
            ),
            onInitialize = controller::initialize,
            onToggleTranslation = controller::toggleTranslation,
            onRequestMic = onRequestMic,
            onReplay = controller::replayLast,
            onOpenMenu = onOpenMenu,
            onAudioRouteSelected = controller::selectAudioRoute,
            modifier = Modifier.padding(innerPadding),
        )
    }
}

/** Stateless and fully previewable: state in, events out. */
@Composable
fun DashboardContent(
    state: DashboardUiState,
    onInitialize: () -> Unit,
    onToggleTranslation: () -> Unit,
    onRequestMic: () -> Unit,
    modifier: Modifier = Modifier,
    onReplay: () -> Unit = {},
    onOpenMenu: () -> Unit = {},
    onAudioRouteSelected: (AudioRoutePreference) -> Unit = {},
) {
    Column(
        modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.xl),
    ) {
        // Upper content scrolls (landscape / large fonts); actions stay pinned
        // below in the thumb zone.
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            AppHeader(
                onMenuClick = onOpenMenu,
                modifier = Modifier.padding(top = Spacing.lg, bottom = Spacing.sm),
            )
            StatusCard(
                stage = state.stage,
                busy = state.busy,
                message = state.message.resolve(),
                isError = state.message.kind == MessageKind.Error,
            )
            if (state.supportsLiveTranslation) {
                AudioRouteSelector(
                    selected = state.audioRoute,
                    activeRouteLabel = state.activeRouteLabel,
                    enabled = state.canChangeAudioRoute,
                    onSelected = onAudioRouteSelected,
                    inputMode = state.audioInputMode,
                )
            }
            if (state.showTranslationCard) {
                TranslationCard(
                    transcript = state.transcript,
                    translation = state.translation,
                    routedDirection = state.routedDirection,
                    executionPath = state.executionPath,
                    canReplay = state.canReplay,
                    onReplay = onReplay,
                )
            }
            if (state.stages.isNotEmpty()) {
                // Diagnostics stay available for testing without pushing the
                // live translation below the first screenful.
                ModelCascadeCard(
                    stages = state.stages,
                    targetSoc = state.targetSoc,
                    hexagon = state.hexagon,
                    modelRootPath = state.modelRootPath,
                )
            }
            if (state.supportsLiveTranslation) {
                MicPermissionRow(
                    granted = state.micGranted,
                    onRequestMic = onRequestMic,
                    permanentlyDenied = state.micPermanentlyDenied,
                )
            }
            Text(
                text = stringResource(R.string.scope_notice_hybrid_auto),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        EngineControls(
            state = state,
            onInitialize = onInitialize,
            onToggleTranslation = onToggleTranslation,
            modifier = Modifier.padding(top = Spacing.lg),
        )
        AnimatedVisibility(
            visible = state.showMicWarning,
            enter = fadeIn(tween(200, delayMillis = 50)) +
                expandVertically(tween(OneVoiceMotion.DurationMedium, easing = OneVoiceMotion.StandardEasing)),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(200)),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = Spacing.md),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_warning_24),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = stringResource(R.string.warn_mic_required),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        Spacer(Modifier.height(Spacing.xl))
    }
}
