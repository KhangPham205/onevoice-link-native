package com.onevoice.link.ui.screens

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import com.onevoice.link.R
import com.onevoice.link.core.ModelLoadReport
import com.onevoice.link.core.StageSpec
import com.onevoice.link.core.StageState
import com.onevoice.link.core.StageStatus
import com.onevoice.link.core.TranslationDirection
import com.onevoice.link.ui.theme.OneVoiceLinkTheme

// Sizes/graph names mirror the real vi-en cascade so the previews lay out
// against realistic string lengths (a 1.15 GB ASR row is the widest case).
private val SPECS = listOf(
    StageSpec("asr", "ASR · Parakeet-CTC vi", "vi-en/asr_parakeet_vi.bin", "graph_08xlh5ri", 1_207_231_000),
    StageSpec("mt_encoder", "MT encoder · opus-mt", "vi-en/mt_encoder.bin", "opus_mt_encoder", 100_050_000),
    StageSpec("mt_decoder", "MT decoder · opus-mt", "vi-en/mt_decoder.bin", "opus_mt_decoder", 156_600_000),
    StageSpec("tts_encoder", "TTS encoder · Kokoro", "vi-en/tts_encoder.bin", "graph_5l17io1h", 67_800_000),
    StageSpec("tts_decoder", "TTS decoder · Kokoro", "vi-en/tts_decoder.bin", "graph_93073co3", 115_000_000),
)

private fun report(ms: Double) = ModelLoadReport(
    stage = "", ok = true, loadMs = ms, sizeBytes = 0, graphName = "",
    socModel = 43, buildId = "v2.45.0", inputs = emptyList(), outputs = emptyList(),
    error = null,
)

private val PENDING = SPECS.map { StageStatus(it) }
private val ALL_LOADED = SPECS.mapIndexed { i, s ->
    StageStatus(s, StageState.LOADED, report(180.0 + i * 45))
}
private val PARTIAL = listOf(
    StageStatus(SPECS[0], StageState.LOADED, report(742.0)),
    StageStatus(SPECS[1], StageState.LOADING),
    StageStatus(SPECS[2], StageState.PENDING),
    StageStatus(SPECS[3], StageState.PENDING),
    StageStatus(SPECS[4], StageState.PENDING),
)
private val MISSING = listOf(
    StageStatus(SPECS[0], StageState.MISSING),
    StageStatus(SPECS[1], StageState.PENDING),
    StageStatus(SPECS[2], StageState.PENDING),
    StageStatus(SPECS[3], StageState.PENDING),
    StageStatus(SPECS[4], StageState.PENDING),
)
private val WRONG_CHIP = listOf(
    StageStatus(SPECS[0], StageState.LOADED, report(742.0)),
    StageStatus(
        SPECS[1], StageState.FAILED, null,
        "contextCreateFromBinary failed (wrong Hexagon arch for this phone)",
    ),
)

private const val ROOT = "/storage/emulated/0/Android/data/com.onevoice.link/files/models/npu"

private fun base(
    stage: EngineStage,
    message: UiMessage,
    stages: List<StageStatus>,
    busy: Boolean = false,
    micGranted: Boolean = true,
    micPermanentlyDenied: Boolean = false,
    direction: TranslationDirection = TranslationDirection.VI_TO_EN,
    unavailableReason: String? = null,
    backend: BackendKind = BackendKind.QNN,
    transcript: String? = null,
    translation: String? = null,
) = DashboardUiState(
    stage = stage,
    busy = busy,
    message = message,
    micGranted = micGranted,
    micPermanentlyDenied = micPermanentlyDenied,
    selectedBackend = backend,
    backendDisplayName = if (backend == BackendKind.QNN) {
        "QNN / Hexagon HTP · live ASR → MT → TTS"
    } else {
        "Offline CPU · complete ASR → MT → TTS"
    },
    supportsLiveTranslation = true,
    transcript = transcript,
    translation = translation,
    replayAvailable = !translation.isNullOrBlank(),
    direction = direction,
    stages = stages,
    unavailableDirections = if (backend == BackendKind.QNN) {
        setOf(TranslationDirection.EN_TO_VI)
    } else {
        emptySet()
    },
    directionUnavailableReason = unavailableReason,
    targetSoc = "Snapdragon 8 Gen 2",
    hexagon = "v73",
    modelRootPath = ROOT,
)

private class DashboardStateProvider : PreviewParameterProvider<DashboardUiState> {
    override val values = sequenceOf(
        base(
            EngineStage.RUNNING,
            UiMessage(R.string.msg_live),
            emptyList(),
            backend = BackendKind.HF,
            transcript = "Xin chào, rất vui được gặp bạn.",
            translation = "Hello, it is nice to meet you.",
        ),
        base(EngineStage.NOT_INITIALIZED, UiMessage(R.string.msg_idle), PENDING),
        base(EngineStage.NOT_INITIALIZED, UiMessage(R.string.msg_initializing), PARTIAL, busy = true),
        base(EngineStage.INITIALIZED, UiMessage(R.string.msg_initialized), ALL_LOADED),
        base(EngineStage.RUNNING, UiMessage(R.string.msg_live), ALL_LOADED),
        base(
            EngineStage.NOT_INITIALIZED,
            UiMessage(R.string.msg_backend_error, "Missing .bin under $ROOT", MessageKind.Error),
            MISSING,
        ),
        base(
            EngineStage.NOT_INITIALIZED,
            UiMessage(R.string.msg_backend_error, "wrong Hexagon arch", MessageKind.Error),
            WRONG_CHIP,
        ),
        // EN→VI selected: no English ASR .bin ships, so the direction is locked.
        base(
            EngineStage.NOT_INITIALIZED,
            UiMessage(R.string.msg_idle),
            emptyList(),
            direction = TranslationDirection.EN_TO_VI,
            unavailableReason = "EN → VI needs an English ASR .bin — not in the model package yet.",
        ),
        base(EngineStage.INITIALIZED, UiMessage(R.string.msg_initialized), ALL_LOADED, micGranted = false),
        base(
            EngineStage.INITIALIZED, UiMessage(R.string.msg_initialized), ALL_LOADED,
            micGranted = false, micPermanentlyDenied = true,
        ),
    )
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun DashboardPreview(
    @PreviewParameter(DashboardStateProvider::class) state: DashboardUiState,
) {
    OneVoiceLinkTheme(dynamicColor = false) {
        DashboardContent(state, onInitialize = {}, onToggleTranslation = {}, onRequestMic = {})
    }
}

@Preview(name = "Large font", showBackground = true, fontScale = 1.5f)
@Composable
private fun DashboardLargeFontPreview() {
    OneVoiceLinkTheme(dynamicColor = false) {
        DashboardContent(
            state = base(
                EngineStage.RUNNING,
                UiMessage(R.string.msg_live),
                emptyList(),
                backend = BackendKind.HF,
                transcript = "Tôi cần một phiên dịch hoàn toàn ngoại tuyến.",
                translation = "I need a completely offline interpreter.",
            ),
            onInitialize = {}, onToggleTranslation = {}, onRequestMic = {},
        )
    }
}
