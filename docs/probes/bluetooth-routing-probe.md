# Bluetooth audio routing probe — SM8550 / Android 16

## Scope

This report contains only values observed on the connected device. It does not
infer an audible result from an Android route object.

- Run date: 2026-08-17 (Asia/Ho_Chi_Minh)
- ADB serial: `R5CW72TP4EP`
- Requested test target: Galaxy S23 / SM8550
- Device actually reported by Android: `SM-F731B` (Galaxy Z Flip5), API 36
- Probe log tag: `OneVoiceProbe`
- Probe Activity:
  `com.onevoice.link.debug.BluetoothRoutingProbeActivity`
- Source: `android-app/app/src/debug/java/com/onevoice/link/debug/BluetoothRoutingProbeActivity.kt`

The Activity is present only in the debug source set. It uses Kotlin and Android
SDK APIs, has no added dependency, and does not call `MainActivity`, `NpuEngine`
or `OneVoiceNative`. The installed app already held both runtime grants:
`RECORD_AUDIO` and `BLUETOOTH_CONNECT`.

Start it manually or through ADB:

```bash
adb -s R5CW72TP4EP shell am start \
  -n com.onevoice.link/.debug.BluetoothRoutingProbeActivity \
  --ez autorun true
adb -s R5CW72TP4EP logcat -s OneVoiceProbe:I '*:S'
```

## Test precondition observed

Samsung Bluetooth Settings showed both devices as **Connected for calls and
audio**. `dumpsys bluetooth_manager` also showed ACL BR/EDR connected for both:

| Device | Bluetooth profiles reported by the stack |
|---|---|
| JBL WAVE BEAM | `AudioSink`, `Avrcp`, `Handsfree` |
| G435 Bluetooth Gaming Headset | `HSP`, `AudioSink`, `Avrcp`, `Handsfree` |

That stack-level connection did not mean that both devices were simultaneously
available to the public audio-routing API. At probe time:

- `AudioManager.getAvailableCommunicationDevices()` exposed only JBL as a
  Bluetooth communication device;
- `AudioManager.getDevices(GET_DEVICES_OUTPUTS)` exposed only JBL as an A2DP
  media output;
- G435 was absent from both public API lists used by the probe.

## Results

| Test | Status | Measured result |
|---|---|---|
| T1 — List devices | Partial pass | Communication devices were built-in earpiece `id=2`, built-in speaker `id=3`, and JBL SCO output `id=367`. JBL address was visible as `08:EB:ED:FF:5A:AF`; it was not masked. A repeated snapshot 250 ms later was identical. JBL A2DP output was `id=371`. G435 was not returned by either public list during this run. No disconnect/reconnect cycle was performed, so `productName + id` stability across reconnection was not measured. |
| T2 — Intentional mic route | Pass for JBL only | `setCommunicationDevice(JBL SCO id=367)` returned `true`. `AudioRecord(VOICE_COMMUNICATION)` routed to input `id=368`, product JBL WAVE BEAM, with the same address `08:EB:ED:FF:5A:AF`. The probe read 96,000 samples, of which 79,119 were non-zero. G435 could not be tested because Android did not expose a corresponding `AudioDeviceInfo` to the app. |
| T3 — SCO switch latency | Not run | Only one endpoint both accepted routing and matched `AudioRecord.getRoutedDevice()`. A→B switching and the requested ten timing samples therefore could not be performed. No min/median/max value is reported. |
| T4 — Two microphones concurrently | Not run | There were not two independently routable microphone endpoints in the public API. No exception was manufactured by attempting the test with a missing device. |
| T5 — Mic sample rate | Pass for JBL framework stream | With the builder sample rate left unspecified, `AudioRecord.sampleRate` was 48,000 Hz. This is the Android framework stream rate. The probe did not obtain Bluetooth air-codec telemetry, so this value is not labeled as mSBC or CVSD. |
| T6 — A2DP conflict | Objective API portion passed for JBL | A 440 Hz `AudioTrack` preferred JBL A2DP and `getRoutedDevice()` remained JBL A2DP before and during JBL SCO microphone capture. Playback head advanced from 0 to 144,336 frames during capture; underrun count remained 0. After closing the record, the requested A2DP route was observed after 12 ms and the head reached 162,865. Audible interruption/quality was not measured because there was no acoustic loopback or operator-response channel. |
| T7 — Split two outputs | Not run | Although Bluetooth Settings reported both headsets connected, `GET_DEVICES_OUTPUTS` returned only one Bluetooth media output during the run. Two `AudioTrack` instances could not be assigned to two distinct public `AudioDeviceInfo` objects. No claim is made about what either listener would hear. |

### Important timing distinction

T2 logged 2,662 ms from the call to `setCommunicationDevice()` through
completion of a deliberately two-second capture. It is **not** a microphone
switch-latency measurement and must not be substituted for the missing T3
median.

## Answers to the three decision questions

### 1. Can the microphone be identified reliably by physical device?

**For the one active headset exposed by Android: yes in this run.** The JBL
output and routed input had different Android IDs (`367` and `368`) but the same
visible MAC address and product name. Address matching worked; ID matching would
have been wrong across sink/source endpoints.

**For a two-headset language-routing design: not verified on this device
configuration.** Android reported both headsets connected at the Bluetooth
profile layer, while the public audio APIs exposed only JBL. The app therefore
could not select or verify G435. This run does not support removing LID on the
assumption that two simultaneously connected HFP headsets will both be
selectable.

### 2. What is the cost of switching the microphone each time?

**Not measured.** Android did not expose a second routable microphone, so T3
could not perform the requested ten A→B switches. The report intentionally has
no inferred latency number.

### 3. Can different content be sent to two headsets?

**Not demonstrated.** Only one Bluetooth media output was exposed through
`AudioManager` during the run, so the probe could not create two independently
preferred output routes. Bluetooth Settings saying that both devices are
connected is not evidence that two app-addressable routes exist.

## Re-test condition

T3, T4 and T7 should be rerun only after a new T1 snapshot shows two Bluetooth
communication outputs and two media outputs. If Samsung Dual Audio must be
enabled through System UI to make that happen, record that operator step as a
test precondition. The debug Activity will then automatically attempt all seven
tests and report exact exceptions/routes instead of the current precondition
failures.
