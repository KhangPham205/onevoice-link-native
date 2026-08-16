package com.onevoice.link.ui.screens

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.onevoice.link.R
import com.onevoice.link.core.EngineBackend
import com.onevoice.link.core.ExecutionPath
import com.onevoice.link.core.NpuEngine
import com.onevoice.link.core.PipelineSegment
import com.onevoice.link.core.StageState
import com.onevoice.link.core.StageStatus
import com.onevoice.link.core.TranslationDirection
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
    /** Null until acoustic language identification accepts an utterance. */
    val detectedDirection: TranslationDirection? = null,
    /** Calibrated language-identification score for [detectedDirection]. */
    val lidConfidence: Float? = null,
    /** Actual route used for the latest utterance, not a user-selectable mode. */
    val executionPath: ExecutionPath? = null,
    /** QNN contexts needed by the Hybrid pipeline. */
    val stages: List<StageStatus> = emptyList(),
    val targetSoc: String = "",
    val hexagon: String = "",
    val modelRootPath: String = "",
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

    val showTranslationCard: Boolean get() = supportsLiveTranslation
    val canReplay: Boolean get() =
        supportsLiveTranslation && replayAvailable && !busy
}

/**
 * Activity-scoped state holder for the single Hybrid engine.
 *
 * Language routing happens per utterance inside the engine. The controller only
 * presents the route selected by LID; it never unloads models in response to a
 * UI direction change.
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
    var detectedDirection by mutableStateOf<TranslationDirection?>(null)
        private set
    var lidConfidence by mutableStateOf<Float?>(null)
        private set
    var executionPath by mutableStateOf<ExecutionPath?>(null)
        private set
    var stages by mutableStateOf<List<StageStatus>>(emptyList())
        private set

    val supportsLiveTranslation: Boolean get() = backend.supportsLiveTranslation

    init {
        attachBackendListeners()
        stages = plannedStages()
    }

    fun initialize() = launchBusy(UiMessage(R.string.msg_initializing_hybrid_auto)) {
        val result = withContext(Dispatchers.IO) {
            runCatching { backend.initialize() }
        }
        result.onSuccess { initialized ->
            stage = if (initialized) EngineStage.INITIALIZED
            else EngineStage.NOT_INITIALIZED
            message = if (initialized) {
                UiMessage(R.string.msg_initialized_hybrid_auto)
            } else {
                backendFailure(R.string.msg_init_failed)
            }
        }.onFailure { failure ->
            stage = EngineStage.NOT_INITIALIZED
            message = nativeFailure(failure)
        }
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
            detectedDirection = null
            lidConfidence = null
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
                    detectedDirection = segment.direction
                    lidConfidence = segment.lidConfidence
                    executionPath = segment.executionPath
                    if (stage == EngineStage.RUNNING) {
                        // A valid next utterance clears a recoverable low-LID or
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
                    // Per-utterance LID/MT/TTS failures are recoverable; the
                    // capture worker may still be listening for the next turn.
                    this@EngineController.message = UiMessage(
                        R.string.msg_backend_error,
                        message,
                        MessageKind.Error,
                    )
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
}
