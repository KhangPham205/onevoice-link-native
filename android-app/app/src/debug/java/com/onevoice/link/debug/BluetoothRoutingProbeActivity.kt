package com.onevoice.link.debug

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.sin

/**
 * Debug-only, SDK-only probe for Bluetooth microphone identity and routing.
 *
 * Start manually from the debug APK or through ADB:
 *   adb shell am start -n \
 *     com.onevoice.link/.debug.BluetoothRoutingProbeActivity \
 *     --ez autorun true
 *
 * Every result is duplicated to the screen and logcat tag [TAG]. This class is
 * intentionally isolated from MainActivity and all OneVoice inference code.
 */
class BluetoothRoutingProbeActivity : Activity() {
    private lateinit var output: TextView
    private lateinit var runButton: Button
    private val running = AtomicBoolean(false)
    private var startAfterPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        runButton = Button(this).apply {
            text = "Run all Bluetooth routing tests"
            setOnClickListener { requestPermissionsOrRun() }
        }
        output = TextView(this).apply {
            textSize = 13f
            setTextIsSelectable(true)
            setPadding(24, 16, 24, 32)
            text = "Press the button to run T1-T7.\n"
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                runButton,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                ScrollView(this@BluetoothRoutingProbeActivity).apply {
                    addView(output)
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ),
            )
        }
        setContentView(column)

        if (intent.getBooleanExtra(EXTRA_AUTORUN, false)) {
            runButton.post { requestPermissionsOrRun() }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS || !startAfterPermission) return
        startAfterPermission = false
        val missing = requiredPermissions().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startProbe()
        else emit("PROBE_ABORT missing runtime permissions: ${missing.joinToString()}")
    }

    private fun requestPermissionsOrRun() {
        val missing = requiredPermissions().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startProbe()
        } else {
            startAfterPermission = true
            requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }

    private fun requiredPermissions(): List<String> = listOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.BLUETOOTH_CONNECT,
    )

    private fun startProbe() {
        if (!running.compareAndSet(false, true)) return
        runButton.isEnabled = false
        output.text = ""
        thread(name = "onevoice-bluetooth-probe") {
            try {
                runProbe()
            } catch (failure: Throwable) {
                emit(
                    "PROBE_FATAL ${failure.javaClass.name}: " +
                        (failure.message ?: "no message"),
                )
                Log.e(TAG, "Probe failed", failure)
            } finally {
                running.set(false)
                runOnUiThread { runButton.isEnabled = true }
            }
        }
    }

    @Suppress("MissingPermission")
    private fun runProbe() {
        val audioManager = requireNotNull(getSystemService(AudioManager::class.java))
        val originalMode = audioManager.mode
        val originalCommunicationDevice = audioManager.communicationDevice
        emit("PROBE_BEGIN elapsedRealtimeMs=${SystemClock.elapsedRealtime()}")
        emit("DEVICE sdk=${android.os.Build.VERSION.SDK_INT} model=${android.os.Build.MODEL}")
        emit("ORIGINAL mode=$originalMode communication=${describe(originalCommunicationDevice)}")

        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            val communication = testT1(audioManager)
            val communicationHeadsets = communication
                .filter(::isBluetoothCommunicationOutput)
            val mediaHeadsets = audioManager
                .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .filter { it.isSink && isMediaBluetoothOutput(it) }
                .sortedWith(
                    compareBy<AudioDeviceInfo> { it.productName.toString().lowercase(Locale.ROOT) }
                        .thenBy { it.address },
                )
                .distinctBy(::physicalKey)
            val candidatesByPhysicalId = linkedMapOf<String, AudioDeviceInfo>()
            // Media outputs make non-selectable paired headsets visible. A
            // communication-list entry replaces the media entry for the same
            // physical address because it is the documented API input.
            mediaHeadsets.forEach { candidatesByPhysicalId[physicalKey(it)] = it }
            communicationHeadsets.forEach { candidatesByPhysicalId[physicalKey(it)] = it }
            val candidates = candidatesByPhysicalId.values
                .sortedWith(
                    compareBy<AudioDeviceInfo> { it.productName.toString().lowercase(Locale.ROOT) }
                        .thenBy { it.address },
                )
                .take(2)

            candidates.forEachIndexed { index, device ->
                emit(
                    "HEADSET_${'A' + index} ${describe(device)} " +
                        "inAvailableCommunicationDevices=" +
                        communicationHeadsets.any { samePhysicalDevice(it, device) },
                )
            }
            if (candidates.isEmpty()) {
                val reason = "no Bluetooth headset endpoint was visible"
                (2..7).forEach { emit("T$it SKIP $reason") }
                return
            }

            val t2 = testT2(audioManager, candidates)
            val routable = t2.filter { it.routeMatches }.map(CaptureObservation::requestedDevice)
            if (routable.size >= 2) {
                testT3(audioManager, routable[0], routable[1])
                testT4(audioManager, routable[0], routable[1])
            } else {
                emit(
                    "T3 SKIP fewer than two endpoints accepted and matched an AudioRecord route; " +
                        "routable=${routable.size}",
                )
                emit(
                    "T4 SKIP fewer than two independently routable microphone endpoints; " +
                        "routable=${routable.size}",
                )
            }
            testT5(t2)
            if (routable.isNotEmpty()) {
                testT6(audioManager, routable.first())
            } else {
                emit("T6 SKIP no headset produced a verified AudioRecord route in T2")
            }
            if (mediaHeadsets.size >= 2) {
                testT7(audioManager, mediaHeadsets[0], mediaHeadsets[1])
            } else {
                emit("T7 SKIP fewer than two Bluetooth media outputs; found ${mediaHeadsets.size}")
            }
        } finally {
            runCatching { audioManager.clearCommunicationDevice() }
            if (originalCommunicationDevice != null) {
                val restored = runCatching {
                    audioManager.setCommunicationDevice(originalCommunicationDevice)
                }.getOrDefault(false)
                emit("RESTORE communicationResult=$restored device=${describe(originalCommunicationDevice)}")
            }
            runCatching { audioManager.mode = originalMode }
            emit("PROBE_END elapsedRealtimeMs=${SystemClock.elapsedRealtime()}")
        }
    }

    @Suppress("MissingPermission")
    private fun testT1(audioManager: AudioManager): List<AudioDeviceInfo> {
        emit("T1 BEGIN getAvailableCommunicationDevices")
        val first = audioManager.availableCommunicationDevices
        first.forEachIndexed { index, device ->
            emit("T1 device[$index] ${describe(device)} addressVisible=${visibleAddress(device.address) != null}")
        }
        SystemClock.sleep(250)
        val second = audioManager.availableCommunicationDevices
        val sameSnapshot = first.map(::snapshotKey) == second.map(::snapshotKey)
        emit("T1 repeatedSnapshotStable=$sameSnapshot count1=${first.size} count2=${second.size}")
        if (first.any { isBluetoothCommunicationOutput(it) && visibleAddress(it.address) == null }) {
            emit(
                "T1 maskedAddress=true productName+id reconnect stability NOT_MEASURED; " +
                    "the run did not perform a controlled disconnect/reconnect",
            )
        } else {
            emit("T1 maskedAddress=false for Bluetooth communication devices")
        }
        val mediaOutputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.isSink && isMediaBluetoothOutput(it) }
        mediaOutputs.forEachIndexed { index, device ->
            emit("T1 mediaOutput[$index] ${describe(device)}")
        }
        emit("T1 END")
        return first
    }

    @Suppress("MissingPermission")
    private fun testT2(
        audioManager: AudioManager,
        headsets: List<AudioDeviceInfo>,
    ): List<CaptureObservation> {
        emit("T2 BEGIN intentional microphone routing")
        val observations = headsets.mapIndexed { index, device ->
            val label = "${'A' + index}"
            runCatching {
                audioManager.clearCommunicationDevice()
                val setStart = SystemClock.elapsedRealtimeNanos()
                val accepted = audioManager.setCommunicationDevice(device)
                emit("T2 headset=$label setCommunicationDevice=$accepted requested=${describe(device)}")
                if (!accepted) {
                    CaptureObservation(label, device, null, 0, 0, 0, 0, "set rejected")
                } else {
                    val observation = captureForSeconds(label, device, CAPTURE_SECONDS)
                    val callMs = (SystemClock.elapsedRealtimeNanos() - setStart) / 1_000_000
                    emit(
                        "T2 headset=$label routeMatch=${observation.routeMatches} " +
                            "elapsedMs=$callMs sampleRate=${observation.sampleRate} " +
                            "readSamples=${observation.readSamples} nonZero=${observation.nonZeroSamples} " +
                            "routed=${describe(observation.routedDevice)} error=${observation.error}",
                    )
                    observation
                }
            }.getOrElse { failure ->
                emit("T2 headset=$label EXCEPTION ${failure.javaClass.name}: ${failure.message}")
                CaptureObservation(label, device, null, 0, 0, 0, 0, failure.toString())
            }
        }
        audioManager.clearCommunicationDevice()
        emit("T2 END")
        return observations
    }

    @Suppress("MissingPermission")
    private fun testT3(
        audioManager: AudioManager,
        headsetA: AudioDeviceInfo,
        headsetB: AudioDeviceInfo,
    ) {
        emit("T3 BEGIN switch A->B iterations=$SWITCH_ITERATIONS")
        val timings = mutableListOf<Long>()
        repeat(SWITCH_ITERATIONS) { iteration ->
            val primed = primeRoute(audioManager, headsetA)
            if (!primed) {
                emit("T3 iteration=${iteration + 1} FAIL could not prime headset A")
                return@repeat
            }
            val startNs = SystemClock.elapsedRealtimeNanos()
            val accepted = audioManager.setCommunicationDevice(headsetB)
            if (!accepted) {
                emit("T3 iteration=${iteration + 1} FAIL setCommunicationDevice(B)=false")
                return@repeat
            }
            val measured = measureUntilRouted(headsetB, ROUTE_TIMEOUT_MS)
            if (measured == null) {
                emit("T3 iteration=${iteration + 1} TIMEOUT timeoutMs=$ROUTE_TIMEOUT_MS")
            } else {
                val elapsedMs = (measured.successNs - startNs) / 1_000_000
                timings += elapsedMs
                emit(
                    "T3 iteration=${iteration + 1} elapsedMs=$elapsedMs " +
                        "sampleRate=${measured.sampleRate} firstRead=${measured.firstRead} " +
                        "nonZero=${measured.nonZeroSamples} routed=${describe(measured.routedDevice)}",
                )
            }
        }
        if (timings.isEmpty()) {
            emit("T3 SUMMARY success=0/$SWITCH_ITERATIONS min=NA median=NA max=NA")
        } else {
            val sorted = timings.sorted()
            val median = if (sorted.size % 2 == 1) {
                sorted[sorted.size / 2].toDouble()
            } else {
                (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
            }
            emit(
                "T3 SUMMARY success=${timings.size}/$SWITCH_ITERATIONS " +
                    "minMs=${sorted.first()} medianMs=${"%.1f".format(Locale.US, median)} " +
                    "maxMs=${sorted.last()}",
            )
        }
        audioManager.clearCommunicationDevice()
        emit("T3 END")
    }

    @Suppress("MissingPermission")
    private fun testT4(
        audioManager: AudioManager,
        headsetA: AudioDeviceInfo,
        headsetB: AudioDeviceInfo,
    ) {
        emit("T4 BEGIN two simultaneous AudioRecord instances")
        audioManager.clearCommunicationDevice()
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
        inputs.forEachIndexed { index, device -> emit("T4 input[$index] ${describe(device)}") }
        val inputA = inputs.firstOrNull { samePhysicalDevice(headsetA, it) && it.isSource }
        val inputB = inputs.firstOrNull { samePhysicalDevice(headsetB, it) && it.isSource }
        if (inputA == null || inputB == null) {
            emit(
                "T4 SKIP could not map both communication outputs to input endpoints: " +
                    "inputA=${describe(inputA)} inputB=${describe(inputB)}",
            )
            emit("T4 END")
            return
        }

        var recordA: AudioRecord? = null
        var recordB: AudioRecord? = null
        try {
            recordA = buildVoiceRecord()
            recordB = buildVoiceRecord()
            val preferredA = recordA.setPreferredDevice(inputA)
            val preferredB = recordB.setPreferredDevice(inputB)
            emit("T4 setPreferred A=$preferredA B=$preferredB")
            recordA.startRecording()
            emit("T4 start A=OK")
            recordB.startRecording()
            emit("T4 start B=OK")

            val resultA = ConcurrentRead()
            val resultB = ConcurrentRead()
            val workerA = thread(name = "probe-record-a") { readBriefly(recordA, resultA) }
            val workerB = thread(name = "probe-record-b") { readBriefly(recordB, resultB) }
            workerA.join(CONCURRENT_READ_TIMEOUT_MS)
            workerB.join(CONCURRENT_READ_TIMEOUT_MS)
            emit(
                "T4 A preferred=${describe(recordA.preferredDevice)} " +
                    "routed=${describe(recordA.routedDevice)} read=${resultA.readSamples} " +
                    "nonZero=${resultA.nonZeroSamples} error=${resultA.error}",
            )
            emit(
                "T4 B preferred=${describe(recordB.preferredDevice)} " +
                    "routed=${describe(recordB.routedDevice)} read=${resultB.readSamples} " +
                    "nonZero=${resultB.nonZeroSamples} error=${resultB.error}",
            )
            emit(
                "T4 routedToDistinctPhysicalDevices=" +
                    (
                        recordA.routedDevice != null && recordB.routedDevice != null &&
                            !samePhysicalDevice(recordA.routedDevice, recordB.routedDevice)
                        ),
            )
        } catch (failure: Throwable) {
            emit("T4 EXCEPTION ${failure.javaClass.name}: ${failure.message}")
            Log.e(TAG, "T4 failed", failure)
        } finally {
            closeRecord(recordB)
            closeRecord(recordA)
            audioManager.clearCommunicationDevice()
            emit("T4 END")
        }
    }

    private fun testT5(observations: List<CaptureObservation>) {
        emit("T5 BEGIN AudioRecord sample rates")
        observations.forEach { observation ->
            emit(
                "T5 headset=${observation.label} sampleRate=${observation.sampleRate}Hz " +
                    "requestedRate=UNSPECIFIED encoding=PCM_16BIT channel=MONO",
            )
        }
        emit(
            "T5 NOTE AudioRecord sampleRate is the framework stream rate; this probe does not " +
                "claim the negotiated air codec without Bluetooth codec telemetry",
        )
        emit("T5 END")
    }

    @Suppress("MissingPermission")
    private fun testT6(audioManager: AudioManager, headsetA: AudioDeviceInfo) {
        emit("T6 BEGIN A2DP conflict tone=440Hz headset=A")
        audioManager.clearCommunicationDevice()
        val mediaA = findMediaOutput(audioManager, headsetA)
        if (mediaA == null) {
            emit("T6 SKIP no A2DP/BLE media output mapped to headset A")
            emit("T6 END")
            return
        }
        var track: AudioTrack? = null
        var record: AudioRecord? = null
        var writer: ToneWriter? = null
        try {
            track = buildToneTrack()
            val preferred = track.setPreferredDevice(mediaA)
            writer = ToneWriter(track, 440.0)
            track.play()
            writer.start()
            waitForTrackRoute(track, 2_000)
            val beforeRoute = track.routedDevice
            val beforeHead = track.playbackHeadPosition
            val beforeUnderruns = track.underrunCount
            emit(
                "T6 before preferredAccepted=$preferred routed=${describe(beforeRoute)} " +
                    "head=$beforeHead underruns=$beforeUnderruns",
            )

            val commAccepted = audioManager.setCommunicationDevice(headsetA)
            record = buildVoiceRecord()
            record.startRecording()
            val capture = readRecord(record, 2_000)
            val duringRoute = track.routedDevice
            val duringHead = track.playbackHeadPosition
            val duringUnderruns = track.underrunCount
            emit(
                "T6 during setCommunication=$commAccepted outputRoute=${describe(duringRoute)} " +
                    "head=$duringHead underruns=$duringUnderruns micRoute=${describe(record.routedDevice)} " +
                    "micRead=${capture.first} micNonZero=${capture.second}",
            )

            closeRecord(record)
            record = null
            audioManager.clearCommunicationDevice()
            val recoveryStart = SystemClock.elapsedRealtime()
            var recovered = samePhysicalDevice(mediaA, track.routedDevice)
            while (!recovered && SystemClock.elapsedRealtime() - recoveryStart < OUTPUT_RECOVERY_TIMEOUT_MS) {
                SystemClock.sleep(20)
                recovered = samePhysicalDevice(mediaA, track.routedDevice)
            }
            val recoveryMs = SystemClock.elapsedRealtime() - recoveryStart
            emit(
                "T6 after recoveredToRequestedMedia=$recovered recoveryMs=$recoveryMs " +
                    "routed=${describe(track.routedDevice)} head=${track.playbackHeadPosition} " +
                    "underruns=${track.underrunCount}",
            )
            emit(
                "T6 audibleInterruptionOrQuality=NOT_MEASURED reason=no acoustic loopback or " +
                    "operator response channel; route/head/underrun counters above are measured",
            )
        } catch (failure: Throwable) {
            emit("T6 EXCEPTION ${failure.javaClass.name}: ${failure.message}")
            Log.e(TAG, "T6 failed", failure)
        } finally {
            closeRecord(record)
            writer?.close()
            closeTrack(track)
            audioManager.clearCommunicationDevice()
            emit("T6 END")
        }
    }

    @Suppress("MissingPermission")
    private fun testT7(
        audioManager: AudioManager,
        headsetA: AudioDeviceInfo,
        headsetB: AudioDeviceInfo,
    ) {
        emit("T7 BEGIN split output A=440Hz B=880Hz")
        audioManager.clearCommunicationDevice()
        val mediaA = findMediaOutput(audioManager, headsetA)
        val mediaB = findMediaOutput(audioManager, headsetB)
        if (mediaA == null || mediaB == null) {
            emit("T7 SKIP missing mapped media outputs A=${describe(mediaA)} B=${describe(mediaB)}")
            emit("T7 END")
            return
        }
        var trackA: AudioTrack? = null
        var trackB: AudioTrack? = null
        var writerA: ToneWriter? = null
        var writerB: ToneWriter? = null
        try {
            trackA = buildToneTrack()
            trackB = buildToneTrack()
            val preferredA = trackA.setPreferredDevice(mediaA)
            val preferredB = trackB.setPreferredDevice(mediaB)
            writerA = ToneWriter(trackA, 440.0)
            writerB = ToneWriter(trackB, 880.0)
            emit("T7 LISTEN_NOW durationMs=$T7_TONE_DURATION_MS A=440Hz B=880Hz")
            trackA.play()
            trackB.play()
            writerA.start()
            writerB.start()
            SystemClock.sleep(1_000)
            val routedA = trackA.routedDevice
            val routedB = trackB.routedDevice
            emit(
                "T7 routes preferredAcceptedA=$preferredA preferredAcceptedB=$preferredB " +
                    "trackA=${describe(routedA)} trackB=${describe(routedB)} " +
                    "distinctPhysicalDevices=${!samePhysicalDevice(routedA, routedB)}",
            )
            SystemClock.sleep(T7_TONE_DURATION_MS - 1_000)
            emit(
                "T7 audibleResult=NOT_MEASURED reason=no acoustic loopback or operator response " +
                    "channel; getRoutedDevice results above are measured",
            )
        } catch (failure: Throwable) {
            emit("T7 EXCEPTION ${failure.javaClass.name}: ${failure.message}")
            Log.e(TAG, "T7 failed", failure)
        } finally {
            writerB?.close()
            writerA?.close()
            closeTrack(trackB)
            closeTrack(trackA)
            emit("T7 END")
        }
    }

    @Suppress("MissingPermission")
    private fun captureForSeconds(
        label: String,
        requestedDevice: AudioDeviceInfo,
        seconds: Int,
    ): CaptureObservation {
        var record: AudioRecord? = null
        return try {
            record = buildVoiceRecord()
            record.startRecording()
            val rate = record.sampleRate
            val capture = readRecord(record, seconds * 1_000L)
            val routed = record.routedDevice
            CaptureObservation(
                label = label,
                requestedDevice = requestedDevice,
                routedDevice = routed,
                sampleRate = rate,
                readSamples = capture.first,
                nonZeroSamples = capture.second,
                firstRead = capture.third,
                error = null,
            )
        } catch (failure: Throwable) {
            CaptureObservation(
                label,
                requestedDevice,
                record?.routedDevice,
                record?.sampleRate ?: 0,
                0,
                0,
                0,
                "${failure.javaClass.name}: ${failure.message}",
            )
        } finally {
            closeRecord(record)
        }
    }

    @Suppress("MissingPermission")
    private fun primeRoute(audioManager: AudioManager, device: AudioDeviceInfo): Boolean {
        if (!audioManager.setCommunicationDevice(device)) return false
        return measureUntilRouted(device, PRIME_ROUTE_TIMEOUT_MS) != null
    }

    @Suppress("MissingPermission")
    private fun measureUntilRouted(
        requestedDevice: AudioDeviceInfo,
        timeoutMs: Long,
    ): TimedRouteObservation? {
        var record: AudioRecord? = null
        return try {
            record = buildVoiceRecord()
            record.startRecording()
            val buffer = ShortArray(maxOf(160, record.sampleRate.coerceAtLeast(8_000) / 50))
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            var firstRead = 0
            var nonZero = 0
            while (SystemClock.elapsedRealtime() < deadline) {
                val read = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (firstRead == 0 && read > 0) firstRead = read
                if (read > 0) nonZero += buffer.take(read).count { it.toInt() != 0 }
                val routed = record.routedDevice
                if (read > 0 && samePhysicalDevice(requestedDevice, routed)) {
                    return TimedRouteObservation(
                        SystemClock.elapsedRealtimeNanos(),
                        record.sampleRate,
                        firstRead,
                        nonZero,
                        routed,
                    )
                }
            }
            null
        } finally {
            closeRecord(record)
        }
    }

    private fun buildVoiceRecord(): AudioRecord {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            // Sample rate deliberately unspecified: report what AudioRecord selects.
            .build()
        return AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(format)
            .setBufferSizeInBytes(VOICE_BUFFER_BYTES)
            .build()
            .also { check(it.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord not initialized" } }
    }

    private fun readRecord(record: AudioRecord, durationMs: Long): Triple<Int, Int, Int> {
        val rate = record.sampleRate.coerceAtLeast(8_000)
        val target = (rate * durationMs / 1_000).toInt()
        val chunk = ShortArray(maxOf(160, rate / 50))
        var total = 0
        var nonZero = 0
        var firstRead = 0
        val deadline = SystemClock.elapsedRealtime() + durationMs + READ_GRACE_MS
        while (total < target && SystemClock.elapsedRealtime() < deadline) {
            val read = record.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING)
            if (read < 0) error("AudioRecord.read=$read")
            if (read == 0) continue
            if (firstRead == 0) firstRead = read
            total += read
            for (index in 0 until read) if (chunk[index].toInt() != 0) nonZero++
        }
        return Triple(total, nonZero, firstRead)
    }

    private fun readBriefly(record: AudioRecord, result: ConcurrentRead) {
        runCatching {
            val observed = readRecord(record, CONCURRENT_CAPTURE_MS)
            result.readSamples = observed.first
            result.nonZeroSamples = observed.second
        }.onFailure { result.error = "${it.javaClass.name}: ${it.message}" }
    }

    private fun buildToneTrack(): AudioTrack {
        val minimum = AudioTrack.getMinBufferSize(
            TONE_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minimum > 0) { "AudioTrack min buffer=$minimum" }
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(TONE_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minimum, TONE_CHUNK_SAMPLES * 4))
            .build()
            .also { check(it.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack not initialized" } }
    }

    private fun waitForTrackRoute(track: AudioTrack, timeoutMs: Long) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (track.routedDevice == null && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(20)
        }
    }

    @Suppress("MissingPermission")
    private fun findMediaOutput(
        audioManager: AudioManager,
        communicationDevice: AudioDeviceInfo,
    ): AudioDeviceInfo? = audioManager
        .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        .firstOrNull { device ->
            device.isSink && isMediaBluetoothOutput(device) &&
                samePhysicalDevice(communicationDevice, device)
        }

    private fun closeRecord(record: AudioRecord?) {
        if (record == null) return
        runCatching {
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
        }
        runCatching { record.release() }
    }

    private fun closeTrack(track: AudioTrack?) {
        if (track == null) return
        runCatching {
            if (track.playState != AudioTrack.PLAYSTATE_STOPPED) track.stop()
        }
        runCatching { track.release() }
    }

    private fun snapshotKey(device: AudioDeviceInfo): String =
        "${device.id}|${device.type}|${device.productName}|${device.address}|" +
            "${device.isSink}|${device.isSource}"

    private fun physicalKey(device: AudioDeviceInfo): String =
        visibleAddress(device.address)
            ?: "name:${device.productName.toString().trim().lowercase(Locale.ROOT)}"

    private fun isBluetoothCommunicationOutput(device: AudioDeviceInfo): Boolean =
        device.isSink && when (device.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            -> true
            else -> false
        }

    private fun isMediaBluetoothOutput(device: AudioDeviceInfo): Boolean = when (device.type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST,
        -> true
        else -> false
    }

    private fun samePhysicalDevice(a: AudioDeviceInfo?, b: AudioDeviceInfo?): Boolean {
        if (a == null || b == null) return false
        val addressA = visibleAddress(a.address)
        val addressB = visibleAddress(b.address)
        if (addressA != null && addressB != null) return addressA == addressB
        val nameA = a.productName.toString().trim().lowercase(Locale.ROOT)
        val nameB = b.productName.toString().trim().lowercase(Locale.ROOT)
        return nameA.isNotEmpty() && nameA == nameB
    }

    private fun visibleAddress(raw: String?): String? {
        val address = raw?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return address.takeUnless {
            it.isBlank() || it == "00:00:00:00:00:00" || it == "02:00:00:00:00:00"
        }
    }

    private fun typeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "BUILTIN_EARPIECE"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "BUILTIN_MIC"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BLUETOOTH_A2DP"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
        AudioDeviceInfo.TYPE_HEARING_AID -> "HEARING_AID"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE_HEADSET"
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BLE_SPEAKER"
        AudioDeviceInfo.TYPE_BLE_BROADCAST -> "BLE_BROADCAST"
        else -> "TYPE_$type"
    }

    private fun describe(device: AudioDeviceInfo?): String = device?.let {
        "id=${it.id} type=${typeName(it.type)}(${it.type}) " +
            "product=${it.productName.toString().ifBlank { "<blank>" }} " +
            "address=${it.address.ifBlank { "<blank>" }} sink=${it.isSink} source=${it.isSource}"
    } ?: "<null>"

    private fun emit(message: String) {
        Log.i(TAG, message)
        runOnUiThread {
            output.append(message)
            output.append("\n")
        }
    }

    private data class CaptureObservation(
        val label: String,
        val requestedDevice: AudioDeviceInfo,
        val routedDevice: AudioDeviceInfo?,
        val sampleRate: Int,
        val readSamples: Int,
        val nonZeroSamples: Int,
        val firstRead: Int,
        val error: String?,
    ) {
        val routeMatches: Boolean
            get() {
                val expected = requestedDevice.address.trim().lowercase(Locale.ROOT)
                val actual = routedDevice?.address?.trim()?.lowercase(Locale.ROOT)
                return expected.isNotBlank() && expected == actual
            }
    }

    private data class TimedRouteObservation(
        val successNs: Long,
        val sampleRate: Int,
        val firstRead: Int,
        val nonZeroSamples: Int,
        val routedDevice: AudioDeviceInfo?,
    )

    private class ConcurrentRead {
        @Volatile var readSamples: Int = 0
        @Volatile var nonZeroSamples: Int = 0
        @Volatile var error: String? = null
    }

    private class ToneWriter(
        private val track: AudioTrack,
        private val frequencyHz: Double,
    ) : AutoCloseable {
        private val keepWriting = AtomicBoolean(true)
        private val samples = ShortArray(TONE_CHUNK_SAMPLES) { index ->
            (TONE_AMPLITUDE * Short.MAX_VALUE *
                sin(2.0 * Math.PI * frequencyHz * index / TONE_SAMPLE_RATE)).toInt().toShort()
        }
        private var worker: Thread? = null

        fun start() {
            worker = thread(name = "probe-tone-${frequencyHz.toInt()}") {
                while (keepWriting.get()) {
                    val written = track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                    if (written < 0) break
                }
            }
        }

        override fun close() {
            keepWriting.set(false)
            runCatching { track.pause() }
            runCatching { track.flush() }
            worker?.join(1_000)
        }
    }

    private companion object {
        const val TAG = "OneVoiceProbe"
        const val EXTRA_AUTORUN = "autorun"
        const val REQUEST_PERMISSIONS = 7_401
        const val CAPTURE_SECONDS = 2
        const val SWITCH_ITERATIONS = 10
        const val ROUTE_TIMEOUT_MS = 8_000L
        const val PRIME_ROUTE_TIMEOUT_MS = 5_000L
        const val READ_GRACE_MS = 4_000L
        const val CONCURRENT_CAPTURE_MS = 1_000L
        const val CONCURRENT_READ_TIMEOUT_MS = 5_000L
        const val OUTPUT_RECOVERY_TIMEOUT_MS = 10_000L
        const val VOICE_BUFFER_BYTES = 96_000
        const val TONE_SAMPLE_RATE = 48_000
        const val TONE_CHUNK_SAMPLES = 4_800
        const val TONE_AMPLITUDE = 0.08
        const val T7_TONE_DURATION_MS = 4_000L
    }
}
