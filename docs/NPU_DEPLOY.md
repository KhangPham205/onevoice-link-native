# Deploy the Hybrid speech pipeline

This guide stages the single production pipeline for SM8550 / Hexagon v73.
The source language of each utterance is chosen on device by dual-ASR
confidence routing (see [OFFLINE_PIPELINE.md](OFFLINE_PIPELINE.md)); nothing
about the headset or its saved language decides the direction. The SpeechFlow
LID files are still staged so a `-ProutingMode=LID` A/B APK works without a
redeploy.

## Requirements

| Item | Requirement |
|---|---|
| Device | `ro.soc.model=SM8550`, `ro.board.platform=kalama` |
| APK | debuggable build; model deployment uses `run-as` |
| Permission | `RECORD_AUDIO`; `BLUETOOTH_CONNECT` for headset inventory in Settings |
| Audio | none extra for the Phone route; one HFP/LE Audio headset for Bluetooth |
| QNN ASR | GIPFormer VI encoder and Parakeet-TDT-CTC-110M EN contexts |
| QNN MT | Opus-MT VI->EN and EN->VI contexts |
| Host models | GIPFormer RNN-T decoder/joiner, Parakeet tokens, Kokoro EN, the built Vietnamese TTS |
| Native lib | `libsea_g2p_rs.so` in the APK for the default `viTtsEngine=VIENEU` |
| Lazy fallback | direction-specific CPU NeMo-CTC ASR + Marian bundles |
| LID A/B | `shared/speechflow_*` (loaded only by `-ProutingMode=LID` builds) |

`R5CW72TP4EP` is the ADB transport serial, not a Hexagon signing serial.

## Build, install and stage

VieNeu-TTS v3 Nano is the default Vietnamese voice and needs two git-ignored
inputs before the first build:

```bash
tools/models/build-sea-g2p-android.sh   # jniLibs/arm64-v8a/libsea_g2p_rs.so + sea_g2p.bin
python3 tools/models/prepare-vieneu-nano.py   # models-vieneu/tts_vieneu/
```

A `VIENEU` build refuses the jniLibs merge without `libsea_g2p_rs.so`; build
with `-PviTtsEngine=KOKORO` or `SUPERTONIC` to skip it.

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

`auto` (alias `all`) stages `shared/` and both complete directions, including
`en-vi/tts_vieneu/`, `en-vi/tts_supertonic/`, Kokoro, eSpeak data and
`fallback/`. `vi-en` and `en-vi` stage `shared/` plus one direction; the app
loads both directions at initialization, so neither subset is a complete
installation. `tools/models/push-vieneu.sh <serial>` re-stages only
`tts_vieneu/` after a VieNeu rebuild.

Before touching the phone the script refuses any missing source and checks the
eSpeak tree. It then requires exactly one device, `SM8550` (override with
`FORCE=1`) and a working `run-as`. Each file goes through `/data/local/tmp`
into an app-owned `.part`, is SHA-256 verified, then renamed inside internal
storage; files whose remote hash already matches are skipped. It never trusts
size alone and performs no broad deletion.

Destination:

```text
/data/user/0/com.onevoice.link/files/models/npu/
```

Internal storage is used because files another uid writes under external
`Android/data` can be invisible to the app on Android 16.

## Source-to-device mapping

| Device asset | Local source | Execution |
|---|---|---|
| `shared/speechflow_*` | `models-npu-generated/lid/` | TFLite, LID A/B only |
| `vi-en/asr_gipformer_encoder.bin` | `onevoice-npu-bins/asr/gipformer_65m_rnnt-bundle/bin/encoder.bin` | QNN HTP |
| `vi-en/asr_gipformer_{decoder,joiner}.onnx` | GIPFormer `decode/` | CPU ORT RNN-T |
| `en-vi/asr_parakeet_ctc.bin` | `onevoice-npu-bins/asr/parakeet_tdt_ctc_110m_en__sherpa_SM8550_10s/model.bin` | QNN HTP |
| `en-vi/asr_parakeet_tokens.txt` | same bundle, `tokens.txt` | CPU greedy CTC |
| `<dir>/mt_{encoder,decoder}.bin` | direction-specific Opus-MT float contexts | QNN HTP |
| `vi-en/tts_cpu.onnx` | `models-hf/tts-en` | CPU Kokoro EN |
| `en-vi/tts_vieneu/*` | `models-vieneu/tts_vieneu` | CPU ORT, default VI voice |
| `en-vi/tts_supertonic/*` | `models-hf/tts-vi-supertonic` | CPU sherpa-onnx, `SUPERTONIC` |
| `en-vi/tts_cpu.onnx` | `models-hf/tts-vi` | CPU Kokoro VI, `KOKORO` |
| `en-vi/tts_tokens.txt` | `tools/models/kokoro-vi-espeak-tokens.txt` | Kokoro VI token aliases |
| `<dir>/fallback/asr/` | `models-hf/asr-{vi,en}` | cold recovery |
| `<dir>/fallback/mt/` | direction-specific Marian ONNX | cold recovery |

Tokenizer, vocabulary, voice and eSpeak assets live next to the direction that
consumes them.

## Verified QNN contract

| Direction | Stage | Graph | Main tensor contract |
|---|---|---|---|
| VI->EN | GIPFormer ASR encoder | `graph_oa00nu3e` | `[1,1000,80]` FP32 -> `[1,248,512]` FP32 |
| VI->EN | MT encoder | `opus_mt_encoder` | IDs/mask `[1,256]` -> cross KV |
| VI->EN | MT decoder | `opus_mt_decoder` | token/position/cache -> 53,739 logits + KV |
| EN->VI | Parakeet CTC ASR | `model` | `[1,1000,80]` UFIXED_16 -> `log_probs [1,125,1025]` UFIXED_16 |
| EN->VI | MT encoder | `opus_mt_encoder` | IDs/mask `[1,256]` -> cross KV |
| EN->VI | MT decoder | `opus_mt_decoder` | token/position/cache -> 53,685 logits + KV |

Parakeet is a QAIRT 2.40 binary that loads on the in-tree 2.47 runtime. Regenerate the
manifest whenever a QNN binary changes; it records Parakeet's fixed-point
scale/offset from the binary:

```bash
python3 tools/models/gen_npu_manifest.py
```

The generator rejects contexts whose `socModel` is not 43. Runtime keys must be
direction-qualified because both Marian directions reuse graph names.

## Operate and observe

1. Open the app and load Hybrid (Dashboard, or **Live demo & metrics**).
2. Pick the audio route on the Dashboard: **Auto** (default; Bluetooth if
   exactly one communication headset is usable, else the phone), **Phone**
   (built-in mic and loudspeaker, no headset needed) or **Bluetooth**.
3. Start translating and speak in turns. Either language can start; dual ASR
   decides each turn.
4. On **Live demo & metrics**, **Export** copies the session JSONL to
   `Download/OneVoice/`.

Useful logs:

```bash
/home/khangpt4/Android/Sdk/platform-tools/adb -s R5CW72TP4EP logcat \
  -s OneVoiceNpu:* OneVoiceRouting:* OneVoiceHalfDuplex:* OneVoiceQNN:* \
     OneVoiceCore:* OneVoiceSession:* adsprpc:V
```

`OneVoiceRouting` has one JSON row per bounded utterance (scores, decision,
reason, stage latencies); `OneVoiceHalfDuplex` has every state edge and route
measurement. Keep these tags stable: they are the calibration contract.

## Troubleshooting

| Symptom | Action |
|---|---|
| `Missing NPU frontend assets` at load | Rerun `push-npu-models.sh auto <serial>`; the app checks only the files its built TTS engine uses |
| `UnsatisfiedLinkError` for `sea_g2p_rs` / build refuses jniLibs merge | Run `build-sea-g2p-android.sh`, or build with `-PviTtsEngine=KOKORO` |
| Bluetooth route: no or multiple communication headsets | Connect one HFP/LE Audio headset, let Android select one, or switch to Auto/Phone |
| Bluetooth route: A2DP route not restored | The turn fails closed by design; reconnect the headset and retry |
| Turns rejected with `floor` / no prior | Inspect the `OneVoiceRouting` rows; thresholds in `gradle.properties` are provisional |
| `libcdsprpc.so not found` | Confirm the merged manifest declares `libcdsprpc.so` |
| `0x36b1` | Read preceding FastRPC/QNN logs; it does not prove a signature failure |
| graph mismatch | Regenerate `npu_manifest.json` from the exact contexts |
| selected Marian MT fails | Expected recovery: that text path is quarantined and the whole PCM utterance reruns on the cold CPU bundle |

See [AURACAST_ROUTING.md](AURACAST_ROUTING.md) for the shared-TWS premise and
the normal-APK Auracast privilege boundary.
