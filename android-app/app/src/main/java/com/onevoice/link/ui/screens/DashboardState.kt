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
import com.onevoice.link.core.TranslationDirection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Lifecycle stages of the engine (native QNN or HF fallback). */
enum class EngineStage(@field:StringRes val labelRes: Int) {
    NOT_INITIALIZED(R.string.stage_not_initialized),
    INITIALIZED(R.string.stage_initialized),
    RUNNING(R.string.stage_running),
}

/** Which engine implementation drives the pipeline. */
enum class BackendKind(@field:StringRes val labelRes: Int) {
    /** Native C++ core + QNN context binary (.bin) on the Hexagon NPU. */
    QNN(R.string.backend_qnn),

    /** HuggingFace models on CPU (sherpa-onnx + ONNX Runtime) — emulator path. */
    HF(R.string.backend_hf),
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

/**
 * Everything the stateless dashboard renders. The derived predicates are the
 * original enabled/visibility expressions, unchanged.
 */
@Immutable
data class DashboardUiState(
    val stage: EngineStage,
    val busy: Boolean,
    val message: UiMessage,
    val micGranted: Boolean,
    val micPermanentlyDenied: Boolean = false,
    val selectedBackend: BackendKind = BackendKind.QNN,
    val transcript: String? = null,
    val translation: String? = null,
    val direction: TranslationDirection = TranslationDirection.VI_TO_EN,
) {
    val running: Boolean get() = stage == EngineStage.RUNNING
    val canInitialize: Boolean get() = !busy && stage == EngineStage.NOT_INITIALIZED
    val canToggle: Boolean get() =
        !busy && stage != EngineStage.NOT_INITIALIZED && (running || micGranted)
    val showMicWarning: Boolean get() =
        stage != EngineStage.NOT_INITIALIZED && !micGranted && !running

    /** Backend may only change while nothing is initialized. */
    val canSelectBackend: Boolean get() = !busy && stage == EngineStage.NOT_INITIALIZED

    /** The HF cascade surfaces intermediate text; QNN is opaque. */
    val showTranslationCard: Boolean get() =
        selectedBackend == BackendKind.HF && (transcript != null || running)

    /** Direction is an HF-cascade concept; hot-switchable once initialized. */
    val showDirectionToggle: Boolean get() = selectedBackend == BackendKind.HF
    val canSwitchDirection: Boolean get() = !busy
}

/**
 * Plain state holder for the engine lifecycle — deliberately NOT a ViewModel:
 * `MainActivity.onDestroy` destroys the engines on every recreation, so UI
 * state must die with the Activity too (create with `remember`).
 *
 * The busy→message→operation→!busy envelope and every state transition are
 * unchanged from the original native-only controller; the backend interface
 * simply routes the four calls to QNN or the HF cascade.
 */
@Stable
class EngineController(
    private val backend: EngineBackend,
    private val scope: CoroutineScope,
) {
    var stage by mutableStateOf(EngineStage.NOT_INITIALIZED)
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf(UiMessage(R.string.msg_idle))
        private set

    /** Live pipeline text from the HF backend (null on QNN). */
    var transcript by mutableStateOf<String?>(null)
        private set
    var translation by mutableStateOf<String?>(null)
        private set

    /** Conversation direction (HF backend; hot-switchable while RUNNING). */
    var direction by mutableStateOf(TranslationDirection.VI_TO_EN)
        private set

    init {
        backend.setPipelineListener(object : EngineBackend.PipelineListener {
            override fun onSegment(transcript: String, translation: String?) {
                // Snapshot state writes are thread-safe from background threads.
                this@EngineController.transcript = transcript
                this@EngineController.translation = translation
            }
        })
    }

    fun initialize() = launchBusy(UiMessage(R.string.msg_initializing)) {
        val result = withContext(Dispatchers.IO) {
            runCatching { backend.initialize() }
        }
        result.onSuccess { initialized ->
            stage = if (initialized) EngineStage.INITIALIZED else EngineStage.NOT_INITIALIZED
            message = if (initialized) {
                UiMessage(R.string.msg_initialized)
            } else {
                backendFailure(R.string.msg_init_failed)
            }
        }.onFailure { t ->
            stage = EngineStage.NOT_INITIALIZED
            message = UiMessage(
                R.string.msg_native_error,
                t.message ?: t.javaClass.simpleName,
                MessageKind.Error,
            )
        }
    }

    fun toggleTranslation() {
        if (stage == EngineStage.RUNNING) stop() else start()
    }

    /** Flip the conversation direction; stale text from the old direction clears. */
    fun switchDirection(newDirection: TranslationDirection) {
        if (newDirection == direction) return
        direction = newDirection
        transcript = null
        translation = null
        backend.setDirection(newDirection)
    }

    /** Re-play the last spoken translation (HF backend only). */
    fun replayLast() {
        backend.replayLastAudio()
    }

    /** Tear down this controller's backend (backend switch / dispose). */
    fun shutdown() {
        backend.setPipelineListener(null)
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { backend.destroy() } }
        }
    }

    private fun start() = launchBusy(UiMessage(R.string.msg_starting)) {
        transcript = null
        translation = null
        val result = withContext(Dispatchers.IO) {
            runCatching { backend.start() }
        }
        result.onSuccess { started ->
            if (started) {
                stage = EngineStage.RUNNING
                message = UiMessage(R.string.msg_live)
            } else {
                message = backendFailure(R.string.msg_start_failed)
            }
        }.onFailure { t ->
            message = UiMessage(
                R.string.msg_native_error,
                t.message ?: t.javaClass.simpleName,
                MessageKind.Error,
            )
        }
    }

    private fun stop() = launchBusy(UiMessage(R.string.msg_stopping)) {
        withContext(Dispatchers.IO) { runCatching { backend.stop() } }
        // Result deliberately ignored: stop always lands back in INITIALIZED.
        stage = EngineStage.INITIALIZED
        message = UiMessage(R.string.msg_stopped)
    }

    /** Prefer the backend's own diagnostic over the generic failure copy. */
    private fun backendFailure(@StringRes generic: Int): UiMessage {
        val detail = backend.lastError
        return if (detail != null) {
            UiMessage(R.string.msg_backend_error, detail, MessageKind.Error)
        } else {
            UiMessage(generic, kind = MessageKind.Error)
        }
    }

    /** Shared envelope: busy → progress message → operation → not busy. */
    private fun launchBusy(progress: UiMessage, block: suspend () -> Unit) {
        scope.launch {
            busy = true
            message = progress
            block()
            busy = false
        }
    }
}
