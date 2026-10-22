# Headset-routed bidirectional Hybrid pipeline

OneVoice exposes one production mode: **Hybrid selected by the verified
Bluetooth microphone route**. There is no backend selector, direction toggle or
learned language classifier.

| Speaking-headset assignment | ASR | Translation | Speech output |
|---|---|---|---|
| Vietnamese | GIPFormer encoder on QNN HTP; RNN-T decoder/joiner on CPU | Marian VI->EN on QNN HTP | whole-utterance Kokoro EN on CPU |
| English | Zipformer-small encoder on QNN HTP; RNN-T decoder/joiner on CPU | Marian EN->VI on QNN HTP | whole-utterance Kokoro VI on CPU |
| Route missing/ambiguous/unassigned | none | none | none; fail closed |

Each direction retains a cold CPU CTC ASR + Marian fallback bundle. It loads
only after that direction's QNN ASR/MT path throws and reprocesses the original
bounded PCM utterance atomically.

## Per-utterance data flow

```text
sole assigned HFP/LE Audio communication headset
  -> AudioManager.setCommunicationDevice(output)
  -> AudioRecord verifies matching Bluetooth input
  -> VAD opens utterance and snapshots headset ID + VI/EN assignment
       | VI -> GIPFormer QNN encoder -> CPU RNN-T -> Marian VI->EN QNN
       | EN -> Zipformer QNN encoder -> CPU RNN-T -> Marian EN->VI QNN
       ` invalid route -> fail closed
  -> whole-utterance CPU Kokoro at 24 kHz
  -> AudioTrack on Android's current system media route
  -> system-managed Auracast broadcast -> compatible passive listeners
```

The unicast microphone is the exclusive speaker-floor proof. Auracast is only
the one-to-many downlink and cannot identify the talker. A route/assignment
change after VAD opens applies to the next utterance.

## QNN context contracts

`android-app/app/src/main/assets/npu_manifest.json` lists QNN contexts only.
Host ONNX/tokenizer/voice files are deliberately absent from `stages`.

| Direction | Logical stage | Graph | Input | Output |
|---|---|---|---|---|
| VI->EN | ASR encoder | `graph_oa00nu3e` | `x` FP32 `[1,1000,80]` | `output_0` FP32 `[1,248,512]` |
| EN->VI | ASR encoder | `graph_shbblv7u` | `x` FP32 `[1,1000,80]` | `output_0` FP32 `[1,248,512]` |
| VI->EN | Marian encoder | `opus_mt_encoder` | IDs/mask `[1,256]` | 12 cross-attention tensors |
| VI->EN | Marian decoder | `opus_mt_decoder` | token/position/mask/KV | logits `[1,1,53739]` + KV |
| EN->VI | Marian encoder | `opus_mt_encoder` | IDs/mask `[1,256]` | 12 cross-attention tensors |
| EN->VI | Marian decoder | `opus_mt_decoder` | token/position/mask/KV | logits `[1,1,53685]` + KV |

All six contexts target `socModel=43`, Hexagon v73. Runtime lookup keys are
direction-qualified because both Marian directions embed identical graph names.

## Deployed layout

```bash
tools/models/push-npu-models.sh auto R5CW72TP4EP
```

The deploy script preflights all sources, verifies SHA-256, copies through an
app-owned `.part` and atomically publishes into internal `filesDir`.

```text
files/models/npu/
|-- vi-en/
|   |-- asr_gipformer_encoder.bin
|   |-- asr_gipformer_{decoder,joiner}.onnx
|   |-- asr_gipformer_{tokens,bpe,config}.*
|   |-- mt_{encoder,decoder}.bin
|   |-- mt_{source,target}.spm, mt_vocab.json, mt_config.json
|   |-- tts_cpu.onnx, tts_tokens.txt, tts_voices.bin
|   |-- fallback/{asr,mt}/...
|   `-- espeak-ng-data/
`-- en-vi/
    |-- asr_zipformer_encoder.bin
    |-- asr_zipformer_{decoder,joiner}.onnx
    |-- asr_zipformer_tokens.txt
    |-- mt_{encoder,decoder}.bin
    |-- mt_{source,target}.spm, mt_vocab.json, mt_config.json
    |-- tts_cpu.onnx, tts_tokens.txt, tts_voices.bin
    |-- fallback/{asr,mt}/...
    `-- espeak-ng-data/
```

No LID `.tflite`, labels or classifier frontend are required or staged.

## Capture, playback and failure policy

Capture uses 20 ms frames, 300 ms pre-roll, one-second end hangover, a ten-second
ASR ceiling, 500 ms overlap at forced boundaries and a queue of two utterances.
Capture is suppressed during TTS playback to prevent self-transcription.

Fail closed before inference when:

- `BLUETOOTH_CONNECT` is missing;
- no assigned HFP/LE Audio communication headset exists;
- more than one assigned communication headset exists and Android has not
  selected one;
- `AudioRecord.routedDevice` is missing, built-in, redacted or unassigned.

CPU fallback applies only after a verified direction and a QNN ASR/MT failure.
It is never permission to guess a language.

## Auracast output boundary

On the API-36 SM8550 phone, public probes report Broadcast Source and Assistant
support. A normal APK still cannot call the system Auracast source/assistant
control APIs because they require `BLUETOOTH_PRIVILEGED`. Start the broadcast in
Samsung Bluetooth settings; OneVoice then plays its completed target waveform
to the current system media route. See [AURACAST_ROUTING.md](AURACAST_ROUTING.md).

The former fixed-window QNN TTS experiment and audible seam analysis remain in
[2026-08-09-qnn-tts-fixed-window-quality.md](incidents/2026-08-09-qnn-tts-fixed-window-quality.md).
