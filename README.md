# OneVoice Link

Offline Vietnamese ↔ English speech-to-speech translation on a Snapdragon 8 Gen 2
phone. Two people take turns speaking into one phone (or one shared Bluetooth
earbud pair). The app works out which language each sentence is in, translates
it and speaks the result. Nothing leaves the device: the APK declares no
`INTERNET` permission.

Submitted to the OneVoice AI Challenge 2026.

## How it works

Each sentence is cut by voice activity detection (Silero, at most 10 s), then:

| Step | Vietnamese speaker (VI→EN) | English speaker (EN→VI) | Runs on |
|---|---|---|---|
| Speech recognition | GIPFormer (RNN-T) | Parakeet-TDT-CTC-110M | Hexagon NPU (decoder on CPU) |
| Language routing | both recognisers score the sentence; the more confident one wins | | CPU |
| Translation | MarianMT vi→en | MarianMT en→vi | Hexagon NPU |
| Speech synthesis | Kokoro EN | VieNeu-TTS v3 Nano | CPU, whole sentence |

Audio is strictly half-duplex: the mic is closed while the translation plays.
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
listener waits 1 s longer than these figures.

Known weak spots:
- English sentences longer than 10 s are rejected.
- An English sentence can be misrouted when the Vietnamese recogniser outputs a
  single confident token.
- Routing on real microphone recordings needs more labelled data.

## Quick install

**You need:**
- An arm64 Snapdragon 8 Gen 2 phone (`SM8550`, Hexagon v73) with USB debugging enabled.
- Linux with `adb`, JDK 21, Android SDK 36 and NDK 27.2.12479018.
- Rust (`rustup target add aarch64-linux-android`).
- Python 3 with `numpy`.
- Network access for step 1. `ANDROID_NDK_HOME` defaults to
  `~/Android/Sdk/ndk/27.2.12479018`.

**Inputs not in git.** They are large or license-restricted; get them from the team:

| Path | Contents |
|---|---|
| `native-core/third_party/qnn_sdk/` | Qualcomm AI Runtime (QAIRT) SDK 2.47 (the context binaries were built with 2.45 and 2.40 and load on it) |
| `android-app/app/libs/sherpa-onnx-static-link-onnxruntime-1.13.4.aar` | from the k2-fsa/sherpa-onnx v1.13.4 release |
| `onevoice-npu-bins/` | QNN context binaries for SM8550 |
| `models-hf/`, `models-npu-generated/` | tokenizers, Kokoro, eSpeak data, CPU recovery models, LID A/B files |

**Then:**

```bash
# 1. Vietnamese voice: sea-g2p for arm64 + VieNeu Nano weights (~345 MB)
tools/models/build-sea-g2p-android.sh
python3 tools/models/prepare-vieneu-nano.py

# 2. Build and install the debug APK (debuggable is required to stage models)
cd android-app
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :app:installDebug
cd ..

# 3. Copy all models (~2.7 GB) into the app's private storage; re-runs skip
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
