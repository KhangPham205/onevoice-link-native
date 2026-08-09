# Deploy and run the QNN speech pipeline

This guide deploys the live VI → EN pipelines to an SM8550 / Hexagon v73 phone.
Hybrid (the default) executes QNN ASR/MT on HTP and whole-utterance Kokoro on
CPU. Full QNN executes all six learned stages from context binaries on HTP.

## Prerequisites

| Item | Requirement |
|---|---|
| Device | `ro.soc.model=SM8550`, `ro.board.platform=kalama` |
| APK | debuggable build; the deploy script uses `run-as` |
| Contexts | `onevoice-npu-bins/` plus generated prosody context |
| QAIRT | headers/runtime under `native-core/third_party/qnn_sdk` |
| Toolchain | JDK 21, NDK `27.2.12479018`, CMake `3.22.1` |

Always pass the ADB serial. `R5CW72TP4EP` is the transport serial, not a
Hexagon signing serial.

```bash
ADB=/home/khangpt4/Android/Sdk/platform-tools/adb
$ADB -s R5CW72TP4EP shell getprop ro.soc.model
$ADB -s R5CW72TP4EP shell getprop ro.board.platform
```

## Build, install, and stage models

```bash
cd android-app
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
  ./gradlew --no-daemon --no-parallel --max-workers=1 :app:assembleDebug
/home/khangpt4/Android/Sdk/platform-tools/adb -s R5CW72TP4EP \
  install -r -t app/build/outputs/apk/debug/app-debug.apk
cd ..
tools/models/push-npu-models.sh vi-en R5CW72TP4EP
```

The script copies into internal app storage:

```text
/data/user/0/com.onevoice.link/files/models/npu/vi-en/
```

It stages the six `.bin` contexts, ASR/MT/TTS tokenizers, exact eSpeak frontend
data and bridge, Kokoro voice table, and the CPU TTS artifact needed by Hybrid.
Large files pass through one temporary file and that staging copy is deleted
immediately; existing same-sized files are skipped.

## Live stage contract

| Stage | Main input/output role |
|---|---|
| `asr` | mel `[1,1001,80]` → CTC logits `[1,126,1025]` |
| `mt_encoder` | IDs/mask `[1,256]` → 12 cross-attention tensors |
| `mt_decoder` | token + position + KV cache → logits + present cache |
| `tts_encoder` | token IDs/style → duration, `d`, and `t_en` |
| `tts_prosody` | aligned `en` + style tail → `F0` and `N` |
| `tts_decoder` | `F0/N/asr/style/har` → 15,000 waveform samples |

Select **Hybrid**, tap **Load**, wait for the ASR and MT context rows, then tap
**Start Translating**. This is the recommended listening path. Select **NPU**
to load all six stages and exercise bucketed QNN TTS. The app records 16 kHz
mono and plays synthesized 24 kHz mono audio. QNN calls are serialized; audio
capture pauses while output is playing so playback is not recognized again as
a new utterance.

Full-QNN G2P is not a character fallback: the tiny `tts_frontend.onnx` bridge
uses sherpa-onnx/eSpeak to return the exact Kokoro phoneme IDs, then all learned
TTS stages execute on HTP. Hybrid loads the unified CPU Kokoro model so it can
render the translated sentence with uninterrupted decoder context.

Useful logs:

```bash
/home/khangpt4/Android/Sdk/platform-tools/adb -s R5CW72TP4EP logcat \
  -s OneVoiceNpu:* OneVoiceQNN:* OneVoiceCore:*
```

## Automated execution smoke test

```bash
cd android-app
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
  ./gradlew --no-daemon --no-parallel --max-workers=1 \
  :app:assembleDebug :app:assembleDebugAndroidTest
ADB=/home/khangpt4/Android/Sdk/platform-tools/adb
$ADB -s R5CW72TP4EP install -r -t app/build/outputs/apk/debug/app-debug.apk
$ADB -s R5CW72TP4EP install -r -t \
  app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
$ADB -s R5CW72TP4EP shell am instrument -w \
  com.onevoice.link.test/androidx.test.runner.AndroidJUnitRunner
```

This test loads all contexts, invokes `graphExecute`, translates a fixed
sentence, synthesizes it, checks the waveform for finite values, and drains it
through `AudioTrack`. A context-load-only result is not considered sufficient.
For the same device sentence, Full QNN took about 1.2 seconds over three decoder
buckets; Hybrid whole-utterance CPU Kokoro took about 2.7 seconds. Both delivered
every PCM frame, so audible Full-QNN stutter is not an `AudioTrack` underrun.

## Direction coverage

| Direction | Status |
|---|---|
| VI → EN Hybrid | Complete; default and recommended for smooth speech |
| VI → EN Full QNN | Complete six-stage execution; bucketed TTS quality-limited |
| EN → VI | Disabled: English ASR QNN context is not available |

## Troubleshooting

| Symptom | Action |
|---|---|
| `libcdsprpc.so not found` | Confirm merged manifest contains `<uses-native-library android:name="libcdsprpc.so">`. |
| `0x36b1` | Read preceding FastRPC/QNN logs. The code means invalid device config; it does not by itself prove a signature failure. |
| every stage missing | Run the push script after installing the debug APK; it uses internal `filesDir`. |
| graph name mismatch | Regenerate `assets/npu_manifest.json` from the delivered binaries. |
| initial/final words missing | Preserve 300 ms pre-roll and one-second VAD hangover. |
| Full-QNN output still stutters with no underrun | Expected limitation of the fixed 25-frame decoder contract; use Hybrid for whole-utterance Kokoro. |
| hard click at a Full-QNN bucket boundary | Preserve HAR phase/RNG continuity and overlap/crossfade stitching. |
| output stops near five seconds | Do not restore the former 200-frame TTS limit; current cap is 750 frames. |

The resolved `0x36b1` incident is documented in
[2026-08-08-qnn-0x36b1-fastrpc-linker-policy.md](incidents/2026-08-08-qnn-0x36b1-fastrpc-linker-policy.md).
The production phone uses an unsigned process domain successfully once the
public vendor `libcdsprpc.so` is exposed to the app linker namespace. A TestSig
does not bypass retail OEM trust policy.
