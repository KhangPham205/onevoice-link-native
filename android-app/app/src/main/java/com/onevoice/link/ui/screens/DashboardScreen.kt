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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.onevoice.link.R
import com.onevoice.link.core.EngineBackend
import com.onevoice.link.core.TranslationDirection
import com.onevoice.link.ui.components.AppHeader
import com.onevoice.link.ui.components.BackendSelector
import com.onevoice.link.ui.components.DirectionToggle
import com.onevoice.link.ui.components.EngineControls
import com.onevoice.link.ui.components.MicPermissionRow
import com.onevoice.link.ui.components.StatusCard
import com.onevoice.link.ui.components.TranslationCard
import com.onevoice.link.ui.theme.OneVoiceMotion
import com.onevoice.link.ui.theme.Spacing

/**
 * Stateful entry point: owns backend selection and the per-backend
 * [EngineController] (plain `remember` — its lifetime must match the
 * Activity's, because onDestroy tears down the engines) and the Scaffold.
 */
@Composable
fun DashboardScreen(
    micGranted: Boolean,
    micPermanentlyDenied: Boolean,
    backends: Map<BackendKind, EngineBackend>,
    defaultBackend: BackendKind,
    onRequestMic: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var selectedBackend by remember { mutableStateOf(defaultBackend) }
    val backend = backends.getValue(selectedBackend)
    val controller = remember(backend) { EngineController(backend, scope) }
    // Switching backends (or leaving composition) tears the old engine down —
    // same semantics as the Activity-level destroy, just earlier.
    DisposableEffect(controller) {
        onDispose { controller.shutdown() }
    }
    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        DashboardContent(
            state = DashboardUiState(
                stage = controller.stage,
                busy = controller.busy,
                message = controller.message,
                micGranted = micGranted,
                micPermanentlyDenied = micPermanentlyDenied,
                selectedBackend = selectedBackend,
                transcript = controller.transcript,
                translation = controller.translation,
                direction = controller.direction,
            ),
            onInitialize = controller::initialize,
            onToggleTranslation = controller::toggleTranslation,
            onRequestMic = onRequestMic,
            onSelectBackend = { selectedBackend = it },
            onSelectDirection = controller::switchDirection,
            onReplay = controller::replayLast,
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
    onSelectBackend: (BackendKind) -> Unit = {},
    onSelectDirection: (TranslationDirection) -> Unit = {},
    onReplay: () -> Unit = {},
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
            AppHeader(Modifier.padding(top = Spacing.lg, bottom = Spacing.sm))
            StatusCard(
                stage = state.stage,
                busy = state.busy,
                message = state.message.resolve(),
                isError = state.message.kind == MessageKind.Error,
            )
            if (state.showTranslationCard) {
                TranslationCard(
                    transcript = state.transcript,
                    translation = state.translation,
                    direction = state.direction,
                    onReplay = onReplay,
                )
            }
            if (state.showDirectionToggle) {
                DirectionToggle(
                    direction = state.direction,
                    enabled = state.canSwitchDirection,
                    onSelect = onSelectDirection,
                )
            }
            MicPermissionRow(
                granted = state.micGranted,
                onRequestMic = onRequestMic,
                permanentlyDenied = state.micPermanentlyDenied,
            )
            BackendSelector(
                selected = state.selectedBackend,
                enabled = state.canSelectBackend,
                onSelect = onSelectBackend,
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
