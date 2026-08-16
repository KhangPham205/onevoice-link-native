package com.onevoice.link.ui.screens

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import com.onevoice.link.R
import com.onevoice.link.core.ExecutionPath
import com.onevoice.link.core.ModelLoadReport
import com.onevoice.link.core.StageSpec
import com.onevoice.link.core.StageState
import com.onevoice.link.core.StageStatus
import com.onevoice.link.core.TranslationDirection
import com.onevoice.link.ui.theme.OneVoiceLinkTheme

// Hybrid keeps only the continuous-contract QNN stages resident. LID and
// whole-utterance Kokoro are host-side components and do not appear as contexts.
private val SPECS = listOf(
    StageSpec(
        "asr",
        "ASR · GIPFormer-65M RNN-T vi",
        "vi-en/asr_gipformer_encoder.bin",
        "graph_oa00nu3e",
        157_634_560,
    ),
    StageSpec(
        "mt_encoder",
        "MT encoder · opus-mt",
        "vi-en/mt_encoder.bin",
        "opus_mt_encoder",
        100_048_896,
    ),
    StageSpec(
        "mt_decoder",
        "MT decoder · opus-mt",
        "vi-en/mt_decoder.bin",
        "opus_mt_decoder",
        156_549_120,
    ),
)

private fun report(ms: Double) = ModelLoadReport(
    stage = "", ok = true, loadMs = ms, sizeBytes = 0, graphName = "",
    socModel = 43, buildId = "v2.45.0", inputs = emptyList(), outputs = emptyList(),
    error = null,
)

private val PENDING = SPECS.map { StageStatus(it) }
private val ALL_LOADED = SPECS.mapIndexed { index, spec ->
    StageStatus(spec, StageState.LOADED, report(180.0 + index * 45))
}
private val PARTIAL = listOf(
    StageStatus(SPECS[0], StageState.LOADED, report(292.0)),
    StageStatus(SPECS[1], StageState.LOADING),
    StageStatus(SPECS[2], StageState.PENDING),
)
private val MISSING = listOf(
    StageStatus(SPECS[0], StageState.MISSING),
    StageStatus(SPECS[1], StageState.PENDING),
    StageStatus(SPECS[2], StageState.PENDING),
)
private val WRONG_CHIP = listOf(
    StageStatus(SPECS[0], StageState.LOADED, report(292.0)),
    StageStatus(
        SPECS[1],
        StageState.FAILED,
        null,
        "contextCreateFromBinary failed (wrong Hexagon arch for this phone)",
    ),
)

private const val ROOT = "/data/user/0/com.onevoice.link/files/models/npu"

private fun base(
    stage: EngineStage,
    message: UiMessage,
    stages: List<StageStatus>,
    busy: Boolean = false,
    micGranted: Boolean = true,
    micPermanentlyDenied: Boolean = false,
    direction: TranslationDirection? = null,
    confidence: Float? = null,
    executionPath: ExecutionPath? = null,
    transcript: String? = null,
    translation: String? = null,
) = DashboardUiState(
    stage = stage,
    busy = busy,
    message = message,
    micGranted = micGranted,
    micPermanentlyDenied = micPermanentlyDenied,
    supportsLiveTranslation = true,
    transcript = transcript,
    translation = translation,
    replayAvailable = !translation.isNullOrBlank(),
    detectedDirection = direction,
    lidConfidence = confidence,
    executionPath = executionPath,
    stages = stages,
    targetSoc = "Snapdragon 8 Gen 2",
    hexagon = "v73",
    modelRootPath = ROOT,
)

private class DashboardStateProvider : PreviewParameterProvider<DashboardUiState> {
    override val values = sequenceOf(
        base(
            EngineStage.RUNNING,
            UiMessage(R.string.msg_live_auto),
            ALL_LOADED,
            direction = TranslationDirection.VI_TO_EN,
            confidence = 0.97f,
            executionPath = ExecutionPath.HYBRID_NPU,
            transcript = "Xin chào, rất vui được gặp bạn.",
            translation = "Hello, it is nice to meet you.",
        ),
        base(
            EngineStage.RUNNING,
            UiMessage(R.string.msg_live_auto),
            ALL_LOADED,
            direction = TranslationDirection.EN_TO_VI,
            confidence = 0.93f,
            executionPath = ExecutionPath.CPU_FALLBACK,
            transcript = "Where is the nearest station?",
            translation = "Nhà ga gần nhất ở đâu?",
        ),
        base(
            EngineStage.NOT_INITIALIZED,
            UiMessage(R.string.msg_idle_hybrid_auto),
            PENDING,
        ),
        base(
            EngineStage.NOT_INITIALIZED,
            UiMessage(R.string.msg_initializing_hybrid_auto),
            PARTIAL,
            busy = true,
        ),
        base(
            EngineStage.INITIALIZED,
            UiMessage(R.string.msg_initialized_hybrid_auto),
            ALL_LOADED,
        ),
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
        base(
            EngineStage.INITIALIZED,
            UiMessage(R.string.msg_initialized_hybrid_auto),
            ALL_LOADED,
            micGranted = false,
        ),
        base(
            EngineStage.INITIALIZED,
            UiMessage(R.string.msg_initialized_hybrid_auto),
            ALL_LOADED,
            micGranted = false,
            micPermanentlyDenied = true,
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
                UiMessage(R.string.msg_live_auto),
                ALL_LOADED,
                direction = TranslationDirection.VI_TO_EN,
                confidence = 0.96f,
                executionPath = ExecutionPath.HYBRID_NPU,
                transcript = "Tôi cần một phiên dịch hoàn toàn ngoại tuyến.",
                translation = "I need a completely offline interpreter.",
            ),
            onInitialize = {},
            onToggleTranslation = {},
            onRequestMic = {},
        )
    }
}
