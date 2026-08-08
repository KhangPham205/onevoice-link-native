# Offline Pipeline: CPU/HF and QNN Status

OneVoice currently contains two materially different inference paths. Do not
treat a successful QNN context load as proof that speech-to-speech execution is
implemented.

| Path | Current capability | Model root |
|---|---|---|
| CPU/HF (`HfEngineBackend`) | Complete ASR -> MT -> TTS implementation | `<filesDir>/models/hf` |
| QNN/HTP (`NpuEngine` + native core) | Load and verify QNN contexts only | `<filesDir>/models/npu` |

The dashboard exposes both backends and defaults to **Offline CPU**. Select QNN
only when loading and verifying the device-specific context binaries; the QNN
choice does not yet support live translation.

## CPU/HF data flow

The offline fallback runs entirely on the phone without a network connection:

```text
AudioRecord, 16 kHz mono
  -> bounded energy-VAD segments
  -> sherpa-onnx NeMo CTC ASR
  -> ONNX Runtime MarianMT greedy decoding
  -> sherpa-onnx Kokoro TTS
  -> AudioTrack
```

Only one direction is kept resident to avoid loading both ASR and TTS weight
sets at once on an 8 GB phone:

| Direction | ASR | MT | TTS |
|---|---|---|---|
| `vi-en` | `asr-vi` | `mt-vi-en` | `tts-en` |
| `en-vi` | `asr-en` | `mt-en-vi` | `tts-vi` |

Both directions also need the shared `espeak-ng-data` directory. The local
source layout is `models-hf/<component>`. `MarianTranslator` currently performs
greedy, no-KV-cache decoding and caps conversational output at 128 tokens.

## Stage one direction on a phone

Install and launch a debuggable APK first, then give the script both the
direction and the exact ADB serial. There is deliberately no implicit "first
connected device" fallback.

```bash
tools/models/push-hf-models.sh vi-en R5CW72TP4EP
# or
tools/models/push-hf-models.sh en-vi R5CW72TP4EP
```

The script performs these checks before transfer:

- the selected ASR, MarianMT, target-language TTS, and espeak artifacts exist
  locally and are non-empty;
- the named device is in `device` state;
- `com.onevoice.link` is installed and `run-as` succeeds;
- the device provides `tar` and `sha256sum`.

It streams an uncompressed tar directly into
`/data/user/0/com.onevoice.link/files/models/hf`. It does not create a second
multi-hundred-megabyte archive, use `/data/local/tmp`, clear app data, or remove
the other direction. Existing files with the same names are updated; unrelated
files remain. After extraction, every transmitted file is checked against its
host SHA-256 digest.

With the artifacts currently present in `models-hf`, `vi-en` transfers about
1.08 GiB and `en-vi` about 0.49 GiB, including the shared espeak data. The script
prints the exact byte count before transfer. Make sure the phone has enough free
space.

To use a model root outside the repository, set `HF_MODELS_DIR`:

```bash
HF_MODELS_DIR=/absolute/path/to/models-hf \
  tools/models/push-hf-models.sh vi-en R5CW72TP4EP
```

## Why the QNN path is verify-only

The app can initialize the HTP backend, create one context per `.bin`, and
retrieve the graph declared by `assets/npu_manifest.json`. The native
`QnnRuntime::executeCascade()` still returns `false`: tensor binding,
`graphExecute`, ASR post-processing, Marian autoregressive KV-cache decoding,
and the TTS bridge are not implemented as an Android cascade.

The Kokoro QNN package contains encoder and decoder context binaries, but it is
not a closed executable TTS artifact set. The decoder requires `F0`, `N`,
`asr`, `s`, and `har`; the encoder does not produce all of them. The missing
intermediate prosody/host stage must perform G2P, alignment scatter, F0/N and
`compute_har`, obtain style vectors from a voicepack, bucket decoder calls, and
overlap-add the waveform. Those supporting QNN/runtime artifacts are not staged
by `push-npu-models.sh`. EN -> VI additionally lacks a deployable English ASR
QNN context. Consequently, **Load Model Cascade** proves compatibility and DSP
access only; it cannot produce translated audio.

## RAM limits on the build laptop

This repository deliberately bounds Gradle memory in
`android-app/gradle.properties`:

- Gradle heap: 1 GiB; metaspace: 512 MiB;
- one Gradle worker; parallel execution disabled;
- Kotlin compiles in-process so a separate 2 GiB daemon is not started.

Native LTO linking can still require roughly 3-4 GiB of available system RAM.
Before a native rebuild, ensure at least 4 GiB is available, avoid concurrent
Gradle/Android Studio builds, and do not raise worker count or heap limits on a
memory-constrained laptop. Run the model push separately from the build. The HF
push itself is streaming and has nearly constant host-memory use; model size
primarily affects disk space, USB time, and the phone's runtime memory.
