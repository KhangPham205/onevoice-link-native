package com.onevoice.link.ui.screens

import android.content.Context
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.onevoice.link.R
import com.onevoice.link.core.AudioInputMode
import com.onevoice.link.core.AudioRoutePreference
import com.onevoice.link.core.EngineBackend
import com.onevoice.link.core.ExecutionPath
import com.onevoice.link.core.LivePhase
import com.onevoice.link.core.MicInputPreference
import com.onevoice.link.core.NpuEngine
import com.onevoice.link.core.PipelineSegment
import com.onevoice.link.core.SessionRecorder
import com.onevoice.link.core.StageState
import com.onevoice.link.core.StageStatus
import com.onevoice.link.core.TranslationDirection
import com.onevoice.link.core.TurnReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Lifecycle stages for the single auto-direction Hybrid pipeline. */
enum class EngineStage(@field:StringRes val labelRes: Int) {
    NOT_INITIALIZED(R.string.stage_not_initialized),
    INITIALIZED(R.string.stage_initialized),
    RUNNING(R.string.stage_running),
}

enum class MessageKind { Info, Error }

/** A status-line message; [kind] is presentation metadata only. */
@Immutable
data class UiMessage(
    @field:StringRes val resId: Int,
    val formatArg: String? = null,
    val kind: MessageKind = MessageKind.Info,
)

@Composable
fun UiMessage.resolve(): String =
    if (formatArg != null) stringResource(resId, formatArg) else stringResource(resId)

/** One choice in the Dashboard's term-library selector; [id] null means off. */
@Immutable
data class TermLibraryOption(val id: String, val name: String, val terms: Int)

/** Everything the stateless dashboard renders. */
@Immutable
data class DashboardUiState(
    val stage: EngineStage,
    val busy: Boolean,
    val message: UiMessage,
    val micGranted: Boolean,
    val micPermanentlyDenied: Boolean = false,
    val supportsLiveTranslation: Boolean = true,
    val transcript: String? = null,
    val translation: String? = null,
    val replayAvailable: Boolean = false,
    /** Null until the utterance router accepts VI or EN. */
    val routedDirection: TranslationDirection? = null,
    /** Actual route used for the latest utterance, not a user-selectable mode. */
    val executionPath: ExecutionPath? = null,
    /** QNN contexts needed by the Hybrid pipeline. */
    val stages: List<StageStatus> = emptyList(),
    val targetSoc: String = "",
    val hexagon: String = "",
    val modelRootPath: String = "",
    val audioRoute: AudioRoutePreference = AudioRoutePreference.AUTO,
    /** Device route of the most recent turn; null before the first capture. */
    val activeRouteLabel: String? = null,
    /** Build-time microphone mode (`audioInput`); SELECT shows the two-mic choice. */
    val audioInputMode: AudioInputMode = AudioInputMode.DASHBOARD,
    /** The Dashboard microphone of a SELECT build. */
    val micInput: MicInputPreference = MicInputPreference.PHONE,
    /** -PasrBias builds offer workplace term libraries for the VI ASR. */
    val termLibrariesEnabled: Boolean = false,
    /** Libraries staged on this phone, sorted by name. */
    val termLibraries: List<TermLibraryOption> = emptyList(),
    /** The selected library's id; null = off (plain greedy recognition). */
    val termLibraryId: String? = null,
) {
    val running: Boolean get() = stage == EngineStage.RUNNING

    val canInitialize: Boolean get() =
        !busy && stage == EngineStage.NOT_INITIALIZED

    val canToggle: Boolean get() =
        supportsLiveTranslation && !busy &&
            stage != EngineStage.NOT_INITIALIZED && (running || micGranted)

    val showMicWarning: Boolean get() =
        supportsLiveTranslation && stage != EngineStage.NOT_INITIALIZED &&
            !micGranted && !running

    /**
     * A library change applies from the next turn. Not while translating, so
     * a turn never switches library between its recognition and translation.
     */
    val canChangeTermLibrary: Boolean get() = !busy && !running

    /** A route change applies at the next turn, but not while a start/stop is busy. */
    val canChangeAudioRoute: Boolean get() =
        supportsLiveTranslation && !busy &&
            (audioInputMode == AudioInputMode.DASHBOARD || audioInputMode == AudioInputMode.SELECT)

    val showTranslationCard: Boolean get() = supportsLiveTranslation
    val canReplay: Boolean get() =
        supportsLiveTranslation && replayAvailable && !busy
}

/**
 * Activity-scoped state holder for the single Hybrid engine.
 *
 * Language routing happens per utterance inside the engine. The controller only
 * presents the direction committed with the resulting transcript.
 */
@Stable
class EngineController(
    private val backend: EngineBackend,
    private val scope: CoroutineScope,
) {
    // This scope survives rememberCoroutineScope cancellation long enough to
    // release native/ORT resources during DisposableEffect.onDispose.
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var shutdownRequested = false
    private var pauseRequested = false

    var stage by mutableStateOf(EngineStage.NOT_INITIALIZED)
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf(UiMessage(R.string.msg_idle_hybrid_auto))
        private set
    var transcript by mutableStateOf<String?>(null)
        private set
    var translation by mutableStateOf<String?>(null)
        private set
    var replayAvailable by mutableStateOf(false)
        private set
    var routedDirection by mutableStateOf<TranslationDirection?>(null)
        private set
    var executionPath by mutableStateOf<ExecutionPath?>(null)
        private set
    var stages by mutableStateOf<List<StageStatus>>(emptyList())
        private set
    var audioRoute by mutableStateOf(
        (backend as? NpuEngine)?.audioRoutePreference ?: AudioRoutePreference.AUTO,
    )
        private set
    var activeRouteLabel by mutableStateOf<String?>(null)
        private set
    var micInput by mutableStateOf(
        (backend as? NpuEngine)?.micInputPreference ?: MicInputPreference.PHONE,
    )
        private set
    var termLibraries by mutableStateOf<List<TermLibraryOption>>(emptyList())
        private set
    var termLibraryId by mutableStateOf<String?>(null)
        private set
    val termLibrariesEnabled: Boolean get() = (backend as? NpuEngine)?.asrBiasAvailable == true

    /**
     * Every live turn since the session began, oldest first. It survives
     * start/stop so a 30-minute demo keeps one continuous on-screen history,
     * matching the single JSONL file the engine appends to.
     */
    var turns by mutableStateOf<List<TurnReport>>(emptyList())
        private set
    var livePhase by mutableStateOf(LivePhase.IDLE)
        private set
    /** Monotonic time the current [livePhase] began, for an elapsed timer. */
    var livePhaseSinceMs by mutableStateOf(SystemClock.elapsedRealtime())
        private set
    var exportMessage by mutableStateOf<UiMessage?>(null)
        private set

    val supportsLiveTranslation: Boolean get() = backend.supportsLiveTranslation

    init {
        attachBackendListeners()
        stages = plannedStages()
        refreshTermLibraries()
    }

    fun initialize() = launchBusy(UiMessage(R.string.msg_initializing_hybrid_auto)) {
        val result = withContext(Dispatchers.IO) {
            runCatching { backend.initialize() }
        }
        result.onSuccess { initialized ->
            // Load rescans the staged libraries and may fall back to off.
            refreshTermLibraries()
            stage = if (initialized) EngineStage.INITIALIZED
            else EngineStage.NOT_INITIALIZED
            message = if (initialized) {
                UiMessage(
                    when ((backend as? NpuEngine)?.audioInputMode) {
                        AudioInputMode.SELECT -> R.string.msg_initialized_hybrid_select
                        AudioInputMode.PHONE, AudioInputMode.USB -> R.string.msg_initialized_hybrid_fixed
                        else -> R.string.msg_initialized_hybrid_auto
                    },
                )
            } else {
                backendFailure(R.string.msg_init_failed)
            }
        }.onFailure { failure ->
            stage = EngineStage.NOT_INITIALIZED
            message = nativeFailure(failure)
        }
    }

    /** Staged term libraries and the persisted choice, as the engine resolved them. */
    private fun refreshTermLibraries() {
        val engine = backend as? NpuEngine ?: return
        if (!engine.asrBiasAvailable) return
        termLibraries = engine.refreshAsrBiasLibraries().map { TermLibraryOption(it.id, it.name, it.terms) }
        termLibraryId = engine.asrBiasLibrary?.id
    }

    /** Persisted; applied to the loaded decoder from the next turn. Null = off. */
    fun selectTermLibrary(id: String?) {
        if (busy || shutdownRequested || stage == EngineStage.RUNNING) return
        val engine = backend as? NpuEngine ?: return
        if (id == termLibraryId) return
        if (engine.selectAsrBiasLibrary(id)) {
            termLibraryId = id
            message = UiMessage(
                R.string.term_library_selected,
                termLibraries.firstOrNull { it.id == id }?.name,
            ).takeIf { id != null } ?: UiMessage(R.string.term_library_off_selected)
        } else {
            message = UiMessage(
                R.string.term_library_failed,
                engine.lastError ?: "",
                MessageKind.Error,
            )
        }
    }

    /** SELECT builds: persisted, and used from the next IDLE -> CAPTURING edge. */
    fun selectMicInput(choice: MicInputPreference) {
        if (busy || shutdownRequested) return
        val engine = backend as? NpuEngine ?: return
        if (engine.audioInputMode != AudioInputMode.SELECT) return
        engine.micInputPreference = choice
        micInput = choice
    }

    /** Persisted by the engine and resolved again at the next IDLE -> CAPTURING edge. */
    fun selectAudioRoute(route: AudioRoutePreference) {
        if (busy || shutdownRequested) return
        if ((backend as? NpuEngine)?.audioInputMode?.let { it != AudioInputMode.DASHBOARD } == true) return
        (backend as? NpuEngine)?.audioRoutePreference = route
        audioRoute = route
    }

    fun toggleTranslation() {
        if (!backend.supportsLiveTranslation) return
        if (stage == EngineStage.RUNNING) stop() else start()
    }

    /**
     * Stop microphone/playback when translation controls are no longer visible.
     * Loaded models stay resident, so returning to the dashboard is cheap.
     */
    fun pauseLiveTranslation() {
        if (shutdownRequested) return
        pauseRequested = true
        if (!busy && stage != EngineStage.NOT_INITIALIZED) stop()
    }

    fun replayLast() {
        if (!backend.supportsLiveTranslation || !replayAvailable || busy) return
        launchBusy(UiMessage(R.string.msg_replaying)) {
            val result = withContext(Dispatchers.IO) {
                runCatching { backend.replayLastAudio() }
            }
            message = result.fold(
                onSuccess = { replayed ->
                    if (replayed) UiMessage(R.string.msg_replaying)
                    else backendFailure(R.string.msg_replay_unavailable)
                },
                onFailure = ::nativeFailure,
            )
        }
    }

    /** Copy the session JSONL to Download/OneVoice so it can leave the device. */
    fun exportSession(context: Context) {
        val file = (backend as? NpuEngine)?.sessionFile
        if (file == null) {
            exportMessage = UiMessage(R.string.demo_export_empty, kind = MessageKind.Error)
            return
        }
        val appContext = context.applicationContext
        scope.launch {
            exportMessage = withContext(Dispatchers.IO) {
                runCatching { SessionRecorder.exportToDownloads(appContext, file) }
            }.fold(
                onSuccess = { UiMessage(R.string.demo_export_done, it) },
                onFailure = {
                    UiMessage(
                        R.string.demo_export_failed,
                        it.message ?: it.javaClass.simpleName,
                        MessageKind.Error,
                    )
                },
            )
        }
    }

    /** Clear the on-screen history and make the next turn open a new log file. */
    fun startNewSession() {
        if (busy || stage == EngineStage.RUNNING) return
        (backend as? NpuEngine)?.startNewSession()
        turns = emptyList()
        exportMessage = null
    }

    /** Detach callbacks and begin off-main-thread teardown. */
    fun shutdown() {
        if (shutdownRequested) return
        shutdownRequested = true
        backend.setPipelineListener(null)
        (backend as? NpuEngine)?.setStageListener(null)
        cleanupScope.launch {
            try {
                runCatching { backend.destroy() }
            } finally {
                cleanupScope.cancel()
            }
        }
    }

    private fun start() {
        pauseRequested = false
        launchBusy(UiMessage(R.string.msg_starting_auto)) {
            transcript = null
            translation = null
            replayAvailable = false
            routedDirection = null
            executionPath = null
            val result = withContext(Dispatchers.IO) { runCatching { backend.start() } }
            result.onSuccess { started ->
                if (started) {
                    stage = EngineStage.RUNNING
                    message = UiMessage(R.string.msg_live_auto)
                } else {
                    message = backendFailure(R.string.msg_start_failed)
                }
            }.onFailure { failure ->
                message = nativeFailure(failure)
            }
        }
    }

    private fun stop() {
        // Consume a pending lifecycle/navigation pause before entering the
        // busy envelope; otherwise its finally block would schedule stop again.
        pauseRequested = false
        launchBusy(UiMessage(R.string.msg_stopping)) {
            withContext(Dispatchers.IO) { runCatching { backend.stop() } }
            stage = EngineStage.INITIALIZED
            message = UiMessage(R.string.msg_stopped)
        }
    }

    private fun attachBackendListeners() {
        val listener = object : EngineBackend.PipelineListener {
            override fun onSegment(segment: PipelineSegment) {
                scope.launch {
                    if (shutdownRequested) return@launch
                    transcript = segment.transcript
                    translation = segment.translation
                    routedDirection = segment.direction
                    executionPath = segment.executionPath
                    activeRouteLabel = (backend as? NpuEngine)?.lastRouteLabel
                    if (stage == EngineStage.RUNNING) {
                        // A valid next utterance clears a recoverable routing or
                        // per-segment inference error from the status banner.
                        message = UiMessage(R.string.msg_live_auto)
                    }
                    // Translation text arrives before TTS. Replay becomes safe
                    // only after onAudioReady publishes the waveform.
                    replayAvailable = false
                }
            }

            override fun onAudioReady() {
                scope.launch {
                    if (shutdownRequested) return@launch
                    replayAvailable = true
                }
            }

            override fun onPipelineError(message: String) {
                scope.launch {
                    if (shutdownRequested) return@launch
                    // Per-utterance route/MT/TTS failures are recoverable; the
                    // capture worker may still be listening for the next turn.
                    this@EngineController.message = UiMessage(
                        R.string.msg_backend_error,
                        message,
                        MessageKind.Error,
                    )
                }
            }

            override fun onTurnReport(report: TurnReport) {
                scope.launch {
                    if (shutdownRequested) return@launch
                    turns = (turns + report).takeLast(MAX_TURN_HISTORY)
                }
            }

            override fun onLivePhase(phase: LivePhase) {
                scope.launch {
                    if (shutdownRequested) return@launch
                    livePhase = phase
                    livePhaseSinceMs = SystemClock.elapsedRealtime()
                }
            }

            override fun onPipelineStopped(message: String) {
                scope.launch {
                    if (shutdownRequested) return@launch
                    stage = EngineStage.INITIALIZED
                    this@EngineController.message = UiMessage(
                        R.string.msg_backend_error,
                        message,
                        MessageKind.Error,
                    )
                }
            }
        }
        backend.setPipelineListener(listener)

        (backend as? NpuEngine)?.let { npu ->
            npu.setStageListener { updated ->
                scope.launch {
                    if (!shutdownRequested) stages = updated
                }
            }
        }
    }

    private fun plannedStages(): List<StageStatus> =
        (backend as? NpuEngine)?.plannedStages().orEmpty()

    /** Prefer the backend's own diagnostic over the generic failure copy. */
    private fun backendFailure(@StringRes generic: Int): UiMessage {
        val detail = backend.lastError
            ?: stages.firstOrNull { it.state == StageState.FAILED }?.error
        return if (detail != null) {
            UiMessage(R.string.msg_backend_error, detail, MessageKind.Error)
        } else {
            UiMessage(generic, kind = MessageKind.Error)
        }
    }

    private fun nativeFailure(failure: Throwable): UiMessage = UiMessage(
        R.string.msg_native_error,
        failure.message ?: failure.javaClass.simpleName,
        MessageKind.Error,
    )

    /** Shared envelope: busy -> progress message -> operation -> not busy. */
    private fun launchBusy(progress: UiMessage, block: suspend () -> Unit) {
        if (busy || shutdownRequested) return
        busy = true
        message = progress
        scope.launch {
            try {
                block()
            } catch (failure: Throwable) {
                message = nativeFailure(failure)
            } finally {
                busy = false
                // Navigation/backgrounding may happen while start or replay is
                // briefly busy. Honor that deferred pause as soon as the
                // current operation releases the backend.
                if (pauseRequested && !shutdownRequested &&
                    stage != EngineStage.NOT_INITIALIZED
                ) {
                    stop()
                }
            }
        }
    }

    private companion object {
        /** About an hour of turns; the JSONL file keeps everything. */
        const val MAX_TURN_HISTORY = 500
    }
}
