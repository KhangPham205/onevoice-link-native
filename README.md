# OneVoice Link

Offline Vietnamese ↔ English speech-to-speech translation on a Snapdragon 8 Gen 2
phone. Two people take turns speaking into one phone (or one shared Bluetooth
earbud pair). The app works out which language each sentence is in, translates
it and speaks the result. Nothing leaves the device: the APK declares no
`INTERNET` permission.

Submitted to the OneVoice AI Challenge 2026.

## How it works

A turn lasts until the speaker pauses for 1 s, up to 120 s. Voice activity
detection (Silero) cuts it into ASR windows of at most 10 s at natural pauses.
Each window is recognised and translated while the speaker keeps talking, and
the whole turn is played back once they stop. Each window then goes through:

| Step | Vietnamese speaker (VI→EN) | English speaker (EN→VI) | Runs on |
|---|---|---|---|
| Speech recognition | GIPFormer (RNN-T) | Parakeet-TDT-CTC-110M | Hexagon NPU (decoder on CPU) |
| Language routing | both recognisers score the sentence; the more confident one wins | | CPU |
| Translation | MarianMT vi→en | MarianMT en→vi | Hexagon NPU |
| Speech synthesis | Kokoro EN | VieNeu-TTS v3 Nano | CPU, whole sentence |

The language is decided once, on the first window, and then kept for the
whole turn. Audio is strictly half-duplex: the mic is closed while the
translation plays.
Audio can use the phone's mic and loudspeaker or a Bluetooth headset. That
choice only picks devices; it never sets the translation direction.

Details: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Measured on device

These results come from the organisers' 200-sentence dataset on an SM8550 phone in
airplane mode
([full report, Vietnamese](docs/proposal/evidence/P3_ket-qua-do_2026-11-02.md)):

| Metric | VI→EN | EN→VI |
|---|---|---|
| Language routed correctly | 100 / 100 | 91 / 100 |
| ASR word error rate | 1.3 % | 11.5 % |
| Translation COMET | 0.789 | 0.761 |
| Time to first audio (p50, from end of sentence detection) | 3.4 s | 3.06 s (VieNeu) |
| Peak app memory (RSS) | 1.7 GB | |

Speech detection also waits 1 s of silence before closing a sentence, so the
listener waits 1 s longer than these figures. These figures use short dataset
sentences (one window each). For long turns (30–134 s, one real Vietnamese
speaker, 12 recordings), the first translated audio starts at p95 74 ms after
the speaker stops, with 3.2 % ASR WER
([long-turn report](docs/LONG_FORM_SPEECH_VALIDATION.md)).

Known weak spots:
- Long turns are tested on recordings replayed through the capture path, not yet
  live on the microphone, on TWS earbuds, or in a 20–30 min soak.
- English spoken with a Vietnamese accent can be routed as Vietnamese (the two
  scores nearly tie and the turn prior decides).
- A same speaker continuing for a second turn can be routed to the wrong
  language, because the prior expects the other person to answer.
- An English sentence can be misrouted when the Vietnamese recogniser outputs a
  single confident token.
- Routing on real microphone recordings needs more labelled data.

## Quick install

**You need:**
- An arm64 Snapdragon 8 Gen 2 phone (`SM8550`, Hexagon v73) with USB debugging enabled.
- Linux with `adb`, JDK 21, Android SDK 36 and NDK 27.2.12479018.
  Point Gradle at the SDK with `ANDROID_HOME`, or create
  `android-app/local.properties` with `sdk.dir=/path/to/Android/Sdk`
  (Android Studio writes it when it opens `android-app/`).
- Rust (`rustup target add aarch64-linux-android`).
- Python 3 with `numpy`.
- Network access for steps 1 and 2. `ANDROID_NDK_HOME` defaults to
  `~/Android/Sdk/ndk/27.2.12479018`.

**Inputs not in git.** They are large or license-restricted. The QNN context
binaries were compiled on Qualcomm AI Hub by the model team and cannot be
downloaded or rebuilt from this repo, so get the first three rows from the team.
The exact files and a packing command are in
[BUILD.md](docs/BUILD.md#handing-the-inputs-to-a-new-machine) (about 3 GB plus
the SDK).

| Path | Contents |
|---|---|
| `native-core/third_party/qnn_sdk/` | Qualcomm AI Runtime (QAIRT) SDK 2.47 (the context binaries were built with 2.45 and 2.40 and load on it), with `include/` and `lib/` directly under it |
| `onevoice-npu-bins/` | QNN context binaries for SM8550 (only 4 of its folders are used) |
| `models-hf/`, `models-npu-generated/lid/` | tokenizers, Kokoro, eSpeak data, CPU recovery models, LID A/B files |
| `android-app/app/libs/sherpa-onnx-static-link-onnxruntime-1.13.4.aar` | public; step 1 downloads it |

**Then:**

```bash
# 1. Public inputs: the sherpa-onnx AAR, and the unsigned Hexagon v73
#    skeleton copied out of the SDK (without it, Load fails at runtime)
curl -fL --create-dirs -o android-app/app/libs/sherpa-onnx-static-link-onnxruntime-1.13.4.aar \
  https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.4/sherpa-onnx-static-link-onnxruntime-1.13.4.aar
mkdir -p android-app/app/src/main/assets/dsp
cp native-core/third_party/qnn_sdk/lib/hexagon-v73/unsigned/libQnnHtpV73Skel.so \
  android-app/app/src/main/assets/dsp/

# 2. Vietnamese voice: sea-g2p for arm64 + VieNeu Nano weights (~345 MB)
tools/models/build-sea-g2p-android.sh
python3 tools/models/prepare-vieneu-nano.py

# 3. Build and install the debug APK (debuggable is required to stage models)
cd android-app
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :app:installDebug
cd ..

# 4. Copy all models (~2.7 GB) into the app's private storage; re-runs skip
#    files whose sha256 already matches
tools/models/push-npu-models.sh auto <adb-serial>
```

**On the phone:**
1. Open **OneVoice Link** and allow the microphone.
2. Pick **Phone** (one shared handset) or **Bluetooth** (earbuds).
3. Tap **Load Hybrid Pipeline** and wait for the engine status **Ready**.
4. Tap **Start Translating** and speak in turns.

**Live demo & metrics** (side menu) shows the offline status, memory and per-turn
timings for recording. Its session log can be exported as JSONL.

Builds stop with an explanation if a required input is missing. For build
troubleshooting see [docs/BUILD.md](docs/BUILD.md); for model staging see
[docs/NPU_DEPLOY.md](docs/NPU_DEPLOY.md).

## Build options

Runtime policy is baked into the APK from `android-app/gradle.properties`; override per build:

```bash
./gradlew :app:assembleDebug -PviTtsEngine=KOKORO   # VI voice: VIENEU (default), KOKORO, SUPERTONIC
./gradlew :app:assembleDebug -ProutingMode=LID      # SpeechFlow language-ID A/B instead of dual ASR
./gradlew :app:assembleDebug -PlongTurnMode=LEGACY  # rollback: one 10 s window per turn (default ADAPTIVE)
./gradlew :app:testDebugUnitTest                    # unit tests (no device, no Rust needed)
```

The full list is in [CLAUDE.md](CLAUDE.md#build-and-deploy).

## Repository

```
android-app/    Kotlin/Compose app: pipeline, routing, half-duplex audio, UI
native-core/    C++ JNI: QNN runtime loader, ASR frontends, sea-g2p shim
tools/models/   model conversion, staging and push scripts
tools/eval/     dataset evaluation harness (WER, BLEU, COMET, latency)
docs/           architecture, build, deployment, evidence
```

## Documentation

| Document | Contents |
|---|---|
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | Pipeline, dual-ASR routing, half-duplex audio, threading |
| [BUILD.md](docs/BUILD.md) | Toolchain, out-of-repo inputs, Gradle flags, troubleshooting |
| [NPU_DEPLOY.md](docs/NPU_DEPLOY.md) | Model layout on the device and how it is staged |
| [OFFLINE_PIPELINE.md](docs/OFFLINE_PIPELINE.md) | Runtime behaviour per direction and failure handling |
| [QNN_INTEGRATION.md](docs/QNN_INTEGRATION.md) | QNN loading order, contexts, DSP skeleton |
| [JNI_API.md](docs/JNI_API.md) | Kotlin ↔ C++ boundary |
| [tools/eval/README.md](tools/eval/README.md) | Re-running the dataset measurements |
| [Evidence report](docs/proposal/evidence/P3_ket-qua-do_2026-11-02.md) | Round 3 measurements (Vietnamese) |

## License

See [LICENSE](LICENSE). VieNeu-TTS and sea-g2p are Apache-2.0, and Kokoro is
Apache-2.0. Supertonic-3 weights (optional) are OpenRAIL-M. The Qualcomm AI Runtime
SDK is under Qualcomm's license and is not redistributed here.
