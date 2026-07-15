package com.onevoice.link

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.onevoice.link.core.EngineBackend
import com.onevoice.link.core.QnnEngineBackend
import com.onevoice.link.hf.HfEngineBackend
import com.onevoice.link.ui.screens.BackendKind
import com.onevoice.link.ui.screens.DashboardScreen
import com.onevoice.link.ui.theme.OneVoiceLinkTheme
import java.io.File

class MainActivity : ComponentActivity() {

    // Backed by Compose snapshot state so the dashboard recomposes when the
    // RECORD_AUDIO grant changes (initial check + async permission result).
    private var micGranted by mutableStateOf(false)

    // Presentation hint only: after a denial with no rationale available, the
    // system dialog won't show again — the UI tells the user to use settings.
    private var micPermanentlyDenied by mutableStateOf(false)

    private val requestMic =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            micGranted = granted
            if (!granted) {
                micPermanentlyDenied =
                    !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
            }
        }

    /** Both engine implementations; the dashboard switches between them. */
    private var backends: Map<BackendKind, EngineBackend> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        micGranted = hasMicPermission()

        // nativeLibraryDir holds the packaged QNN backend .so; filesDir/models is
        // a stand-in for where a real serialized context binary would live.
        val nativeLibDir = applicationInfo.nativeLibraryDir
        val modelDir = File(filesDir, "models").absolutePath

        backends = mapOf(
            BackendKind.QNN to QnnEngineBackend(nativeLibDir, modelDir),
            BackendKind.HF to HfEngineBackend(applicationContext),
        )
        // Emulator images are x86_64 (no Hexagon NPU) — default to the HF path
        // there; real Snapdragon devices default to the QNN .bin path.
        val defaultBackend =
            if (Build.SUPPORTED_ABIS.firstOrNull()?.startsWith("x86") == true) BackendKind.HF
            else BackendKind.QNN

        enableEdgeToEdge()
        setContent {
            OneVoiceLinkTheme {
                DashboardScreen(
                    micGranted = micGranted,
                    micPermanentlyDenied = micPermanentlyDenied,
                    backends = backends,
                    defaultBackend = defaultBackend,
                    onRequestMic = {
                        requestMic.launch(Manifest.permission.RECORD_AUDIO)
                    },
                )
            }
        }

        // Dynamic runtime permission request on launch — mic is required to capture.
        if (!micGranted) {
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onDestroy() {
        // Engine cleanup wired directly into the Activity lifecycle. Safe to call
        // unconditionally: both backends no-op when never created/initialized.
        backends.values.forEach { runCatching { it.destroy() } }
        super.onDestroy()
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
}
