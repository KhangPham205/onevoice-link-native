# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

On-device speech-to-speech translation (Vietnamese ↔ English) for Snapdragon phones. The app has two explicit backends:

- **Offline CPU** is the runnable speech-to-speech path: Kotlin `AudioRecord` + bounded VAD → sherpa-onnx ASR → ONNX Runtime MarianMT → sherpa-onnx Kokoro TTS → `AudioTrack`.
- **QNN HTP** loads and verifies Qualcomm context binaries on Hexagon. It is not presented as a runnable speech pipeline because the delivered QNN TTS split is not a closed artifact set.

Target hardware is fixed: **arm64-v8a, Snapdragon 8 Gen 2** (`SM8550` / `kalama` / Hexagon **v73**, `qnnSocModel` 43). No other ABI is built.

## Build & deploy

```bash
cd android-app
./gradlew :app:assembleDebug          # → app/build/outputs/apk/debug/app-debug.apk (~177 MB)
./gradlew :app:installDebug
./gradlew :app:testDebugUnitTest      # unit tests (JVM)
./gradlew :app:test --tests '*ExampleUnitTest*'   # single test
```

Model binaries are **not** in the APK — push them separately (needs a debuggable build for `run-as`):

```bash
tools/models/push-npu-models.sh all    # or: vi-en / en-vi   (~2 GB, resumable)
tools/models/push-hf-models.sh vi-en R5CW72TP4EP  # complete runnable direction (~1.1 GB)
adb logcat -s OneVoiceNpu:* OneVoiceQNN:* OneVoiceCore:* adsprpc:V
```

Gradle properties that change runtime behavior:

```bash
-PqnnSignedSkel=/secure/path/libQnnHtpV73Skel.so   # inject an OEM/test-signed Hexagon skeleton
-PqnnPdMode=auto|signed|unsigned                    # process-domain selection (default auto)
```

### Build environment gotchas (these will bite)

- **Pin the JDK.** Gradle otherwise may pick a VS Code extension JRE without `jlink`:
  `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew --no-daemon --no-parallel --max-workers=1 :app:assembleDebug`
- **VS Code's Gradle/Java extensions hold Gradle locks** and auto-restart when killed, which hangs terminal builds with `Timeout waiting to lock … Owner PID: …`. Either disable those extensions, build in Android Studio, or isolate: `GRADLE_USER_HOME=/tmp/gradle-isolated ./gradlew --no-daemon …`.
- Do not delete `.cxx` or run `clean` routinely. Prefer an incremental build. A clean LTO link may need 3–4 GB free RAM.
- `android-app/gradle.properties` deliberately caps Gradle at a 1 GiB heap, one worker, no parallel execution, and in-process Kotlin. Do not raise those limits casually: a former 4 GiB Gradle + 2 GiB Kotlin-daemon configuration caused the development laptop to be OOM-killed.
- Open the Android Studio project at `android-app/` (that is where `gradle.xml` lives), not the repo root.

## Architecture

Read `docs/ARCHITECTURE.md` for the threading model. The essentials:

**Three threads, no locks between them.** Oboe capture callback → lock-free SPSC ring → AI worker thread → QNN/HTP → output ring → Oboe playback callback. `SpscRingBuffer` (`native-core/include/ring_buffer.h`) uses power-of-two masking, `alignas(64)` head/tail to avoid false sharing, and drop-newest overflow. Audio latency (256-frame callback) is deliberately decoupled from model latency (1 s chunk) so a slow inference degrades to a gap, not a torn stream.

**JNI is confined to one file.** `native-core/src/main_jni.cpp` is the only place JNI is touched; it owns engine state and spawns the worker. `core/OneVoiceNative.kt` is the Kotlin mirror — changing arity/order on either side breaks the other silently.

**The cascade is N separate context binaries, not one graph.** ASR → MT encoder/decoder → TTS encoder/decoder, each an independently compiled `.bin` with its own AI Hub-generated graph name. `QnnRuntime` therefore creates the backend/device **once** and one context **per stage** (`native-core/src/qnn_runtime.cpp`). Loading each stage is a 3-step proof, reported separately so failures are diagnosable:

1. `QnnSystemContext_getBinaryInfo` — parse blob, read graph name + I/O contract + `socModel`, no hardware.
2. `contextCreateFromBinary` — place graphs on the HTP (fails on wrong Hexagon arch or DSP-access denial).
3. `graphRetrieve(graphName)` — confirm graph identity.

**Graph names are not guessable and not in the shipped metadata.** `assets/npu_manifest.json` is the single source of truth, generated from the binaries themselves via `python3 tools/models/gen_npu_manifest.py`. Regenerate it whenever the model team ships new `.bin`, or `graphRetrieve` fails.

**QNN libraries are `dlopen`'d, never linked.** `libonevoice_core.so` deliberately has no QNN `DT_NEEDED` entries, because `ADSP_LIBRARY_PATH` must be published *before* `libQnnHtp.so` first loads — an ELF NEEDED entry would load it too early and make the later `setenv` useless. `openQnnLib()` tries soname first, absolute path second (`useLegacyPackaging = false` keeps `.so` mmap'd inside the APK, so absolute paths may not exist on disk).

**The Hexagon skeleton ships as an asset, not a jniLib.** `libQnnHtpV73Skel.so` is a DSP6 binary; putting it in `jniLibs/arm64-v8a` would make the Android linker misread it as ARM64. It is extracted to `<filesDir>/dsp/{signed,unsigned}/` at runtime and that directory is prepended to `ADSP_LIBRARY_PATH`.

**Models live in internal `filesDir`, not external storage.** On Android 11+ (verified on Android 16 / One UI) an app cannot see files under its own `Android/data/<pkg>/files/` created by another uid — `adb shell` lists them, the path matches byte-for-byte, and the app still gets `exists() == false`; chowning does not help. `NpuEngine.candidateRoots` checks both and uses whichever actually holds models.

**The complete offline path is Kotlin/native-library orchestration, not the C++ QNN worker.** `HfEngineBackend` loads only the selected direction to bound phone RAM. Its capture queue, waveform buffers, playback buffer and ORT sessions are bounded; changing direction/backend releases the current bundle before another is loaded. The dashboard defaults to this backend and exposes transcript, translation and replay only after TTS publishes a waveform.

## Current status — read before debugging inference

Keep the capability boundary explicit when debugging:

- **The Offline CPU backend is end-to-end and is the default.** On 2026-08-08 it was verified on `R5CW72TP4EP`: both directions initialized; VI→EN microphone capture produced a Vietnamese transcript, Marian produced English text, Kokoro published a replayable waveform, and the process remained alive at about 1.57 GB PSS (EN→VI ready state was about 764 MB PSS). Models live under `<filesDir>/models/hf`; stage each direction with `tools/models/push-hf-models.sh`.
- **QNN `executeCascade()` still returns `false` on purpose.** Besides missing tensor-execution/ASR/MT host glue, the delivered Kokoro QNN pair omits the learned `F0Ntrain` prosody network, exact HAR generator/state and overlap-add contract. The five `.bin` files therefore cannot form a truthful end-to-end TTS cascade. The QNN UI says **verify only** and never opens the fake “Capturing audio” state.
- **The former `contextCreateFromBinary` `0x36b1` failure is resolved.** `0x36b1`
  is `QNN_DEVICE_ERROR_INVALID_CONFIG`; it did not prove a signature-policy
  failure. The real log chain was `libcdsprpc.so not found` → FastRPC remote
  symbols/transport failed → QNN surfaced 14001. Because the app targets API
  36, Android only exposes this public vendor non-NDK library when the manifest
  declares `<uses-native-library android:name="libcdsprpc.so">`.
  - Verified on the connected SM8550 device on 2026-08-08: unsigned-PD control
    returned 0, the QNN backend initialized, and all five VI→EN contexts loaded
    (`socModel=43`, binary build v2.45.0).
  - The working path uses QAIRT's **unsigned** v73 skeleton. A testsig is not
    required for this app path. Hexagon TestSig is for debug-fused/test devices;
    it is not a production trust anchor for a locked OEM retail phone.
  - If 14001 recurs, inspect the preceding QNN/FastRPC transport logs first.
    Check `libcdsprpc` visibility, unsigned-control result, skeleton lookup and
    stub/skel versions; never infer “needs signed skel” from the number alone.
  - The real DSP serial comes from `adb shell cat /sys/devices/soc0/serial_number`
    or the SDK's `getserial` — **not** from the ADB/USB serial string.
  - `ADSP_LIBRARY_PATH` only controls DSP-side lookup. It does not grant SELinux
    permissions or bypass signature validation.

  Full postmortem: `docs/incidents/2026-08-08-qnn-0x36b1-fastrpc-linker-policy.md`.
  See also `docs/QNN_SIGNED_DSP.md` (signed skeleton vs. testsig, and the HTP
  signed-PD device config) and `docs/NPU_DEPLOY.md`.

Full offline runtime/deployment notes: `docs/OFFLINE_PIPELINE.md`.

## Conventions

- The QNN SDK (`native-core/third_party/qnn_sdk/`) and model binaries (`onevoice-npu-bins/`, `models-*/`) are git-ignored — large and license-restricted. The core still compiles without the SDK: every QNN call degrades to a logged stub behind `#if __has_include(<QnnInterface.h>)`.
- Header stays SDK-agnostic — QNN handles live behind the `State` pimpl in the `.cpp` so callers need no QNN headers.
- Comments here explain *why*, especially where behavior looks wrong without device context (uid visibility, dlopen ordering, non-fatal `deviceCreate`). Match that when editing.
- Work happens on `dev`; `main` is the PR target.
