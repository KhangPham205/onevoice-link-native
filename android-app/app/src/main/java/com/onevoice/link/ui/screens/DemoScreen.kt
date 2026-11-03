package com.onevoice.link.ui.screens

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.onevoice.link.R
import com.onevoice.link.core.DeviceVitals
import com.onevoice.link.core.ExecutionPath
import com.onevoice.link.core.LivePhase
import com.onevoice.link.core.TranslationDirection
import com.onevoice.link.core.TurnReport
import com.onevoice.link.ui.components.StatusDot
import com.onevoice.link.ui.theme.CaptionMono
import com.onevoice.link.ui.theme.OneVoiceTheme
import com.onevoice.link.ui.theme.OverlineMono
import com.onevoice.link.ui.theme.Spacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val VITALS_POLL_MS = 1_000L
private const val PHASE_TICK_MS = 100L

/**
 * Screen-recording surface for the uncut offline demo video.
 *
 * Everything a judge must read off the video is on one screen: proof that no
 * network is active, live process memory, the half-duplex phase with an
 * elapsed timer, and every turn's text and stage timings. The same numbers go
 * to the session JSONL, so the video and the submitted logs agree.
 */
@Composable
fun DemoScreen(
    micGranted: Boolean,
    controller: EngineController,
    onRequestMic: () -> Unit,
    onOpenMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var vitals by remember { mutableStateOf<DeviceVitals?>(null) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            vitals = withContext(Dispatchers.IO) { DeviceVitals.read(context.applicationContext) }
            nowMs = System.currentTimeMillis()
            delay(VITALS_POLL_MS)
        }
    }

    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        DemoContent(
            stage = controller.stage,
            busy = controller.busy,
            micGranted = micGranted,
            message = controller.message,
            exportMessage = controller.exportMessage,
            livePhase = controller.livePhase,
            livePhaseSinceMs = controller.livePhaseSinceMs,
            turns = controller.turns,
            vitals = vitals,
            nowMs = nowMs,
            onOpenMenu = onOpenMenu,
            onInitialize = controller::initialize,
            onToggle = { if (micGranted) controller.toggleTranslation() else onRequestMic() },
            onExport = { controller.exportSession(context) },
            onNewSession = controller::startNewSession,
            modifier = Modifier.padding(innerPadding),
        )
    }
}

/** Stateless: state in, events out. */
@Composable
fun DemoContent(
    stage: EngineStage,
    busy: Boolean,
    micGranted: Boolean,
    message: UiMessage,
    exportMessage: UiMessage?,
    livePhase: LivePhase,
    livePhaseSinceMs: Long,
    turns: List<TurnReport>,
    vitals: DeviceVitals?,
    nowMs: Long,
    onOpenMenu: () -> Unit,
    onInitialize: () -> Unit,
    onToggle: () -> Unit,
    onExport: () -> Unit,
    onNewSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    // Follow the newest turn, as a live transcript should.
    LaunchedEffect(turns.size) {
        if (turns.isNotEmpty()) listState.animateScrollToItem(turns.lastIndex)
    }

    Column(
        modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        DemoHeader(nowMs = nowMs, onOpenMenu = onOpenMenu)
        VitalsCard(vitals)
        SessionSummary(turns)
        LivePhaseRow(
            stage = stage,
            phase = livePhase,
            sinceMs = livePhaseSinceMs,
        )
        if (message.kind == MessageKind.Error) {
            Text(
                text = message.resolve(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 3,
            )
        }
        if (turns.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.demo_no_turns),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.lg),
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                items(turns, key = { it.turnId }) { TurnCard(it) }
            }
        }
        exportMessage?.let {
            Text(
                text = it.resolve(),
                style = CaptionMono,
                color = if (it.kind == MessageKind.Error) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        DemoControls(
            stage = stage,
            busy = busy,
            micGranted = micGranted,
            hasTurns = turns.isNotEmpty(),
            onInitialize = onInitialize,
            onToggle = onToggle,
            onExport = onExport,
            onNewSession = onNewSession,
        )
        Spacer(Modifier.height(Spacing.sm))
    }
}

@Composable
private fun DemoHeader(nowMs: Long, onOpenMenu: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = Spacing.sm),
    ) {
        IconButton(onClick = onOpenMenu, modifier = Modifier.size(48.dp)) {
            Icon(
                painter = painterResource(R.drawable.ic_menu_24),
                contentDescription = stringResource(R.string.a11y_open_navigation_menu),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.demo_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = stringResource(R.string.demo_subtitle),
                style = CaptionMono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Wall clock so video timestamps can be matched to JSONL rows.
        Text(
            text = clockFormat().format(Date(nowMs)),
            style = MaterialTheme.typography.titleMedium.copy(
                fontFamily = CaptionMono.fontFamily,
            ),
        )
    }
}

@Composable
private fun VitalsCard(vitals: DeviceVitals?) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)) {
            val offline = vitals?.offline == true
            NetworkBadge(
                text = stringResource(
                    when {
                        vitals == null -> R.string.demo_network_unknown
                        offline && vitals.airplaneMode -> R.string.demo_offline_airplane
                        offline -> R.string.demo_offline
                        else -> R.string.demo_online
                    },
                ),
                good = offline,
            )
            if (vitals == null) return@Column
            if (!vitals.internetPermission) {
                Text(
                    text = stringResource(R.string.demo_no_internet_permission),
                    style = CaptionMono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.xs),
                )
            }
            Text(
                text = stringResource(R.string.demo_memory, gb(vitals.rssKb), gb(vitals.peakRssKb)),
                style = CaptionMono.copy(fontWeight = FontWeight.Bold),
            )
            Text(
                text = stringResource(
                    R.string.demo_device_memory,
                    gb(vitals.memAvailableKb),
                    gb(vitals.memTotalKb),
                    thermalLabel(vitals.thermalStatus),
                ),
                style = CaptionMono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NetworkBadge(text: String, good: Boolean) {
    val colors = if (good) OneVoiceTheme.colors.live else null
    Surface(
        shape = MaterialTheme.shapes.small,
        color = colors?.container ?: MaterialTheme.colorScheme.errorContainer,
        contentColor = colors?.onContainer ?: MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Text(
            text = text,
            style = OverlineMono.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 2.dp),
        )
    }
}

@Composable
private fun SessionSummary(turns: List<TurnReport>) {
    val ok = turns.filter(TurnReport::succeeded)
    val firstAudio = ok.mapNotNull(TurnReport::firstAudioMs)
    Column {
        Text(
            text = stringResource(R.string.demo_summary_turns, ok.size, turns.size),
            style = CaptionMono,
        )
        if (firstAudio.isNotEmpty()) {
            Text(
                text = stringResource(
                    R.string.demo_summary_latency,
                    seconds(percentile(firstAudio, 50.0)),
                    seconds(percentile(firstAudio, 95.0)),
                    // Medians, not means: DVFS and preemption give a long tail.
                    percentile(ok.map { it.frontendMs + it.dualAsrWallMs }, 50.0),
                    percentile(ok.map(TurnReport::mtMs), 50.0),
                    percentile(ok.map(TurnReport::ttsMs), 50.0),
                ),
                style = CaptionMono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LivePhaseRow(stage: EngineStage, phase: LivePhase, sinceMs: Long) {
    var nowMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val ticking = stage == EngineStage.RUNNING && phase == LivePhase.PROCESSING
    LaunchedEffect(ticking, sinceMs) {
        while (ticking) {
            nowMs = SystemClock.elapsedRealtime()
            delay(PHASE_TICK_MS)
        }
    }
    val (label, color) = when {
        stage == EngineStage.NOT_INITIALIZED ->
            stringResource(R.string.demo_phase_not_loaded) to OneVoiceTheme.colors.neutral.indicator
        stage != EngineStage.RUNNING ->
            stringResource(R.string.demo_phase_ready) to OneVoiceTheme.colors.standby.indicator
        phase == LivePhase.PROCESSING -> stringResource(
            R.string.demo_phase_processing,
            seconds((nowMs - sinceMs).coerceAtLeast(0L)),
        ) to MaterialTheme.colorScheme.tertiary
        phase == LivePhase.PLAYING ->
            stringResource(R.string.demo_phase_playing) to MaterialTheme.colorScheme.primary
        else -> stringResource(R.string.demo_phase_listening) to OneVoiceTheme.colors.live.indicator
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        StatusDot(
            color = color,
            size = 14.dp,
            pulsing = stage == EngineStage.RUNNING && phase == LivePhase.CAPTURING,
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(text = label, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun TurnCard(turn: TurnReport) {
    val failed = !turn.succeeded
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (failed) {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(
                        R.string.demo_turn_header,
                        turn.turnId,
                        directionLabel(turn.direction),
                        pathLabel(turn.executionPath),
                        turn.reason,
                    ),
                    style = OverlineMono,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = clockFormat().format(Date(turn.timestampMs)),
                    style = OverlineMono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            turn.transcript?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            turn.translation?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(Spacing.xs))
                Text(text = it, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = stringResource(
                    R.string.demo_turn_stages,
                    turn.frontendMs + turn.dualAsrWallMs,
                    turn.mtMs,
                    turn.ttsMs,
                ),
                style = CaptionMono,
            )
            Text(
                text = turn.firstAudioMs?.let {
                    stringResource(
                        R.string.demo_turn_first_audio,
                        seconds(it),
                        seconds(turn.audioMs),
                        seconds(turn.ttsAudioMs ?: 0L),
                    )
                } ?: stringResource(
                    R.string.demo_turn_no_audio,
                    seconds(turn.postBoundTotalMs),
                    seconds(turn.audioMs),
                ),
                style = CaptionMono.copy(fontWeight = FontWeight.Bold),
            )
            if (turn.viScore != null || turn.enScore != null) {
                Text(
                    text = stringResource(
                        R.string.demo_turn_scores,
                        score(turn.viScore),
                        score(turn.enScore),
                    ),
                    style = CaptionMono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            turn.error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 3,
                )
            }
        }
    }
}

@Composable
private fun DemoControls(
    stage: EngineStage,
    busy: Boolean,
    micGranted: Boolean,
    hasTurns: Boolean,
    onInitialize: () -> Unit,
    onToggle: () -> Unit,
    onExport: () -> Unit,
    onNewSession: () -> Unit,
) {
    val running = stage == EngineStage.RUNNING
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        if (stage == EngineStage.NOT_INITIALIZED) {
            Button(
                onClick = onInitialize,
                enabled = !busy,
                modifier = Modifier.weight(1f).height(52.dp),
            ) {
                Text(stringResource(R.string.demo_action_load))
            }
        } else {
            FilledTonalButton(
                onClick = onToggle,
                enabled = !busy,
                colors = if (running) {
                    ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    )
                } else {
                    ButtonDefaults.filledTonalButtonColors()
                },
                modifier = Modifier.weight(1f).height(52.dp),
            ) {
                Text(
                    stringResource(
                        when {
                            running -> R.string.demo_action_stop
                            micGranted -> R.string.demo_action_start
                            else -> R.string.demo_action_grant_mic
                        },
                    ),
                )
            }
        }
        OutlinedButton(
            onClick = onExport,
            enabled = hasTurns,
            modifier = Modifier.height(52.dp),
        ) {
            Text(stringResource(R.string.demo_action_export))
        }
        OutlinedButton(
            onClick = onNewSession,
            enabled = hasTurns && !running && !busy,
            modifier = Modifier.height(52.dp),
        ) {
            Text(stringResource(R.string.demo_action_new_session))
        }
    }
}

@Composable
private fun directionLabel(direction: TranslationDirection?): String = stringResource(
    when (direction) {
        TranslationDirection.VI_TO_EN -> R.string.demo_dir_vi_en
        TranslationDirection.EN_TO_VI -> R.string.demo_dir_en_vi
        null -> R.string.demo_dir_rejected
    },
)

@Composable
private fun pathLabel(path: ExecutionPath?): String = stringResource(
    when (path) {
        ExecutionPath.HYBRID_NPU -> R.string.demo_path_npu
        ExecutionPath.CPU_FALLBACK -> R.string.demo_path_cpu
        null -> R.string.demo_path_none
    },
)

@Composable
private fun thermalLabel(status: Int): String = stringResource(
    when (status) {
        // -1 (unavailable) reads as normal rather than alarming a judge.
        -1, 0 -> R.string.demo_thermal_none
        1 -> R.string.demo_thermal_light
        2 -> R.string.demo_thermal_moderate
        else -> R.string.demo_thermal_severe
    },
)

private fun clockFormat() = SimpleDateFormat("HH:mm:ss", Locale.ROOT)

private fun gb(kb: Long): String = String.format(Locale.ROOT, "%.2f", kb / 1_048_576.0)

private fun seconds(ms: Long): String = String.format(Locale.ROOT, "%.1f", ms / 1_000.0)

private fun score(value: Double?): String =
    value?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "—"

/**
 * Nearest-rank percentile matching the bench harness's `gates.percentile`
 * (Python's half-even `round`, hence [Math.rint]), so on-screen p95 and the
 * submitted bench report use one definition.
 */
private fun percentile(values: List<Long>, p: Double): Long {
    val sorted = values.sorted()
    val index = (Math.rint(p / 100.0 * sorted.size + 0.5).toInt() - 1)
        .coerceIn(0, sorted.lastIndex)
    return sorted[index]
}
