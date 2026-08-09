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
import com.onevoice.link.core.NpuEngine
import com.onevoice.link.core.StageState
import com.onevoice.link.core.StageStatus
import com.onevoice.link.core.TranslationDirection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Lifecycle stages shared by the CPU and QNN live paths. */
enum class EngineStage(@field:StringRes val labelRes: Int) {
    NOT_INITIALIZED(R.string.stage_not_initialized),
    INITIALIZED(R.string.stage_initialized),
    RUNNING(R.string.stage_running),
}

/** Which implementation currently owns the speech pipeline. */
enum class BackendKind(@field:StringRes val labelRes: Int) {
    /** Complete offline ASR -> MT -> TTS pipeline backed by ONNX Runtime. */
    HF(R.string.backend_hf),

    /** QNN ASR/MT with CPU Kokoro rendering one continuous utterance. */
    HYBRID(R.string.backend_hybrid),

    /** Native QNN/HTP learned stages with bounded CPU audio/tensor host glue. */
    QNN(R.string.backend_qnn),
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
    val selectedBackend: BackendKind = BackendKind.HF,
    val backendDisplayName: String = "",
    val supportsLiveTranslation: Boolean = true,
    val transcript: String? = null,
    val translation: String? = null,
    val replayAvailable: Boolean = false,
    val direction: TranslationDirection = TranslationDirection.VI_TO_EN,
    /** QNN-only: one row per context binary in the selected cascade. */
    val stages: List<StageStatus> = emptyList(),
    val unavailableDirections: Set<TranslationDirection> = emptySet(),
    val directionUnavailableReason: String? = null,
    val targetSoc: String = "",
    val hexagon: String = "",
    val modelRootPath: String = "",
) {
    val running: Boolean get() = stage == EngineStage.RUNNING
    val isQnn: Boolean get() = selectedBackend != BackendKind.HF
    val isHybrid: Boolean get() = selectedBackend == BackendKind.HYBRID

    val canInitialize: Boolean get() =
        !busy && stage == EngineStage.NOT_INITIALIZED &&
            direction !in unavailableDirections

    val canToggle: Boolean get() =
        supportsLiveTranslation && !busy &&
            stage != EngineStage.NOT_INITIALIZED && (running || micGranted)

    val showMicWarning: Boolean get() =
        supportsLiveTranslation && stage != EngineStage.NOT_INITIALIZED &&
            !micGranted && !running

    /** Selecting another backend releases the currently resident model bundle. */
    val canSelectBackend: Boolean get() = !busy && !running

    /**
     * A direction change also releases the current bundle/cascade, so changing
     * direction remains possible after initialization without retaining both
     * directions in memory.
     */
    val canSwitchDirection: Boolean get() = !busy && !running

    val showTranslationCard: Boolean get() = supportsLiveTranslation
    val canReplay: Boolean get() =
        supportsLiveTranslation && replayAvailable && !busy
}

/**
 * Activity-scoped state holder for both [EngineBackend] implementations.
 *
 * It owns backend lifecycle and keeps only one model bundle resident. Switching
 * backend or direction destroys the active backend first, then returns the UI to
 * NOT_INITIALIZED so the newly selected models can be loaded explicitly.
 */
@Stable
class EngineController(
    backends: Map<BackendKind, EngineBackend>,
    private val scope: CoroutineScope,
    defaultBackend: BackendKind = BackendKind.HF,
) {
    private val backends = backends.toMap()
    private var backend: EngineBackend =
        requireNotNull(this.backends[defaultBackend]) {
            "No backend registered for $defaultBackend"
        }

    // This scope survives rememberCoroutineScope cancellation long enough to
    // release native/ORT resources during DisposableEffect.onDispose.
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var shutdownRequested = false

    var backendKind by mutableStateOf(defaultBackend)
        private set
    var stage by mutableStateOf(EngineStage.NOT_INITIALIZED)
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf(idleMessage(defaultBackend))
        private set
    var transcript by mutableStateOf<String?>(null)
        private set
    var translation by mutableStateOf<String?>(null)
        private set
    var replayAvailable by mutableStateOf(false)
        private set
    var direction by mutableStateOf(TranslationDirection.VI_TO_EN)
        private set
    var stages by mutableStateOf<List<StageStatus>>(emptyList())
        private set

    val backendDisplayName: String get() = backend.displayName
    val supportsLiveTranslation: Boolean get() = backend.supportsLiveTranslation

    private var pipelineListener: EngineBackend.PipelineListener? = null
    private var stageListener: ((List<StageStatus>) -> Unit)? = null

    init {
        backend.setDirection(direction)
        attachBackendListeners()
        stages = plannedStages()
    }

    fun initialize() = launchBusy(initializingMessage()) {
        val selected = backend
        val result = withContext(Dispatchers.IO) {
            runCatching { selected.initialize() }
        }
        result.onSuccess { initialized ->
            stage = if (initialized) EngineStage.INITIALIZED
            else EngineStage.NOT_INITIALIZED
            message = if (initialized) {
                initializedMessage()
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
     * Switch implementation while keeping only one resident model set. This is
     * allowed from INITIALIZED; [EngineBackend.destroy] performs the required
     * explicit unload.
     */
    fun selectBackend(newKind: BackendKind) {
        if (newKind == backendKind || busy || stage == EngineStage.RUNNING) return
        launchBusy(UiMessage(R.string.msg_switching_backend)) {
            val previous = backend
            detachBackendListeners(previous)
            withContext(Dispatchers.IO) { runCatching { previous.destroy() } }

            backend = requireNotNull(backends[newKind])
            backendKind = newKind
            withContext(Dispatchers.IO) { backend.setDirection(direction) }
            resetPresentation()
            attachBackendListeners()
            stages = plannedStages()
            message = idleMessage(newKind)
        }
    }

    /**
     * Release the selected direction before changing it. This avoids keeping two
     * Parakeet/Kokoro bundles or two QNN cascades resident on an 8 GB phone.
     */
    fun switchDirection(newDirection: TranslationDirection) {
        if (newDirection == direction || busy || stage == EngineStage.RUNNING) return
        launchBusy(UiMessage(R.string.msg_switching_direction)) {
            val selected = backend
            detachBackendListeners(selected)
            withContext(Dispatchers.IO) {
                runCatching { selected.destroy() }
                selected.setDirection(newDirection)
            }
            direction = newDirection
            resetPresentation()
            attachBackendListeners()
            stages = plannedStages()
            message = idleMessage(backendKind)
        }
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

    /**
     * Detach callbacks and begin off-main-thread teardown. MainActivity retains
     * an idempotent final cleanup as a lifecycle safety net.
     */
    fun shutdown() {
        if (shutdownRequested) return
        shutdownRequested = true
        backends.values.distinct().forEach { registered ->
            registered.setPipelineListener(null)
            (registered as? NpuEngine)?.setStageListener(null)
        }
        cleanupScope.launch {
            try {
                backends.values.distinct().forEach { registered ->
                    runCatching { registered.destroy() }
                }
            } finally {
                cleanupScope.cancel()
            }
        }
    }

    private fun start() = launchBusy(UiMessage(R.string.msg_starting)) {
        transcript = null
        translation = null
        replayAvailable = false
        val result = withContext(Dispatchers.IO) { runCatching { backend.start() } }
        result.onSuccess { started ->
            if (started) {
                stage = EngineStage.RUNNING
                message = UiMessage(R.string.msg_live)
            } else {
                message = backendFailure(R.string.msg_start_failed)
            }
        }.onFailure { failure ->
            message = nativeFailure(failure)
        }
    }

    private fun stop() = launchBusy(UiMessage(R.string.msg_stopping)) {
        withContext(Dispatchers.IO) { runCatching { backend.stop() } }
        stage = EngineStage.INITIALIZED
        message = UiMessage(R.string.msg_stopped)
    }

    private fun attachBackendListeners() {
        val selected = backend
        val listener = object : EngineBackend.PipelineListener {
            override fun onSegment(transcript: String, translation: String?) {
                scope.launch {
                    if (backend !== selected || shutdownRequested) return@launch
                    this@EngineController.transcript = transcript
                    if (translation == null) {
                        this@EngineController.translation = null
                    } else {
                        this@EngineController.translation = translation
                    }
                    // Translation text arrives before TTS. Replay becomes safe
                    // only after onAudioReady publishes the waveform.
                    replayAvailable = false
                }
            }

            override fun onAudioReady() {
                scope.launch {
                    if (backend !== selected || shutdownRequested) return@launch
                    replayAvailable = true
                }
            }

            override fun onPipelineError(message: String) {
                scope.launch {
                    if (backend !== selected || shutdownRequested) return@launch
                    // Per-segment MT/TTS errors are recoverable and capture may
                    // still be running, so the callback must not invent a stop.
                    this@EngineController.message = UiMessage(
                        R.string.msg_backend_error,
                        message,
                        MessageKind.Error,
                    )
                }
            }

            override fun onPipelineStopped(message: String) {
                scope.launch {
                    if (backend !== selected || shutdownRequested) return@launch
                    stage = EngineStage.INITIALIZED
                    this@EngineController.message = UiMessage(
                        R.string.msg_backend_error,
                        message,
                        MessageKind.Error,
                    )
                }
            }
        }
        pipelineListener = listener
        selected.setPipelineListener(listener)

        (selected as? NpuEngine)?.let { npu ->
            val listener: (List<StageStatus>) -> Unit = { updated ->
                scope.launch {
                    if (backend === npu && !shutdownRequested) stages = updated
                }
            }
            stageListener = listener
            npu.setStageListener(listener)
        }
    }

    private fun detachBackendListeners(selected: EngineBackend = backend) {
        selected.setPipelineListener(null)
        (selected as? NpuEngine)?.setStageListener(null)
        pipelineListener = null
        stageListener = null
    }

    private fun plannedStages(): List<StageStatus> =
        (backend as? NpuEngine)?.plannedStages(direction).orEmpty()

    private fun resetPresentation() {
        stage = EngineStage.NOT_INITIALIZED
        transcript = null
        translation = null
        replayAvailable = false
        stages = emptyList()
    }

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

    private fun initializingMessage(): UiMessage = UiMessage(
        when (backendKind) {
            BackendKind.HF -> R.string.msg_initializing_offline
            BackendKind.QNN -> R.string.msg_initializing_qnn
            BackendKind.HYBRID -> R.string.msg_initializing_hybrid
        },
    )

    private fun initializedMessage(): UiMessage = UiMessage(
        when (backendKind) {
            BackendKind.HF -> R.string.msg_initialized_offline
            BackendKind.QNN -> R.string.msg_initialized_qnn
            BackendKind.HYBRID -> R.string.msg_initialized_hybrid
        },
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
            }
        }
    }

    private companion object {
        fun idleMessage(kind: BackendKind): UiMessage = UiMessage(
            when (kind) {
                BackendKind.HF -> R.string.msg_idle_offline
                BackendKind.QNN -> R.string.msg_idle_qnn
                BackendKind.HYBRID -> R.string.msg_idle_hybrid
            },
        )
    }
}
