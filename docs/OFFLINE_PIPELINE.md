# Offline speech-to-speech pipelines

OneVoice has three fully offline modes. **Hybrid** is the default VI → EN path;
**Offline CPU** is the bidirectional reference; **Full QNN** keeps all six
learned VI → EN stages on Hexagon HTP for validation and optimization.

| Backend | Direction | Learned inference | Status |
|---|---|---|---|
| Hybrid (default) | VI → EN | QNN ASR + MT; whole-utterance CPU Kokoro | Live, recommended |
| Offline CPU | VI → EN, EN → VI | sherpa-onnx + ONNX Runtime | Live reference |
| Full QNN | VI → EN | Six QNN graphs on HTP | Live, TTS quality-limited |
| Hybrid / Full QNN | EN → VI | Missing English ASR context | Disabled |

## Hybrid live data flow

```text
AudioRecord + bounded VAD
  -> native NeMo-compatible frontend -> QNN ASR
  -> SentencePiece -> QNN Marian encoder/decoder
  -> translated sentence -> sherpa-onnx Kokoro once for the whole utterance
  -> one bounded 24 kHz waveform -> AudioTrack
```

Hybrid deliberately combines the two execution mechanisms. ASR and MT use the
continuous QNN graph contracts supplied for those stages. Kokoro stays on CPU
because the unified model sees the complete phoneme sequence and generates one
continuous waveform, preserving prosody and decoder state across the sentence.

## QNN live data flow

```text
AudioRecord, 16 kHz mono
  -> 20 ms frames, 300 ms pre-roll, energy VAD, 1 s silence hangover
  -> native NeMo-compatible 80-mel frontend
  -> QNN ASR -> CTC collapse/detokenize
  -> SentencePiece -> QNN Marian encoder
  -> QNN Marian decoder loop with persistent KV cache
  -> exact eSpeak/Kokoro token IDs via the tiny frontend bridge -> QNN encoder
  -> duration/alignment host math -> QNN learned prosody
  -> native harmonic source/STFT -> QNN decoder in 25-frame buckets
  -> phase-continuous HAR + overlap/crossfade stitching -> AudioTrack, 24 kHz mono
```

The following learned stages run on HTP:

| Stage | Graph |
|---|---|
| Vietnamese ASR | `graph_08xlh5ri` |
| Marian encoder | `opus_mt_encoder` |
| Marian autoregressive decoder | `opus_mt_decoder` |
| Kokoro encoder | `graph_5l17io1h` |
| Kokoro learned prosody | `onevoice_kokoro_en_prosody_w8a8` |
| Kokoro waveform decoder | `graph_93073co3` |

Small deterministic transforms remain on CPU: audio framing/VAD, mel features,
tokenization, CTC collapse, MT argmax/cache placement, alignment, harmonic STFT,
bucket stitching, and audio I/O. This does not make the backend a CPU model
path; all neural-network inference is performed by the QNN contexts on HTP.

## Recording and playback boundaries

The QNN ASR graph has a fixed ten-second input window. Capture therefore uses:

- 20 ms frames and a 300 ms circular pre-roll so initial consonants are kept;
- an RMS threshold of `0.008`, at least 120 ms of voiced audio, and one second
  of trailing silence before ending an utterance;
- a ten-second hard ASR window with 500 ms carry-over when continuous speech
  must be split.

The Full-QNN TTS path accepts up to 750 alignment frames (30 decoder buckets),
about 18.75 seconds of 24 kHz audio. Harmonic phase and RNG state continue
across buckets, and overlap/crossfade stitching reduces hard boundary clicks.
It cannot recreate the unified Kokoro decoder's whole-utterance temporal
context, however, so audible rhythm/timbre discontinuities can remain even when
every PCM frame is delivered. Hybrid avoids that boundary entirely.

These details are load-bearing. Reducing the hangover, removing pre-roll,
resetting HAR per bucket, or restoring the former 200-frame TTS cap introduces
additional missing-word or truncation failures. It does not resolve the
inherent fixed-window quality difference; use Hybrid for smooth output.

## Deploy the QNN artifacts

Build and install a debuggable APK, then target the device explicitly:

```bash
cd android-app
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
  ./gradlew --no-daemon --no-parallel --max-workers=1 :app:assembleDebug
adb -s R5CW72TP4EP install -r -t app/build/outputs/apk/debug/app-debug.apk
cd ..
tools/models/push-npu-models.sh vi-en R5CW72TP4EP
```

The script stages contexts and frontend assets into the app's internal
`files/models/npu/vi-en` directory through `run-as`. It skips an existing file
when the byte size matches and never clears app data.

## Device validation

The instrumented smoke test does more than load contexts: it executes ASR,
translates a long Vietnamese sentence, synthesizes it, validates finite PCM,
and sends it through Android `AudioTrack`.

```bash
cd android-app
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
  ./gradlew --no-daemon --no-parallel --max-workers=1 \
  :app:assembleDebug :app:assembleDebugAndroidTest
adb -s R5CW72TP4EP install -r -t app/build/outputs/apk/debug/app-debug.apk
adb -s R5CW72TP4EP install -r -t \
  app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s R5CW72TP4EP shell am instrument -w \
  com.onevoice.link.test/androidx.test.runner.AndroidJUnitRunner
```

On the target SM8550 device, `AudioTrack` delivered every requested frame in
both modes. For the same sentence, Full-QNN synthesis took about 1.2 seconds and
used three decoder buckets; Hybrid whole-utterance CPU Kokoro took about 2.7
seconds. The faster QNN result remained audibly less smooth, which isolates the
problem to bucketed synthesis rather than playback loss.

## Current limitations

- EN → VI QNN is disabled because no deployable English ASR `.bin` exists.
- Full-QNN English Kokoro uses exact eSpeak phonemization. A tiny bridge ONNX
  model exposes sherpa-onnx's normal Kokoro token IDs without loading the full
  CPU Kokoro network; learned synthesis still executes in QNN.
- Full-QNN Kokoro decoding is fixed at 25 alignment frames per context call.
  Host overlap/crossfade cannot fully reproduce whole-utterance decoder state.
- The ASR context is statically limited to ten seconds per inference. Long
  continuous speech is deliberately segmented with overlap, not fed as one
  unbounded tensor.

## Host RAM policy

Gradle is bounded to a 1 GiB heap, one worker, and no parallel execution.
Native linking can still need several GiB; avoid concurrent Android Studio or
Gradle builds. Runtime tensor buffers are persistent and reused. Full QNN does
not load the 345 MB unified Kokoro ONNX model; Hybrid intentionally loads one
CPU Kokoro instance and releases it when the backend changes.

Phân tích đầy đủ về artifact TTS fixed-window, bằng chứng thiết bị và yêu cầu
re-export dành cho team model nằm tại
[Sự cố chất lượng TTS QNN](incidents/2026-08-09-qnn-tts-fixed-window-quality.md).
