# Emulator testing with HuggingFace models

Until a Snapdragon device (Hexagon NPU) is available, the full S2ST cascade can
be exercised on the Android Studio **x86_64 emulator** using three HuggingFace
models on CPU. **The QNN `.bin` path is untouched** — the app now has two
switchable backends behind one `EngineBackend` interface:

| Backend | Runtime | Where |
|---|---|---|
| `QNN · .bin` | native C++ core + Hexagon HTP | arm64 Snapdragon devices (default there) |
| `HF · CPU` | sherpa-onnx + ONNX Runtime | emulator / any device (default on x86_64) |

## The HF cascade (bidirectional)

```
Mic (AudioRecord 16 kHz mono) → energy-VAD segmentation      HfEngineBackend.kt

  VI→EN: parakeet-ctc-0.6b-vi → opus-mt-vi-en → Kokoro-EN (kokoro-en-v0_19)
  EN→VI: nemo-ctc-en-conformer-small → opus-mt-en-vi → Kokoro-VI (contextboxai)

  → Speaker (AudioTrack)  — the translation is ALWAYS spoken by the
                            target-language voice
```

Both directions load at Initialize and stay resident, so the **DIRECTION**
segmented toggle (VI→EN / EN→VI) switches instantly mid-conversation — each
speaker flips it before their turn. **Give the AVD ≥ 4 GB RAM** (Device
Manager → Edit → Advanced) or initialization may be killed by the OOM killer.

The **Live Translation** card shows the transcript/translation (labels follow
the direction) and has a 🔊 **replay button** that re-speaks the last
translation. While TTS is playing, the mic gate closes (half-duplex) so the
synthesized speech is never re-captured as input.

## One-time setup

### 0. Prerequisites
Android Studio emulator (x86_64, API 28+), Python 3.10+, `adb` on PATH.
Models were downloaded to `models-src/` (git-ignored) with `huggingface-cli`:

```powershell
# Already done in-repo; for reference:
$env:PYTHONUTF8=1; $env:HF_HOME="$PWD\models-src\.hf-cache"
huggingface-cli download nvidia/parakeet-ctc-0.6b-Vietnamese --local-dir models-src/parakeet-ctc-0.6b-vi --exclude "lm_4gram*"
huggingface-cli download Helsinki-NLP/opus-mt-vi-en --local-dir models-src/opus-mt-vi-en --exclude "tf_model.h5"
huggingface-cli download contextboxai/Kokoro-Vietnamese --local-dir models-src/kokoro-vietnamese --exclude "kokoro_vi.pth"
```

### 1. Fetch the sherpa-onnx AAR + espeak-ng data (user-run, one time)
```powershell
powershell -ExecutionPolicy Bypass -File tools/models/fetch-sherpa-assets.ps1
```
Downloads the official k2-fsa AAR into `android-app/app/libs/` (bundles
arm64-v8a **and** x86_64 JNI libs) and extracts `espeak-ng-data/` for Kokoro.
**The app does not compile without the AAR.**

### 2. Convert the models to on-device format
```powershell
powershell -ExecutionPolicy Bypass -File tools/models/setup-venv.ps1           # optimum/torch etc.
tools\models\.venv\Scripts\python.exe tools\models\convert_marian.py --int8    # → models-hf/mt
tools\models\.venv\Scripts\python.exe tools\models\convert_kokoro.py           # → models-hf/tts

powershell -ExecutionPolicy Bypass -File tools/models/setup-venv.ps1 -WithNemo # +nemo_toolkit (several GB)
tools\models\.venv\Scripts\python.exe tools\models\convert_parakeet.py --int8  # → models-hf/asr
```

### 3. Build, install, push models
```powershell
cd android-app
.\gradlew :app:assembleDebug          # now builds arm64-v8a + x86_64
.\gradlew :app:installDebug           # to the running emulator
# Launch the app once (creates its files dir), then:
cd ..
powershell -ExecutionPolicy Bypass -File tools/models/push-to-device.ps1
```

Models land in `/sdcard/Android/data/com.onevoice.link/files/models/hf/` with
the bidirectional layout:

```
asr-vi/  asr-en/  mt-vi-en/  mt-en-vi/  tts-vi/  tts-en/  espeak-ng-data/
```

(`asr-en` and `tts-en` are downloaded ready-made by `fetch-sherpa-assets.ps1` —
no conversion; `mt-en-vi` comes from `convert_marian.py --direction en-vi`.)

### 4. Run
Launch the app → backend defaults to **HF · CPU** on the emulator →
**Initialize Engine** → **Start Translating** → speak Vietnamese into the host
mic (Emulator settings: enable *Virtual microphone uses host audio input*).

## How the pieces map to code

| Piece | File |
|---|---|
| Backend interface + QNN adapter | `core/EngineBackend.kt`, `core/QnnEngineBackend.kt` |
| HF cascade (audio, VAD, orchestration) | `hf/HfEngineBackend.kt` |
| MarianMT greedy decode on ORT | `hf/MarianTranslator.kt` |
| Pure-Kotlin SentencePiece (unigram Viterbi) | `hf/SentencePieceUnigram.kt` |
| Backend switch + live text UI | `ui/components/BackendSelector.kt`, `TranslationCard.kt` |
| Model conversion / push tooling | `tools/models/*` |

## Native-core notes (x86_64)

The core now also builds for `x86_64` so the APK installs on the emulator:
ARM `-march` flags and the QNN link are gated to `arm64-v8a` in
`native-core/CMakeLists.txt`. On x86_64 the QNN code compiles against the
headers but `dlopen(libQnnHtp.so)` fails at runtime → `nativeInitEngine`
returns `false`, which the dashboard reports if you select the QNN backend on
the emulator. **Behavior on arm64 devices is byte-for-byte unchanged.**

## Known constraints

- Parakeet 0.6B on an emulated CPU is slow: expect a few seconds per segment
  (`--int8` helps). This is a functional test rig, not a latency demo.
- **16 KB page-size dialog (x86_64 only):** the upstream sherpa-onnx / ONNX
  Runtime x86_64 prebuilts are not 16 KB ELF-aligned, so API-36 emulator images
  would show a compatibility warning. The manifest opts into page-size
  compatibility mode (`android:pageSizeCompat="enabled"`), which suppresses the
  dialog; the arm64-v8a device libs (including `libonevoice_core.so`) are
  properly 16 KB aligned and unaffected.
- Kokoro-Vietnamese voices are Vietnamese; English synthesis is accented.
- `models-src/` (~3.4 GB) and `models-hf/` are git-ignored — never commit them.
- The 4-gram LM (`lm_4gram*`, 174 MB) from the Parakeet repo is excluded;
  sherpa-onnx greedy CTC decoding doesn't use it.
