package com.onevoice.link.ui.screens

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import com.onevoice.link.R
import com.onevoice.link.ui.theme.OneVoiceLinkTheme

private class DashboardStateProvider : PreviewParameterProvider<DashboardUiState> {
    override val values = sequenceOf(
        DashboardUiState(
            EngineStage.NOT_INITIALIZED, busy = false,
            message = UiMessage(R.string.msg_idle), micGranted = true,
        ),
        DashboardUiState(
            EngineStage.NOT_INITIALIZED, busy = true,
            message = UiMessage(R.string.msg_initializing), micGranted = true,
        ),
        DashboardUiState(
            EngineStage.INITIALIZED, busy = false,
            message = UiMessage(R.string.msg_initialized), micGranted = true,
        ),
        DashboardUiState(
            EngineStage.RUNNING, busy = false,
            message = UiMessage(R.string.msg_live), micGranted = true,
        ),
        DashboardUiState(
            EngineStage.NOT_INITIALIZED, busy = false,
            message = UiMessage(R.string.msg_native_error, "QNN backend load failed", MessageKind.Error),
            micGranted = true,
        ),
        DashboardUiState(
            EngineStage.INITIALIZED, busy = false,
            message = UiMessage(R.string.msg_initialized), micGranted = false,
        ),
        DashboardUiState(
            EngineStage.INITIALIZED, busy = false,
            message = UiMessage(R.string.msg_initialized), micGranted = false,
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
            state = DashboardUiState(
                EngineStage.RUNNING, busy = false,
                message = UiMessage(R.string.msg_live), micGranted = true,
            ),
            onInitialize = {}, onToggleTranslation = {}, onRequestMic = {},
        )
    }
}
