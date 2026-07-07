package com.onevoice.link

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.onevoice.link.core.OneVoiceNative
import com.onevoice.link.ui.theme.OneVoiceLinkTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Lifecycle stages of the native engine, driven by [OneVoiceNative].
 * The [label] is what the dashboard renders as the primary status text.
 */
private enum class EngineStage(val label: String, val color: Color) {
    NOT_INITIALIZED("Not Initialized", Color(0xFF9E9E9E)),
    INITIALIZED("Initialized", Color(0xFFFFB300)),
    RUNNING("Running / Translating", Color(0xFF00C853)),
}

class MainActivity : ComponentActivity() {

    // Backed by Compose snapshot state so the dashboard recomposes when the
    // RECORD_AUDIO grant changes (initial check + async permission result).
    private var micGranted by mutableStateOf(false)

    private val requestMic =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            micGranted = granted
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        micGranted = hasMicPermission()

        // nativeLibraryDir holds the packaged QNN backend .so; filesDir/models is
        // a stand-in for where a real serialized context binary would live.
        val nativeLibDir = applicationInfo.nativeLibraryDir
        val modelDir = File(filesDir, "models").absolutePath

        enableEdgeToEdge()
        setContent {
            OneVoiceLinkTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Dashboard(
                        micGranted = micGranted,
                        nativeLibDir = nativeLibDir,
                        modelDir = modelDir,
                        onRequestMic = {
                            requestMic.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }

        // Dynamic runtime permission request on launch — mic is required to capture.
        if (!micGranted) {
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onDestroy() {
        // Native cleanup wired directly into the Activity lifecycle. Safe to call
        // unconditionally: the C++ side no-ops when the engine was never created.
        runCatching { OneVoiceNative.nativeDestroyEngine() }
        super.onDestroy()
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
}

// ============================================================================
// Dashboard UI
// ============================================================================

@Composable
private fun Dashboard(
    micGranted: Boolean,
    nativeLibDir: String,
    modelDir: String,
    onRequestMic: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    var stage by remember { mutableStateOf(EngineStage.NOT_INITIALIZED) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Idle — initialize the engine to begin.") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Header()

        StatusCard(stage = stage, busy = busy, message = message)

        MicPermissionRow(granted = micGranted, onRequestMic = onRequestMic)

        Spacer(Modifier.height(4.dp))

        // ---- Action: Initialize -------------------------------------------
        Button(
            onClick = {
                scope.launch {
                    busy = true
                    message = "Initializing audio + QNN context…"
                    val ok = withContext(Dispatchers.IO) {
                        runCatching { OneVoiceNative.initialize(nativeLibDir, modelDir) }
                    }
                    ok.onSuccess { initialized ->
                        stage = if (initialized) EngineStage.INITIALIZED else EngineStage.NOT_INITIALIZED
                        message = if (initialized) "Engine initialized. Ready to translate."
                        else "Initialization failed (check QNN context / model path)."
                    }.onFailure {
                        stage = EngineStage.NOT_INITIALIZED
                        message = "Native error: ${it.message ?: it.javaClass.simpleName}"
                    }
                    busy = false
                }
            },
            enabled = !busy && stage == EngineStage.NOT_INITIALIZED,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text("Initialize Engine", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        }

        // ---- Action: Toggle translation -----------------------------------
        val running = stage == EngineStage.RUNNING
        FilledTonalButton(
            onClick = {
                scope.launch {
                    busy = true
                    if (running) {
                        message = "Stopping stream…"
                        withContext(Dispatchers.IO) {
                            runCatching { OneVoiceNative.nativeStopEngine() }
                        }
                        stage = EngineStage.INITIALIZED
                        message = "Stopped. Engine still initialized."
                    } else {
                        message = "Starting full-duplex translation…"
                        val ok = withContext(Dispatchers.IO) {
                            runCatching { OneVoiceNative.nativeStartEngine() }
                        }
                        ok.onSuccess { started ->
                            if (started) {
                                stage = EngineStage.RUNNING
                                message = "Live. Speak now — translating in realtime."
                            } else {
                                message = "Failed to start the audio stream."
                            }
                        }.onFailure {
                            message = "Native error: ${it.message ?: it.javaClass.simpleName}"
                        }
                    }
                    busy = false
                }
            },
            enabled = !busy && stage != EngineStage.NOT_INITIALIZED && (running || micGranted),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(14.dp),
            colors = if (running) {
                ButtonDefaults.filledTonalButtonColors(
                    containerColor = Color(0xFFB00020),
                    contentColor = Color.White,
                )
            } else {
                ButtonDefaults.filledTonalButtonColors()
            },
        ) {
            Text(
                if (running) "Stop Translating" else "Start Translating",
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
            )
        }

        if (stage != EngineStage.NOT_INITIALIZED && !micGranted && !running) {
            Text(
                "Grant microphone access to start the stream.",
                color = MaterialTheme.colorScheme.error,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun Header() {
    Column {
        Text(
            "OneVoice Link",
            fontSize = 30.sp,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            "S2ST • Snapdragon Hexagon HTP • ultra-low latency",
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusCard(stage: EngineStage, busy: Boolean, message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                "ENGINE STATUS",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 2.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(
                    Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(stage.color),
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    stage.label,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (busy) {
                    Spacer(Modifier.size(12.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                message,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MicPermissionRow(granted: Boolean, onRequestMic: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (granted) Color(0xFF00C853) else Color(0xFFB00020)),
            )
            Spacer(Modifier.size(10.dp))
            Text(
                if (granted) "Microphone granted" else "Microphone denied",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        if (!granted) {
            FilledTonalButton(onClick = onRequestMic, shape = RoundedCornerShape(10.dp)) {
                Text("Grant")
            }
        }
    }
}
