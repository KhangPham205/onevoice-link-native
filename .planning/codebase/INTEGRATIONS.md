# External Integrations

**Analysis Date:** 2026-11-02

## APIs & External Services

**On-device AI/runtime:**
- Qualcomm QNN/QAIRT HTP — runs the six SM8550-specific ASR and Marian context graphs.
  - SDK/client: direct JNI bridge in `android-app/app/src/main/java/com/onevoice/link/core/OneVoiceNative.kt` and dynamic C API loading in `native-core/src/qnn_runtime.cpp`.
  - Auth: none at runtime; the licensed SDK and optional signed DSP skeleton are supplied outside version control.
- ONNX Runtime / sherpa-onnx — CPU RNN-T decode, Marian fallback, and whole-utterance TTS through `android-app/app/src/main/java/com/onevoice/link/core/` and `android-app/app/src/main/java/com/onevoice/link/hf/`.
  - SDK/client: `onnxruntime-android` and the local sherpa AAR configured in `android-app/app/build.gradle.kts`.
  - Auth: none.
- TensorFlow Lite SpeechFlow — build-time language-routing A/B branch only, implemented in `android-app/app/src/main/java/com/onevoice/link/core/SpeechFlowLanguageIdentifier.kt`.
  - SDK/client: `tensorflow-lite` plus `tensorflow-lite-select-tf-ops`.
  - Auth: none.

**Android platform:**
- Audio/Bluetooth routing — microphone capture, `AudioManager` communication-device selection, and playback are implemented in `android-app/app/src/main/java/com/onevoice/link/core/NpuEngine.kt`.
  - Required permissions: `RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS`, and Android 12+ `BLUETOOTH_CONNECT` in `android-app/app/src/main/AndroidManifest.xml`.
- FastRPC vendor bridge — `libcdsprpc.so` is a required public vendor native library declared in `android-app/app/src/main/AndroidManifest.xml`; QNN loads it before the HTP backend.
- Thermal status — resolved dynamically from `libandroid.so` in `native-core/src/thermal_probe.cpp` to preserve minSdk 28 compatibility.

## Data Storage

**Databases:**
- Not detected.

**File Storage:**
- App-private internal storage is the production model store: `files/models/npu/` and the `files/dsp/` extracted runtime directory, managed by `NpuEngine` in `android-app/app/src/main/java/com/onevoice/link/core/NpuEngine.kt`.
- `tools/models/push-npu-models.sh` transfers model payloads through a `.part` file, verifies SHA-256 as the app UID, then atomically renames the destination.
- Audio route preference is persisted locally by `AudioRoutePreferences` in `android-app/app/src/main/java/com/onevoice/link/core/AudioRoute.kt`.

**Caching:**
- No remote or shared cache. Model/context residency is in-process and Android app-private files.

## Authentication & Identity

**Auth Provider:**
- None. The app does not authenticate a user or call a cloud service.
- Device integration relies on Android runtime permission grants and app UID access for `run-as` model staging.

## Monitoring & Observability

**Error Tracking:**
- No external error-tracking service detected.

**Logs:**
- Android Logcat tags such as `OneVoiceNpu`, `OneVoiceRouting`, `OneVoiceHalfDuplex`, and native `OneVoiceQNN` are emitted by files under `android-app/app/src/main/java/com/onevoice/link/core/` and `native-core/src/`.
- The ADB-controlled benchmark receiver `android-app/app/src/main/java/com/onevoice/link/core/BenchControlReceiver.kt` can request QNN profile, power, memory, and CSV telemetry. It is inert unless `<filesDir>/bench` exists.

## CI/CD & Deployment

**Hosting:**
- Not applicable; this is a locally built Android APK with on-device model deployment.

**CI Pipeline:**
- No repository CI workflow detected.
- Build/deploy entry points are Gradle in `android-app/` and `tools/models/push-npu-models.sh`; the manifest generator is `tools/models/gen_npu_manifest.py`.

## Environment Configuration

**Required env vars:**
- None required by the app.
- Optional tool overrides: `ADB`, `HF_MODELS_DIR`, `FORCE`, and `LIBCXX_DIR` are consumed by scripts in `tools/models/`.

**Secrets location:**
- No application secret store detected. The QNN SDK and any signed DSP artifact are supplied locally outside git.

## Webhooks & Callbacks

**Incoming:**
- No network callbacks. The exported benchmark broadcast action `com.onevoice.link.BENCH` is defined in `android-app/app/src/main/AndroidManifest.xml` and handled by `BenchControlReceiver`; the implementation gates behavior on an app-private marker directory.

**Outgoing:**
- None. The app does not make network requests.

## Contract Drift to Avoid

- Treat `android-app/app/src/main/assets/npu_manifest.json` plus `tools/models/gen_npu_manifest.py` as the current QNN deployment contract. Some prose documents still name an EN Zipformer graph/headset-language assignment, while the current generator and Gradle defaults specify Parakeet CTC and `DUAL_ASR`; do not implement against those stale descriptions without reconciling them to source.

---

*Integration audit: 2026-11-02*
