# Offline bidirectional Hybrid pipeline

OneVoice exposes one production backend: **Hybrid**. There is no backend
selector, direction toggle, Full-QNN mode or CPU-only mode. The source language
of every bounded utterance is chosen by **dual-ASR confidence routing**: both
ASRs score the same PCM and the app compares their per-token log-probability.
`docs/ARCHITECTURE.md` is authoritative; this page is the runtime and
deployment summary.

| Routed source | ASR | Translation | Speech output |
|---|---|---|---|
| Vietnamese | GIPFormer encoder on QNN HTP; RNN-T decoder/joiner on CPU | Marian VI->EN on QNN HTP | whole-utterance Kokoro EN on CPU |
| English | Parakeet-TDT-CTC-110M (CTC head, UFIXED_16) on QNN HTP; greedy CTC on CPU | Marian EN->VI on QNN HTP | whole-utterance VieNeu-TTS v3 Nano on CPU (Kokoro VI / Supertonic-3 by build flag) |
| Both below floor, or tie with no prior, or an ASR branch threw | none | none | none; fail closed |

Each direction retains a cold CPU NeMo-CTC ASR + Marian ONNX bundle under
`fallback/`. It loads only after routing has selected that direction and the
selected Marian text path fails; it then reprocesses the original bounded PCM
from scratch. Partial NPU and CPU output are never spliced, and the fallback is
never used to guess a language.

## Per-utterance data flow

```text
Dashboard audio route (Auto / Phone / Bluetooth), resolved at IDLE -> CAPTURING
  -> AudioRecord: VOICE_COMMUNICATION over SCO, or VOICE_RECOGNITION on the
     built-in mic; the actual routed input is verified
  -> Silero VAD bounds one utterance (pre-roll, hangover, 10 s ceiling)
  -> stop AudioRecord; close SCO on the Bluetooth route
  -> both ASR branches run concurrently on the same PCM
       | VI: Kaldi fbank [1,1000,80] FP32 -> GIPFormer HTP -> CPU RNN-T
       | EN: NeMo log-mel -> UFIXED_16 -> Parakeet HTP -> greedy CTC
  -> DualAsrRouter: floor / margin / EN offset / soft turn prior
  -> selected Marian MT on HTP
  -> whole-utterance CPU TTS at 24 kHz (Supertonic is resampled from 44.1 kHz)
  -> stereo AudioTrack: target VI left, target EN right on Bluetooth;
     both channels on the phone route
```

The audio route selects devices only. Connected-headset inventory and saved
VI/EN preferences in Settings do not select the direction, because Android
exposes a shared TWS pair as one communication mic and cannot say which bud
heard the speaker. See [AURACAST_ROUTING.md](AURACAST_ROUTING.md).

## Routing decision

Each branch reports the sum of emitted lexical-token log-probabilities, token
count and their ratio. `dualAsrEnOffset` is added to the EN score because CTC
peak scores run closer to zero than RNN-T scores. Then:

1. Both scores below `dualAsrFloorThreshold`: fail closed.
2. Difference at least `dualAsrMarginThreshold`: take the higher branch.
3. Smaller difference with no prior: fail closed.
4. Smaller difference with a prior: shift by `dualAsrPriorWeight` toward the
   expected language and take the sign.

The expected language is the opposite of the last translated turn, or the same
language after a forced 10-second boundary (the speaker was cut mid-thought).
Defaults live in `android-app/gradle.properties` and are compiled into
`BuildConfig`: margin `0.15`, floor `-5.0`, EN offset `-0.08`, prior weight
`0.15`. They were fit on 21 synthetic on-device fixtures and are provisional.

`-ProutingMode=LID` builds an A/B APK that uses the retained SpeechFlow TFLite
classifier from `shared/` instead. It is not a UI mode.

## QNN context contracts

`android-app/app/src/main/assets/npu_manifest.json` lists QNN contexts only.
Host ONNX, tokenizer, voice and LID files are deliberately absent from `stages`.

| Direction | Logical stage | Graph | Input | Output |
|---|---|---|---|---|
| VI->EN | ASR encoder | `graph_oa00nu3e` | `x` FP32 `[1,1000,80]` | `output_0` FP32 `[1,248,512]` |
| EN->VI | ASR (CTC) | `model` | `x` UFIXED_16 `[1,1000,80]` | `log_probs` UFIXED_16 `[1,125,1025]` |
| VI->EN | Marian encoder | `opus_mt_encoder` | IDs/mask `[1,256]` | 12 cross-attention tensors |
| VI->EN | Marian decoder | `opus_mt_decoder` | token/position/mask/KV | logits `[1,1,53739]` + KV |
| EN->VI | Marian encoder | `opus_mt_encoder` | IDs/mask `[1,256]` | 12 cross-attention tensors |
| EN->VI | Marian decoder | `opus_mt_decoder` | token/position/mask/KV | logits `[1,1,53685]` + KV |

All six contexts target `socModel=43`, Hexagon v73, and stay resident in both
routing modes. Runtime lookup keys are direction-qualified because both Marian
directions embed identical graph names. Parakeet's fixed-point scale/offset are
read from the binary into the manifest; regenerate it with
`python3 tools/models/gen_npu_manifest.py` for every model drop.

## Deployed layout

```bash
tools/models/push-npu-models.sh auto R5CW72TP4EP
```

The deploy script refuses missing sources before touching the phone, checks
for one SM8550 device and a debuggable install, copies each file through an
app-owned `.part`, verifies SHA-256 and atomically publishes into internal
`filesDir`. See [NPU_DEPLOY.md](NPU_DEPLOY.md).

```text
files/models/npu/
|-- shared/
|   `-- speechflow_{langid.tflite,labels.json,contract.json}   (LID A/B only)
|-- vi-en/
|   |-- asr_gipformer_encoder.bin
|   |-- asr_gipformer_{decoder,joiner}.onnx
|   |-- asr_gipformer_{tokens.txt,bpe.model,config.json}
|   |-- mt_{encoder,decoder}.bin
|   |-- mt_{source,target}.spm, mt_vocab.json, mt_config.json
|   |-- tts_cpu.onnx, tts_tokens.txt, tts_voices.bin        (Kokoro EN)
|   |-- fallback/{asr,mt}/...
|   `-- espeak-ng-data/
`-- en-vi/
    |-- asr_parakeet_ctc.bin
    |-- asr_parakeet_tokens.txt
    |-- mt_{encoder,decoder}.bin
    |-- mt_{source,target}.spm, mt_vocab.json, mt_config.json
    |-- tts_vieneu/      (default VI voice; sea_g2p.bin dictionary inside)
    |-- tts_supertonic/  (-PviTtsEngine=SUPERTONIC)
    |-- tts_cpu.onnx, tts_tokens.txt, tts_voices.bin        (Kokoro VI)
    |-- fallback/{asr,mt}/...
    `-- espeak-ng-data/
```

All three Vietnamese voices are staged so an A/B needs only an APK rebuild. The
app checks only the files its built `viTtsEngine` uses; eSpeak data is needed
only by Kokoro. `en-vi/tts_tokens.txt` is
`tools/models/kokoro-vi-espeak-tokens.txt`, not the upstream table: it aliases
espeak-ng's digit tones to the arrows Kokoro VI was trained on.

VieNeu also needs `libsea_g2p_rs.so` in the APK. Build it with
`tools/models/build-sea-g2p-android.sh`, then produce `models-vieneu/` with
`tools/models/prepare-vieneu-nano.py`. A `VIENEU` build refuses the jniLibs
merge without the library rather than failing at the first turn on device.

## Capture, playback and failure policy

Strict half-duplex: `IDLE -> CAPTURING -> PROCESSING -> PLAYING -> IDLE`, one
in-flight turn, queue capacity one. Capture and playback never overlap, so
there is no forced-boundary overlap or carried tail.

- Capture: 20 ms frames labelled by Silero VAD (32 ms windows, 0.5 onset /
  0.35 offset; `-PvadMode=ENERGY` restores the legacy RMS gate), 300 ms
  pre-roll, one-second end hangover, hard 10-second ceiling.
- Phone route: built-in mic with `VOICE_RECOGNITION` and the loudspeaker. A
  300 ms echo guard after playback warms Silero and fills the pre-roll but
  cannot open a turn, so the loudspeaker tail is ignored while a fast reply
  keeps its first syllable.
- Bluetooth route: `setCommunicationDevice()`, SCO and `VOICE_COMMUNICATION`;
  SCO is cleared before inference. Playback starts on 100 ms of silence and
  must confirm the matching A2DP sink or the turn fails closed.

Fail closed before capture when the Bluetooth route is chosen explicitly and
no single communication headset is usable (Auto falls back to the phone
instead). Fail closed after capture on a floor, tie-without-prior or ASR branch
failure. Every bounded utterance, including rejected ones, writes one
`OneVoiceRouting` JSON row; every state edge writes `OneVoiceHalfDuplex`.

## Demo evidence

The **Live demo & metrics** screen shows per-turn direction, scores, execution
path and stage timings, plus memory, thermal and network state. Each turn is
also appended to a JSONL session log under `<filesDir>/sessions/`
(`SessionRecorder`); **Export** copies it to `Download/OneVoice/` without
`run-as`. `BenchControlReceiver` accepts `com.onevoice.link.BENCH` broadcasts
from adb for the benchmark harness and is inert unless `<filesDir>/bench`
exists.

## Auracast output boundary

Auracast is at most a one-to-many downlink and is never source-language
evidence. A normal APK cannot start or control a broadcast because the system
APIs require `BLUETOOTH_PRIVILEGED`. See [AURACAST_ROUTING.md](AURACAST_ROUTING.md).

History: the former fixed-window QNN Kokoro experiment and its audible seams
are recorded in
[2026-08-09-qnn-tts-fixed-window-quality.md](incidents/2026-08-09-qnn-tts-fixed-window-quality.md).
