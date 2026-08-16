# Automatic bidirectional Hybrid pipeline

OneVoice exposes one production mode: **Hybrid with automatic spoken-language
identification**. There is no backend selector and no VI→EN / EN→VI toggle. A
completed VAD utterance is classified acoustically, then routed through the
matching ASR→MT→TTS branch.

| Input | ASR | Translation | Speech output |
|---|---|---|---|
| Vietnamese | GIPFormer encoder on QNN HTP; RNN-T decoder/joiner on CPU | Marian VI→EN on QNN HTP | whole-utterance Kokoro EN on CPU |
| English | Zipformer-small encoder on QNN HTP; RNN-T decoder/joiner on CPU | Marian EN→VI on QNN HTP | whole-utterance Kokoro VI on CPU |
| Unknown / uncertain | none | none | none; fail closed and keep listening |

Each direction has a cold CPU CTC ASR + Marian bundle. It is loaded only after
that direction's QNN ASR/MT path throws and must not be presented as an HTP
stage.

## Per-utterance data flow

```text
AudioRecord, 16 kHz mono
  -> bounded VAD utterance
  -> exact SpeechFlow log-Mel frontend -> SpeechFlow LID on CPU/TFLite
       | accepted VI
       |   -> GIPFormer encoder.bin on HTP
       |   -> host RNN-T decoder/joiner
       |   -> Marian VI->EN encoder/decoder on HTP
       |   -> CPU Kokoro EN, one continuous waveform
       |
       | accepted EN
       |   -> Zipformer-small encoder.bin on HTP
       |   -> host RNN-T decoder/joiner
       |   -> Marian EN->VI encoder/decoder on HTP
       |   -> CPU Kokoro VI, one continuous waveform
       |
       ` rejected/other language -> no ASR, MT, or TTS
  -> AudioTrack, 24 kHz mono
```

LID runs before either ASR, so it can make the routing decision without a
transcript. `papluca/xlm-roberta-base-language-detection` is a text classifier
and therefore cannot replace this first acoustic decision; at most it can be a
post-ASR validator.

LID executes on CPU through TFLite. Its interface is intentionally isolated so a
future static SM8550 QNN context can replace it without changing capture or
routing. Copying a model file beside the QNN contexts does not execute it on HTP.

## Headset assignment and future Auracast routing

Settings can persist a VI/EN preferred language for Bluetooth audio endpoints
that Android currently exposes. Those assignments are not a second UI mode and
do not currently redirect `AudioTrack`: they are input to a future
one-speaker/many-listener route planner.

The classifier can be bypassed only after a separate floor-control/microphone
transport proves exactly one active headset. At the utterance boundary, the app
will snapshot that speaker and the listener assignments, derive the source
direction from the speaker's language and target only listeners assigned the
opposite language. Unknown, unassigned or multiple speakers fail closed.

Auracast itself is broadcast downlink and neither supplies a microphone uplink
nor enumerates every listener. A normally installed Android APK also lacks the
privileged system APIs required to create/provision Auracast sessions. The
current code therefore keeps acoustic LID as the operational fallback and marks
Auracast broadcast creation/provisioning as inactive. The required OEM-service boundary is detailed
in [AURACAST_ROUTING.md](AURACAST_ROUTING.md).

## SpeechFlow LID export contract

The published artifact is a TensorFlow SavedModel whose graph excludes the audio
frontend, so the input is a processed feature tensor, not PCM. Generate the
reviewed deployment trio with:

```bash
python3 tools/models/convert_speechflow_lid.py models-src/speechflow-lid
```

Expected output under the git-ignored `models-npu-generated/lid/` directory:

```text
speechflow_langid.tflite
speechflow_labels.json
speechflow_contract.json
```

The contract pins 16 kHz audio, 80 features, 25 ms / 10 ms framing, FFT 512,
pre-emphasis 0.97, peak signal normalization, **whole-utterance** feature
normalization and **time-major** layout. Both differ from the AmberNet export
that preceded it, and both fail silently — reusing `makeAmberNetFeatures` here
produces finite, wrong features rather than an error.

`output_1` is unnormalized logits. Every value is negative, which makes them
look like log-probabilities, but `exp()` over the vector sums to ~0.27; treating
them that way reports confidence above 1.0. Apply softmax — it reproduces
upstream `predict_pb` to within rounding.

The labels file pins all 14 output IDs, including English index 1 and Vietnamese
index 12; the order is upstream `vocab/vocab.txt`, which is alphabetical.
Deployment refuses to proceed if any member of this trio is missing; a model
paired with stale labels or frontend parameters is unsafe.

Class 13 is `other`, but it is not a guard: gaussian noise ranks `italian` at
p≈0.72 on this export. The thresholds below are what fail closed.

Classification is fail-closed. The global top-1 class must be `english` or `vietnamese` and
must pass both confidence and top-1/top-2 margin thresholds. Do not renormalize
only the EN and VI logits: doing so would force an unsupported third language
into a false route. Very short, mixed-language, silent, or low-confidence turns
produce no translated audio.

## QNN context contracts

`android-app/app/src/main/assets/npu_manifest.json` lists QNN contexts only.
Host ONNX/tokenizer/voice files are deliberately absent from `stages`; this
keeps the HTP claim auditable.

| Direction | Logical stage | Graph | Input | Output |
|---|---|---|---|---|
| VI→EN | ASR encoder | `graph_oa00nu3e` | `x` FP32 `[1,1000,80]` | `output_0` FP32 `[1,248,512]` |
| EN→VI | ASR encoder | `graph_shbblv7u` | `x` FP32 `[1,1000,80]` | `output_0` FP32 `[1,248,512]` |
| VI→EN | Marian encoder | `opus_mt_encoder` | IDs/mask `[1,256]` | 12 cross-attention tensors |
| VI→EN | Marian decoder | `opus_mt_decoder` | token/position/mask/KV | logits `[1,1,53739]` + KV |
| EN→VI | Marian encoder | `opus_mt_encoder` | IDs/mask `[1,256]` | 12 cross-attention tensors |
| EN→VI | Marian decoder | `opus_mt_decoder` | token/position/mask/KV | logits `[1,1,53685]` + KV |

Both ASR encoders were compiled by QAIRT v2.45.0 for `socModel=43`, Hexagon
v73. The English bundle reports 57.19 ms encoder inference, 93.4 MB peak memory,
and 100% NPU placement in its supplied metadata; on-device integration still
has to validate end-to-end latency and accuracy.

The two Marian binaries reuse the same embedded graph names, so runtime lookup
keys must be direction-qualified even though their logical roles remain
`mt_encoder` and `mt_decoder`. Loading two contexts under an unqualified key
would cause `executeGraph()` to select the first direction.

## Deployed layout

Run the default `auto` plan after installing a debuggable APK:

```bash
tools/models/push-npu-models.sh auto R5CW72TP4EP
```

The script preflights every local source before modifying the phone, verifies
every published file with SHA-256, uses `.part` plus an in-filesDir rename for
large files, and publishes each eSpeak tree only after verifying every file.

```text
files/models/npu/
|-- shared/
|   |-- speechflow_langid.tflite
|   |-- speechflow_labels.json
|   `-- speechflow_contract.json
|-- vi-en/
|   |-- asr_gipformer_encoder.bin
|   |-- asr_gipformer_{decoder,joiner}.onnx
|   |-- asr_gipformer_{tokens,bpe,config}.*
|   |-- mt_{encoder,decoder}.bin
|   |-- mt_{source,target}.spm, mt_vocab.json, mt_config.json
|   |-- tts_cpu.onnx, tts_tokens.txt, tts_voices.bin
|   |-- fallback/asr/{model.onnx,tokens.txt}
|   |-- fallback/mt/{encoder_model.onnx,decoder_model.onnx,source.spm,vocab.json,mt_config.json}
|   `-- espeak-ng-data/
`-- en-vi/
    |-- asr_zipformer_encoder.bin
    |-- asr_zipformer_{decoder,joiner}.onnx
    |-- asr_zipformer_tokens.txt
    |-- mt_{encoder,decoder}.bin
    |-- mt_{source,target}.spm, mt_vocab.json, mt_config.json
    |-- tts_cpu.onnx, tts_tokens.txt, tts_voices.bin
    |-- fallback/asr/{model.onnx,tokens.txt}
    |-- fallback/mt/{encoder_model.onnx,decoder_model.onnx,source.spm,vocab.json,mt_config.json}
    `-- espeak-ng-data/
```

Legacy Parakeet and fixed-window QNN Kokoro contexts are no longer part of the
production deploy plan. Existing old files on a development phone are not
deleted automatically; the script never performs broad cleanup.

## Recording, failure, and memory policy

Capture uses 20 ms frames, 300 ms pre-roll, a one-second end hangover, a
ten-second ASR ceiling, 500 ms overlap at forced boundaries, and a queue of two
utterances. Learned inference never runs on the audio read loop. Capture is
suppressed during playback so the device does not translate its own speaker.

“CPU fallback” is failover, not fail-closed. The safe behavior is:

1. An uncertain language decision always fails closed: no default direction.
2. A QNN ASR/MT exception quarantines that direction's HTP text path for the
   current engine lifetime.
3. Load its reviewed CPU bundle lazily and reprocess the complete captured
   utterance from its PCM boundary; never splice partial NPU and CPU results.
4. If fallback is unavailable or also fails, stop producing speech and expose a
   degraded/error state.

Only fallback assets should be kept cold until needed. Do not eagerly load the
large CPU reference pipelines alongside both Hybrid branches. Measure Android
PSS, HTP load, per-stage latency, and classifier acceptance on the target phone
before deciding whether both Kokoro sessions may remain hot.

The former fixed-window QNN TTS experiment and its audible seam analysis remain
documented for historical reference in
[2026-08-09-qnn-tts-fixed-window-quality.md](incidents/2026-08-09-qnn-tts-fixed-window-quality.md).
