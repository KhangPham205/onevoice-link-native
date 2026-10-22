# Deploy the headset-routed Hybrid speech pipeline

This guide stages the single production pipeline for SM8550 / Hexagon v73.
Direction comes from the verified Bluetooth microphone identity and its saved
VI/EN assignment. No language-classifier artifact is built or deployed.

## Requirements

| Item | Requirement |
|---|---|
| Device | `ro.soc.model=SM8550`, `ro.board.platform=kalama` |
| APK | debuggable build; model deployment uses `run-as` |
| Permission | `RECORD_AUDIO`, runtime `BLUETOOTH_CONNECT` |
| Speaker uplink | assigned HFP or LE Audio unicast headset microphone |
| QNN ASR | GIPFormer VI and Zipformer-small EN encoder contexts |
| QNN MT | Opus-MT VI->EN and EN->VI contexts |
| Host models | both RNN-T decoder/joiners and both CPU Kokoro models |
| Lazy fallback | direction-specific CPU ASR + Marian bundles |

`R5CW72TP4EP` is the ADB transport serial, not a Hexagon signing serial.

## Build, install and stage

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

`auto` stages both complete directions. `vi-en` and `en-vi` remain focused
development subsets; neither is a complete unattended two-way installation.

The script refuses missing sources before touching the phone. It copies each
file through `/data/local/tmp`, verifies SHA-256 in an app-owned `.part`, then
renames inside internal storage. It never trusts size alone and performs no
broad deletion.

Destination:

```text
/data/user/0/com.onevoice.link/files/models/npu/
```

## Source-to-device mapping

| Device asset | Local source | Execution |
|---|---|---|
| `vi-en/asr_gipformer_encoder.bin` | `onevoice-npu-bins/asr/gipformer_65m_rnnt-bundle/bin/encoder.bin` | QNN HTP |
| `vi-en/asr_gipformer_{decoder,joiner}.onnx` | GIPFormer `decode/` | CPU ORT RNN-T |
| `en-vi/asr_zipformer_encoder.bin` | `onevoice-npu-bins/asr/zipformer_small_en-bundle/bin/encoder.bin` | QNN HTP |
| `en-vi/asr_zipformer_{decoder,joiner}.onnx` | Zipformer `decode/` | CPU ORT RNN-T |
| `<dir>/mt_{encoder,decoder}.bin` | direction-specific Opus-MT contexts | QNN HTP |
| `<dir>/tts_cpu.onnx` | `models-hf/tts-en` or `tts-vi` | CPU sherpa-onnx |
| `<dir>/fallback/asr/` | direction-specific CPU ASR | cold recovery |
| `<dir>/fallback/mt/` | direction-specific Marian ONNX | cold recovery |

Tokenizer, vocabulary, voice and eSpeak assets live next to the direction that
consumes them. There is no `shared/speechflow_*` deployment.

## Verified QNN contract

| Direction | Stage | Graph | Main tensor contract |
|---|---|---|---|
| VI->EN | GIPFormer ASR encoder | `graph_oa00nu3e` | `[1,1000,80]` FP32 -> `[1,248,512]` FP32 |
| VI->EN | MT encoder | `opus_mt_encoder` | IDs/mask `[1,256]` -> cross KV |
| VI->EN | MT decoder | `opus_mt_decoder` | token/position/cache -> 53,739 logits + KV |
| EN->VI | Zipformer ASR encoder | `graph_shbblv7u` | `[1,1000,80]` FP32 -> `[1,248,512]` FP32 |
| EN->VI | MT encoder | `opus_mt_encoder` | IDs/mask `[1,256]` -> cross KV |
| EN->VI | MT decoder | `opus_mt_decoder` | token/position/cache -> 53,685 logits + KV |

Regenerate the manifest whenever a QNN binary changes:

```bash
python3 tools/models/gen_npu_manifest.py
```

The generator rejects contexts whose `socModel` is not 43. Runtime keys must be
direction-qualified because both Marian directions reuse graph names.

## Operate and observe

1. Grant Nearby devices from OneVoice Settings.
2. Connect exactly one HFP/LE Audio headset intended as the speaker uplink.
3. Assign that headset VI or EN in OneVoice Settings.
4. Start the Samsung system Auracast broadcast and let compatible receivers
   join it.
5. Load Hybrid and start translating.

The app selects the sole assigned communication endpoint with
`setCommunicationDevice()`, then verifies `AudioRecord.routedDevice` before
using its assignment. Invalid/ambiguous input routing produces no ASR, MT or TTS.

Useful logs:

```bash
/home/khangpt4/Android/Sdk/platform-tools/adb -s R5CW72TP4EP logcat \
  -s OneVoiceNpu:* OneVoiceQNN:* OneVoiceCore:*
```

## Troubleshooting

| Symptom | Action |
|---|---|
| no assigned communication headset | Connect one HFP/LE Audio endpoint and assign VI/EN in Settings |
| multiple assigned communication headsets | Let Android select one active communication device or disconnect extras |
| routed mic is built-in/redacted | Reconnect headset, grant `BLUETOOTH_CONNECT`, verify the call/LE Audio route |
| Auracast menu missing on receiver | The receiver must support LE Audio Auracast; classic A2DP is insufficient |
| `libcdsprpc.so not found` | Confirm merged manifest declares `libcdsprpc.so` |
| `0x36b1` | Read preceding FastRPC/QNN logs; it does not prove a signature failure |
| graph mismatch | Regenerate `npu_manifest.json` from the exact contexts |
| QNN ASR/MT fails | Quarantine that direction and retry the whole PCM utterance on the cold CPU bundle |

See [AURACAST_ROUTING.md](AURACAST_ROUTING.md) for the normal-APK privilege
boundary and the current Samsung system-UI procedure.
