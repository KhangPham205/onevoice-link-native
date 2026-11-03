# Technology Stack

**Analysis Date:** 2026-11-02

## Languages

**Primary:**
- Kotlin 2.2.10 — Android app, Jetpack Compose UI, audio/pipeline orchestration, and JNI calls under `android-app/app/src/main/java/com/onevoice/link/`.
- C++20 — the `libonevoice_core.so` JNI/QNN/audio utility layer under `native-core/src/`.

**Secondary:**
- Gradle Kotlin DSL — Android build and QNN package staging in `android-app/app/build.gradle.kts`.
- Python 3 — model conversion/manifest tooling in `tools/models/*.py`.
- Bash and PowerShell — device deployment and model setup tooling in `tools/models/`.

## Runtime

**Environment:**
- Android API 28+; compile/target API 36.1, package `com.onevoice.link`, specified in `android-app/app/build.gradle.kts`.
- Production ABI is `arm64-v8a`; the intended device is SM8550/Snapdragon 8 Gen 2 with Hexagon v73, encoded in `android-app/app/src/main/assets/npu_manifest.json`.

**Package Manager:**
- Gradle wrapper in `android-app/`; version catalog at `android-app/gradle/libs.versions.toml`.
- Lockfile: Gradle dependency lockfiles not detected.

## Frameworks

**Core:**
- Android Gradle Plugin 9.2.1 and Kotlin Compose plugin 2.2.10 — APK build and Compose compilation.
- Jetpack Compose / Material 3 — dashboard and settings UI under `android-app/app/src/main/java/com/onevoice/link/ui/`.
- Android media APIs — bounded `AudioRecord`/`AudioTrack` and Bluetooth/communication routing in `android-app/app/src/main/java/com/onevoice/link/core/NpuEngine.kt`.
- CMake 3.22.1, Android NDK 27.2.12479018 — native build configuration in `native-core/CMakeLists.txt` and `android-app/app/build.gradle.kts`.

**AI runtime:**
- Qualcomm QNN/QAIRT HTP — dynamically loaded by `native-core/src/qnn_runtime.cpp`; SDK is locally vendored but intentionally uncommitted at `native-core/third_party/qnn_sdk/`.
- ONNX Runtime Android 1.20.0 — host RNN-T and whole-utterance TTS/MT support in Kotlin, declared in `android-app/gradle/libs.versions.toml`.
- sherpa-onnx static AAR (local `android-app/app/libs/sherpa-onnx-static-link-onnxruntime-1.13.4.aar`) — offline ASR/TTS reference and recovery assets.
- TensorFlow Lite 2.16.1 plus Select TF Ops — retained SpeechFlow LID A/B path in `android-app/app/src/main/java/com/onevoice/link/core/SpeechFlowLanguageIdentifier.kt`.
- Google Oboe — vendored source build, statically linked into the native core by `native-core/CMakeLists.txt`; the active hybrid audio path is Kotlin-owned.

**Testing:**
- JUnit 4.13.2, AndroidX JUnit/Espresso, and Compose UI test libraries are declared in `android-app/gradle/libs.versions.toml`.

## Key Dependencies

**Critical:**
- QNN HTP/System/Prepare/stub shared libraries — staged from the licensed SDK into the APK by `copyQnnRuntimeLibs` in `android-app/app/build.gradle.kts`; they must be packaged but are not linked at build time.
- SM8550-specific QNN context binaries — described by `android-app/app/src/main/assets/npu_manifest.json`; six ASR/Marian stages are kept resident by `NpuEngine`.
- Externally deployed host models (GIPFormer/Parakeet decoding, Marian resources, Kokoro/Supertonic, and cold fallback) — copied to app-internal storage by `tools/models/push-npu-models.sh`.
- `libcdsprpc.so` — requested through `<uses-native-library>` in `android-app/app/src/main/AndroidManifest.xml` for the QNN FastRPC dependency on the target device.

**Infrastructure:**
- AndroidX Core, Lifecycle Runtime, Activity Compose, Compose BOM, and Material 3 — UI/runtime support in `android-app/gradle/libs.versions.toml`.

## Configuration

**Environment:**
- No app environment-variable configuration is detected. Runtime behavior is compile-time Gradle properties in `android-app/gradle.properties`: routing mode, dual-ASR thresholds/offset/prior, VAD mode, QNN PD mode, CPU TTS and Supertonic tuning.
- `-PqnnSignedSkel=/path/to/libQnnHtpV73Skel.so` optionally stages a licensed DSP skeleton; its input remains external to the repository.

**Build:**
- Build files: `android-app/settings.gradle.kts`, `android-app/build.gradle.kts`, `android-app/app/build.gradle.kts`, `android-app/gradle/libs.versions.toml`, and `native-core/CMakeLists.txt`.
- Release C++ builds use `-O3 -flto`; debug builds are not LTO. Native libraries request 16 KiB ELF alignment in `native-core/CMakeLists.txt`.

## Platform Requirements

**Development:**
- JDK 17+, Android SDK API 36, NDK 27.2.12479018, CMake 3.22.1, and a locally extracted QNN SDK are required to build the fully functional APK. Without QNN headers/libraries the C++ wrapper has guarded stub behavior, but Gradle QNN-library staging fails unless the SDK library directory exists.

**Production:**
- A debuggable target build, connected via ADB, is required to stage model artifacts under the app's internal `filesDir`; `tools/models/push-npu-models.sh` validates device state, model hashes, and SM8550 unless explicitly overridden.

---

*Stack analysis: 2026-11-02*
