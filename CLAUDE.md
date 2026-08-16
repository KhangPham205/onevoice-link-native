# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

On-device speech-to-speech translation (Vietnamese ↔ English) for Snapdragon phones. The app exposes one production backend: **automatic Hybrid**. A CPU SpeechFlow LID classifier (TFLite) identifies each completed utterance, then the app routes it without user input:

- VI→EN: GIPFormer encoder and MarianMT execute on Hexagon HTP; the small RNN-T decoder/joiner and whole-utterance Kokoro EN run on CPU.
- EN→VI: Zipformer-small encoder and MarianMT execute on Hexagon HTP; the small RNN-T decoder/joiner and whole-utterance Kokoro VI run on CPU.
- Unknown/low-confidence language: fail closed, emit no translation or audio.

CPU models may be retained as cold, whole-utterance failover assets, but there is no user-facing CPU or Full-QNN mode. The old fixed-window QNN Kokoro work remains historical evidence only.

The Settings screen inventories connected Bluetooth audio endpoints and stores
their preferred VI/EN listening language. This is future Auracast provisioning,
not an active audio route. Auracast is downlink-only and does not identify the
speaker; keep acoustic LID until an authenticated, exclusive speaker-floor plus
verified microphone route supplies an endpoint ID. Route planning must snapshot
one speaker and all listeners at the utterance boundary and fail closed on an
unknown/ambiguous speaker. See `docs/AURACAST_ROUTING.md`.

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
python3 tools/models/convert_speechflow_lid.py models-src/speechflow-lid
tools/models/push-npu-models.sh auto R5CW72TP4EP  # LID + both Hybrid directions
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

**Live audio is bounded.** Hybrid uses `AudioRecord` and `AudioTrack` from capture and inference workers: 20 ms VAD frames, 300 ms pre-roll, one-second end hangover, a 10-second static ASR window, 500 ms overlap at forced window boundaries, and a queue of two utterances. LID and all learned inference run after a complete segment, never on the audio read loop.

**JNI is confined to one file.** `native-core/src/main_jni.cpp` binds Kotlin direct buffers to the generic `QnnRuntime::executeGraph` and runs the exact ASR/LID feature frontends. `core/OneVoiceNative.kt` is the Kotlin mirror — changing arity/order on either side breaks the other silently. The old Oboe `executeCascade()` worker is not used by Hybrid.

**The two branches use six separate QNN context binaries, not one graph.** Each direction owns ASR encoder → MT encoder → MT decoder. `QnnRuntime` creates the backend/device **once** and one context **per stage** (`native-core/src/qnn_runtime.cpp`). Direction-qualified runtime keys are mandatory because both Marian directions embed the graph names `opus_mt_encoder` and `opus_mt_decoder`. Loading each stage is a 3-step proof, reported separately so failures are diagnosable:

1. `QnnSystemContext_getBinaryInfo` — parse blob, read graph name + I/O contract + `socModel`, no hardware.
2. `contextCreateFromBinary` — place graphs on the HTP (fails on wrong Hexagon arch or DSP-access denial).
3. `graphRetrieve(graphName)` — confirm graph identity.

**Graph names are not guessable and not in the shipped metadata.** `assets/npu_manifest.json` is the single source of truth, generated from the binaries themselves via `python3 tools/models/gen_npu_manifest.py`. Regenerate it whenever the model team ships new `.bin`, or `graphRetrieve` fails.

**QNN libraries are `dlopen`'d, never linked.** `libonevoice_core.so` deliberately has no QNN `DT_NEEDED` entries, because `ADSP_LIBRARY_PATH` must be published *before* `libQnnHtp.so` first loads — an ELF NEEDED entry would load it too early and make the later `setenv` useless. `openQnnLib()` tries soname first, absolute path second (`useLegacyPackaging = false` keeps `.so` mmap'd inside the APK, so absolute paths may not exist on disk).

**The Hexagon skeleton ships as an asset, not a jniLib.** `libQnnHtpV73Skel.so` is a DSP6 binary; putting it in `jniLibs/arm64-v8a` would make the Android linker misread it as ARM64. It is extracted to `<filesDir>/dsp/{signed,unsigned}/` at runtime and that directory is prepended to `ADSP_LIBRARY_PATH`.

**Models live in internal `filesDir`, not external storage.** On Android 11+ (verified on Android 16 / One UI) an app cannot see files under its own `Android/data/<pkg>/files/` created by another uid — `adb shell` lists them, the path matches byte-for-byte, and the app still gets `exists() == false`; chowning does not help. `NpuEngine.candidateRoots` checks both and uses whichever actually holds models.

**Automatic routing is Kotlin/native-library orchestration, not the old C++ cascade worker.** SpeechFlow LID decides VI, EN or unknown for each bounded utterance. The chosen branch owns its QNN ASR/MT contexts and CPU transducer/Kokoro host glue. An unknown decision never falls back to a default language. Transcript, translation and replay are published with the actual per-utterance route; replay becomes available only after TTS publishes a complete waveform.

**Headset assignments do not currently replace LID.** The public Android
inventory cannot identify an individual Auracast listener or the active talker,
and an ordinary APK cannot manage Auracast broadcasts without a privileged OEM
service. `headset/HeadsetRoutePlanner.kt` is a pure future policy boundary; it
must not be interpreted as proof that audio was routed.

## Current status — read before debugging inference

Keep the capability boundary explicit when debugging:

- **Automatic Hybrid is the only user-facing path.** SpeechFlow LID runs on CPU/TFLite before ASR. Its SavedModel export excludes the audio frontend, so `models-npu-generated/lid/speechflow_contract.json` and `speechflow_labels.json` are as mandatory as the `.tflite` itself. Three traps, all silent: features are **time-major** `[1,1000,80]` (AmberNet was channel-first), normalization is **whole-utterance** (`normalize_per_feature: false`), and `output_1` is **raw logits despite being all-negative** — `exp()` over the vector sums to ~0.27, so softmax is required. The 14th class `other` is not a guard; gaussian noise ranks `italian` at p≈0.72. Two of 83 nodes need `tensorflow-lite-select-tf-ops`, which costs ~68 MB of APK.
- **Both Hybrid directions have QNN ASR encoders.** VI uses GIPFormer graph `graph_oa00nu3e`; EN uses Zipformer-small graph `graph_shbblv7u`. Both contracts are FP32 `[1,1000,80]` → `[1,248,512]` on SM8550/v73. Their decoder/joiner ONNX files remain CPU host glue.
- **Both Marian directions execute on HTP.** Their embedded graph names repeat, so never use the raw logical role as the sole loaded-context key when both directions are resident.
- **Kokoro remains whole-utterance CPU by design.** This preserves continuous prosody and avoids the old fixed-window QNN decoder seams. Do not restore the former Full-QNN selector.
- **CPU recovery is lazy and atomic at the utterance boundary.** An ASR/MT exception quarantines that direction's HTP text path; the cold CPU ASR+Marian bundle restarts from captured PCM rather than splicing partial results. An uncertain LID decision always fails closed even if CPU assets exist.
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
