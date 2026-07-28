# OneVoice Link

> **Ultra-low-latency, on-device Speech-to-Speech Translation (S2ST) for Android**, powered by the Qualcomm AI Runtime (QNN) on the Hexagon NPU and Google Oboe for bare-metal audio.

**Submitted to the OneVoice AI Challenge 2026.**

OneVoice Link turns a Snapdragon phone into a real-time, offline interpreter. Speech captured from the microphone is streamed through a fully native C++ pipeline - capture -> AI translation -> synthesis -> playback - that **never touches the JVM on the hot path**, so Android's garbage collector can never stall the audio or spike latency. The entire cascade runs on the device: no network, no cloud, usable off-grid.

---

## Why it's fast

| Design choice | Why it matters |
|---|---|
| **Zero-Java hot path** | The audio->AI->audio loop lives entirely in `libonevoice_core.so`. The JVM only issues four lifecycle calls; ART GC pauses cannot tear the pipeline. |
| **Qualcomm Hexagon HTP** | Inference runs on the NPU via a pre-compiled QNN context binary - no on-device graph compilation, near-zero warm-up. |
| **Google Oboe, exclusive + low-latency** | Engages the AAudio MMAP fast path, bypassing the AudioFlinger mixer for the lowest possible round-trip latency. |
| **Lock-free SPSC ring buffers** | Audio and AI threads exchange samples with cache-line-padded, branch-free ring buffers - no mutex, no allocation, no false sharing in the realtime callback. |
| **LTO + `-O3` + ARMv8.2 dotprod** | The core is built with link-time optimization and NEON dot-product tuning to feed the HTP efficiently. |

---

## Architecture at a glance

```text
+---------------- Android app (Kotlin / Jetpack Compose) -----------------+
| MainActivity -> OneVoiceNative (JNI bridge, com.onevoice.link.core)     |
|                 initEngine / startEngine / stopEngine / destroyEngine   |
+-------------------------------+-----------------------------------------+
                                |
                                | JNI boundary: lifecycle calls only
                                | no audio data crosses here
                                v
+--------------------- libonevoice_core.so (C++20) -----------------------+
| Mic -> Oboe capture -> input SPSC ring -> AI worker -> QNN / Hexagon HTP|
|        (RT thread)      (lock-free)       (native)      (INT8 S2ST)     |
|                                                                         |
| Speaker <- Oboe playback <- output SPSC ring <- synthesized PCM         |
|             (RT thread)      (lock-free)                                |
+-------------------------------------------------------------------------+
```

Full details in [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

---

## Repository layout

```text
onevoice-link-native/
|-- android-app/            Jetpack Compose app (Kotlin DSL, Gradle version catalog)
|   `-- app/src/main/
|       |-- java/com/onevoice/link/
|       |   |-- MainActivity.kt          GSD dashboard + runtime permissions + lifecycle
|       |   `-- core/OneVoiceNative.kt   JNI bridge to the native core
|       |-- jniLibs/arm64-v8a/           packaged native libs (QNN staged here by Gradle)
|       `-- AndroidManifest.xml          RECORD_AUDIO + Bluetooth (P2P) permissions
|-- native-core/            The C++ realtime engine -> libonevoice_core.so
|   |-- CMakeLists.txt
|   |-- include/            audio_engine.h, ring_buffer.h, qnn_inference.h
|   |-- src/                main_jni.cpp, audio_engine.cpp, ring_buffer.cpp, qnn_inference.cpp
|   `-- third_party/
|       |-- oboe/           Google Oboe (committed, built from source)
|       `-- qnn_sdk/        Qualcomm QNN SDK (VENDORED LOCALLY, NOT committed; see below)
`-- docs/                   Architecture, build, JNI API, and QNN integration guides
```

---

## Quick start

**Prerequisites:** Android Studio (or CLI) with SDK Platform **36**, NDK **26-29**, CMake **3.22.1**, and a JDK **17+**. Target device: an **arm64-v8a Snapdragon** phone with a Hexagon NPU.

1. **Vendor the QNN SDK.** The Qualcomm AI Runtime SDK is *not* committed (large, license-restricted). Extract it so that these paths exist:
   ```
   native-core/third_party/qnn_sdk/include/QNN/
   native-core/third_party/qnn_sdk/lib/aarch64-android/
   ```
   Without it, the core still compiles in **stub mode** (via `__has_include` guards) so wiring can be exercised on any toolchain.

2. **Build the debug APK:**
   ```bash
   cd android-app
   ./gradlew :app:assembleDebug
   ```

3. **Install & run:**
   ```bash
   ./gradlew :app:installDebug
   ```
   Grant the microphone permission, tap **Initialize Engine**, then **Start Translating**.

See [`docs/BUILD.md`](docs/BUILD.md) for the full toolchain matrix, build flags, and troubleshooting.

---

## Documentation

| Document | Contents |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Threading model, data flow, latency budget, the lock-free ring buffer. |
| [docs/BUILD.md](docs/BUILD.md) | Toolchain, QNN vendoring, Gradle <-> CMake binding, compiler flags, troubleshooting. |
| [docs/JNI_API.md](docs/JNI_API.md) | The native ABI contract and the Kotlin bridge (`OneVoiceNative`). |
| [docs/QNN_INTEGRATION.md](docs/QNN_INTEGRATION.md) | QNN SDK layout, packaging `.so` into the APK, and the runtime `dlopen` chain. |
| [docs/MODEL_HANDOFF.md](docs/MODEL_HANDOFF.md) | Contract for the AI/model team: everything to deliver alongside the `.bin`. |
| [docs/EMULATOR_TESTING.md](docs/EMULATOR_TESTING.md) | Running the full cascade on the emulator with HuggingFace models (no NPU needed). |
| [docs/HF_INTEGRATION_REPORT.md](docs/HF_INTEGRATION_REPORT.md) | Integration report (vi): what was integrated for emulator testing — libraries, model conversion, issues & fixes. |

---

## Project status

- [done] **Builds green** - `./gradlew :app:assembleDebug` produces a signed-debug APK for `arm64-v8a`.
- [done] **Realtime audio** - Oboe full-duplex capture/playback and lock-free SPSC ring buffers are fully implemented.
- [done] **QNN bring-up** - backend `dlopen`, device/context creation from a `.bin`, and graph retrieval are wired against the real QNN C API.
- [in progress] **Model integration** - tensor binding (INT8 shapes), QnnMem zero-copy, and `graphExecute` are clearly-marked placeholders owned by the AI/model team (see [`qnn_inference.cpp`](native-core/src/qnn_inference.cpp)). Until a real serialized context binary is supplied, `initEngine` stops at `contextCreateFromBinary` and the UI reports the failure gracefully.

See the roadmap notes in [docs/QNN_INTEGRATION.md](docs/QNN_INTEGRATION.md#status--roadmap).

---

## License

See [LICENSE](LICENSE). Google Oboe is Apache-2.0. The Qualcomm QNN / AI Runtime SDK is subject to Qualcomm's own license and is not redistributed in this repository.
