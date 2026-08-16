# Deploy the automatic Hybrid speech pipeline

This guide stages the single production pipeline for an SM8550 / Hexagon v73
phone. The app identifies Vietnamese or English from each completed audio turn,
runs ASR and MarianMT on QNN HTP, then renders the target sentence with
whole-utterance CPU Kokoro.

There is no user-facing CPU/QNN/backend selector and no direction toggle.

## Prerequisites

| Item | Requirement |
|---|---|
| Device | `ro.soc.model=SM8550`, `ro.board.platform=kalama` |
| APK | debuggable build; deployment uses `run-as` |
| QNN ASR | GIPFormer VI bundle and Zipformer-small EN bundle |
| QNN MT | Opus-MT VI→EN and EN→VI contexts |
| Host models | SpeechFlow LID, both transducer decoders/joiners, both CPU Kokoro models |
| Lazy fallback | Direction-specific CPU CTC ASR + Marian ONNX bundles |
| QAIRT | headers/runtime under `native-core/third_party/qnn_sdk` |
| Toolchain | JDK 21, NDK `27.2.12479018`, CMake `3.22.1` |

Always pass the ADB serial. `R5CW72TP4EP` is the USB transport serial, not a
Hexagon signing serial.

```bash
ADB=/home/khangpt4/Android/Sdk/platform-tools/adb
$ADB -s R5CW72TP4EP shell getprop ro.soc.model
$ADB -s R5CW72TP4EP shell getprop ro.board.platform
```

## Generate the SpeechFlow LID deployment trio

Fetch the Apache-2.0 SavedModel from
`SpeechFlow/spoken_language_identification` into `models-src/speechflow-lid/`
(`saved_model.pb` plus `variables/`), then convert inside a venv that has
`tensorflow-cpu`:

```bash
python3 tools/models/convert_speechflow_lid.py models-src/speechflow-lid
```

The converter asserts the signature, the 14-class output and the label order,
runs a TFLite smoke test, and writes:

```text
models-npu-generated/lid/speechflow_langid.tflite   (~1.2 MB)
models-npu-generated/lid/speechflow_labels.json
models-npu-generated/lid/speechflow_contract.json
```

All three are mandatory, and the label file is the routing contract: upstream
`vocab/vocab.txt` is alphabetical, so `english` is class 1 and `vietnamese` is
class 12. A silent reordering upstream would send Vietnamese speech through the
English branch with no other symptom, which is why both indices are asserted at
load time on the device as well.

Three properties of this graph differ from the AmberNet export it replaced, and
each produces plausible-looking garbage rather than an error if ignored:

- **Features are time-major** `[1,1000,80]`, not channel-first. The matching
  frontend is `makeSpeechFlowFeatures`, not `makeAmberNetFeatures`.
- **Normalization is whole-utterance** — upstream `config.yml` sets
  `normalize_per_feature: false` — over every valid `(frame, mel)` cell.
- **`output_1` is already log-softmax.** Exponentiate it; a second softmax
  flattens the scores and defeats the confidence gate.

The 14th class is `other`, but it does not absorb junk on its own: on this
export, gaussian noise ranks `italian` at p≈0.72. The confidence and margin
thresholds are what actually fail closed.

Two of the 83 graph nodes stay outside the TFLite builtin set, so the app must
ship `tensorflow-lite-select-tf-ops` alongside `tensorflow-lite`; without the
flex delegate the interpreter refuses to allocate. LID runs on CPU. Moving it to
HTP would require a separately compiled and introspected SM8550 QNN context.

## Build, install, and stage both directions

```bash
cd android-app
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
  ./gradlew --no-daemon --no-parallel --max-workers=1 :app:assembleDebug
/home/khangpt4/Android/Sdk/platform-tools/adb -s R5CW72TP4EP \
  install -r -t app/build/outputs/apk/debug/app-debug.apk
cd ..
ADB=/home/khangpt4/Android/Sdk/platform-tools/adb \
  tools/models/push-npu-models.sh auto R5CW72TP4EP
```

`auto` is the production plan and stages shared LID plus both directions.
`vi-en` and `en-vi` remain available only for focused development deployment;
neither is a complete unattended two-way installation by itself. `all` is an
alias for `auto`.

Before contacting ADB, the script confirms every selected source is present and
non-empty. Each file is copied through `/data/local/tmp`, SHA-256 checked in an
app-owned `.part` path, then renamed within internal storage. Existing content
is skipped only when its SHA-256 matches. The eSpeak tree is streamed to a
temporary directory, verified file by file, then published by rename. No model
is trusted based on byte size alone.

The destination is:

```text
/data/user/0/com.onevoice.link/files/models/npu/
```

Direct `adb push` into external `Android/data` is not supported: on the target
Android 16 build, files created there by the shell uid are not reliably visible
to the app even when paths appear identical.

## Source-to-device mapping

| Device asset | Local source | Execution |
|---|---|---|
| `shared/speechflow_langid.tflite` | `models-npu-generated/lid/` | CPU TFLite language router |
| `shared/speechflow_{labels,contract}.json` | same | host contract, never a learned stage |
| `vi-en/asr_gipformer_encoder.bin` | `onevoice-npu-bins/asr/gipformer_65m_rnnt-bundle/bin/encoder.bin` | QNN HTP |
| `vi-en/asr_gipformer_{decoder,joiner}.onnx` | GIPFormer `decode/` | CPU ORT RNN-T loop |
| `en-vi/asr_zipformer_encoder.bin` | `onevoice-npu-bins/asr/zipformer_small_en-bundle/bin/encoder.bin` | QNN HTP |
| `en-vi/asr_zipformer_{decoder,joiner}.onnx` | Zipformer `decode/` | CPU ORT RNN-T loop |
| `<dir>/fallback/asr/{model.onnx,tokens.txt}` | direction-specific `models-hf/asr-*` | cold CPU recovery |
| `<dir>/fallback/mt/` | direction-specific Marian encoder/decoder + tokenizer | cold CPU recovery |
| `<dir>/mt_{encoder,decoder}.bin` | direction-specific Opus-MT contexts | QNN HTP |
| `<dir>/tts_cpu.onnx` | `models-hf/tts-en` or `tts-vi` | CPU sherpa-onnx |

Tokenizer, vocabulary, voice and eSpeak files are staged next to the direction
that consumes them. Host files are not listed as QNN stages in
`npu_manifest.json`.

## Verified QNN stage contract

The generated manifest is derived from the context binaries with
`qnn-context-binary-utility`.

| Direction | Stage | Graph | Main tensor contract |
|---|---|---|---|
| VI→EN | GIPFormer ASR encoder | `graph_oa00nu3e` | `[1,1000,80]` FP32 → `[1,248,512]` FP32 |
| VI→EN | MT encoder | `opus_mt_encoder` | IDs/mask `[1,256]` → cross KV |
| VI→EN | MT decoder | `opus_mt_decoder` | token/position/cache → 53,739 logits + KV |
| EN→VI | Zipformer ASR encoder | `graph_shbblv7u` | `[1,1000,80]` FP32 → `[1,248,512]` FP32 |
| EN→VI | MT encoder | `opus_mt_encoder` | IDs/mask `[1,256]` → cross KV |
| EN→VI | MT decoder | `opus_mt_decoder` | token/position/cache → 53,685 logits + KV |

The English encoder binary is 64,933,888 bytes, targets `socModel=43` /
`dspArch=73`, and contains graph `graph_shbblv7u`. Its bundle also supplies a
2,092,621-byte decoder, 1,026,405-byte joiner and 502-token vocabulary. Those
host pieces are required; `encoder.bin` alone cannot produce text.

Because both directions' Marian contexts embed the same graph names, the app
must assign direction-qualified runtime stage keys. Graph name and runtime key
are separate concepts: `graphRetrieve()` uses the embedded graph name, while
`executeGraph()` needs an unambiguous loaded-context key.

Regenerate the manifest whenever any QNN binary changes:

```bash
python3 tools/models/gen_npu_manifest.py
```

The generator rejects a vendor/AI-Hub context whose `socModel` is not 43.

## Operate and observe

Load Hybrid once, then start capture. For every accepted segment the UI reports
the detected source language and actual route. An uncertain LID result
does not default to either direction and produces no synthesized speech.

Useful logs:

```bash
/home/khangpt4/Android/Sdk/platform-tools/adb -s R5CW72TP4EP logcat \
  -s OneVoiceNpu:* OneVoiceQNN:* OneVoiceCore:*
```

Collect at least these values per utterance:

- LID top-1 language, confidence, margin, accepted/rejected, and latency;
- ASR, MT and TTS latency with the chosen direction;
- QNN context load/execute errors and whether CPU recovery was activated;
- waveform samples/duration, AudioTrack delivered frames and underruns;
- process PSS plus device thermal/battery state during a repeated workload.

Do not treat SpeechFlow's aggregate model-card accuracy or AI Hub's isolated
encoder timing as end-to-end validation. Test short and long VI/EN turns,
accented speech, silence/noise, third languages, mixed-language turns and
alternating speakers on the target microphone.

## Failure policy and troubleshooting

| Symptom | Action |
|---|---|
| LID files missing | Generate all three reviewed artifacts, then rerun the `auto` deploy plan. |
| LID repeatedly returns unknown | Inspect duration, global top-1, confidence and margin; do not lower thresholds without an evaluation set. |
| `libcdsprpc.so not found` | Confirm merged manifest contains `<uses-native-library android:name="libcdsprpc.so">`. |
| `0x36b1` | Read preceding FastRPC/QNN logs; invalid device config alone does not prove a signature failure. |
| every QNN stage missing | Install the debug APK first, then rerun the internal-filesDir deploy script. |
| graph name mismatch | Regenerate `npu_manifest.json` from the exact delivered contexts. |
| English encoder loads but produces no text | Confirm Zipformer decoder, joiner and 502-row tokens file were staged and selected. |
| wrong Marian direction executes | Ensure runtime stage keys are direction-qualified; embedded graph names legitimately repeat. |
| initial/final words missing | Preserve 300 ms pre-roll and one-second VAD hangover. |
| NPU ASR/MT execution fails | Quarantine that direction's HTP text path and restart the complete utterance from captured PCM with the cold CPU ASR+MT bundle. Never splice partial results. |

The production path fails closed for uncertain routing: it emits no translation
or TTS. CPU recovery is a separately visible failover mode, not permission to
guess a language. If both NPU and fallback fail, stop speech output and expose a
degraded/error state.

The resolved FastRPC incident remains documented in
[2026-08-08-qnn-0x36b1-fastrpc-linker-policy.md](incidents/2026-08-08-qnn-0x36b1-fastrpc-linker-policy.md).
