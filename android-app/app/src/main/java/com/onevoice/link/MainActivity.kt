package com.onevoice.link

import android.Manifest
import android.content.pm.PackageManager
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
import com.onevoice.link.core.NpuEngine
import com.onevoice.link.hf.HfEngineBackend
import com.onevoice.link.ui.screens.BackendKind
import com.onevoice.link.ui.screens.DashboardScreen
import com.onevoice.link.ui.theme.OneVoiceLinkTheme

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

    /**
     * Engine instances are owned by the dashboard controller. Keeping the map in
     * the Activity only makes their lifetime explicit and prevents recreation on
     * every Compose recomposition.
     */
    private var backends: Map<BackendKind, EngineBackend> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        micGranted = hasMicPermission()

        // Default to the complete offline path. QNN remains available as an
        // explicit load-and-verify backend until its Android host glue exists.
        backends = mapOf(
            BackendKind.HF to HfEngineBackend(applicationContext),
            BackendKind.QNN to NpuEngine(applicationContext),
        )

        enableEdgeToEdge()
        setContent {
            OneVoiceLinkTheme {
                DashboardScreen(
                    micGranted = micGranted,
                    micPermanentlyDenied = micPermanentlyDenied,
                    backends = backends,
                    defaultBackend = BackendKind.HF,
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
        // Activity teardown is the final safety net if Compose cancels its
        // remembered scope before DisposableEffect cleanup finishes. Backend
        // destroy() is idempotent, so a controller-initiated teardown is safe.
        backends.values.distinct().forEach { runCatching { it.destroy() } }
        backends = emptyMap()
        super.onDestroy()
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
}
