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
import androidx.lifecycle.lifecycleScope
import com.onevoice.link.core.NpuEngine
import com.onevoice.link.core.NpuTtsMode
import com.onevoice.link.ui.OneVoiceApp
import com.onevoice.link.ui.screens.EngineController
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
            micPermanentlyDenied = !granted &&
                !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
        }

    // Nearby-device access is requested only from Settings. Listing already
    // connected endpoints does not need scan/advertise permission.
    private var bluetoothConnectGranted by mutableStateOf(false)

    private val requestBluetoothConnect =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            bluetoothConnectGranted = granted || !requiresBluetoothConnectPermission()
        }

    /** Sole lifecycle owner; teardown always leaves the main thread immediately. */
    private var engineController: EngineController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        micGranted = hasMicPermission()
        bluetoothConnectGranted = hasBluetoothConnectPermission()

        // Hybrid is the only user-facing pipeline. It automatically detects the
        // source language per utterance; CPU execution is an internal degraded
        // path, never a mode the user has to choose.
        val hybridEngine = NpuEngine(
            applicationContext,
            NpuTtsMode.CPU_WHOLE_UTTERANCE,
        )
        val controller = EngineController(hybridEngine, lifecycleScope)
        engineController = controller

        enableEdgeToEdge()
        setContent {
            OneVoiceLinkTheme {
                OneVoiceApp(
                    micGranted = micGranted,
                    micPermanentlyDenied = micPermanentlyDenied,
                    bluetoothConnectGranted = bluetoothConnectGranted,
                    engine = hybridEngine,
                    engineController = controller,
                    onRequestMic = {
                        requestMic.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    onRequestBluetoothConnect = {
                        if (requiresBluetoothConnectPermission()) {
                            requestBluetoothConnect.launch(Manifest.permission.BLUETOOTH_CONNECT)
                        } else {
                            bluetoothConnectGranted = true
                        }
                    },
                )
            }
        }

        // Dynamic runtime permission request on launch — mic is required to capture.
        if (!micGranted) {
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onResume() {
        super.onResume()
        micGranted = hasMicPermission()
        bluetoothConnectGranted = hasBluetoothConnectPermission()
        if (micGranted) micPermanentlyDenied = false
    }

    override fun onStop() {
        // No foreground service/notification exists, so capture must not remain
        // active after the Activity loses visibility. Models remain resident.
        engineController?.pauseLiveTranslation()
        super.onStop()
    }

    override fun onDestroy() {
        // EngineController is the only teardown owner and performs blocking
        // worker joins/native release on its private IO cleanup scope.
        engineController?.shutdown()
        engineController = null
        super.onDestroy()
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasBluetoothConnectPermission(): Boolean =
        !requiresBluetoothConnectPermission() ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private fun requiresBluetoothConnectPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
}
