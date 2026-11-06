# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with
code in this repository.

## What this is

OneVoice Link is on-device, utterance-bounded speech-to-speech translation
(Vietnamese ↔ English) for Snapdragon phones. The app exposes one user backend:
**Hybrid**.

- VI→EN: GIPFormer ASR encoder and MarianMT execute on Hexagon HTP; the
  RNN-T decoder/joiner and whole-utterance Kokoro EN execute on CPU.
- EN→VI: Parakeet-TDT-CTC-110M (CTC head, sherpa-onnx SM8550 binary,
  UFIXED_16 I/O) and MarianMT execute on Hexagon HTP; greedy CTC decoding and
  whole-utterance Vietnamese TTS execute on CPU: VieNeu-TTS v3 Nano by
  default, or Kokoro VI / Supertonic-3 with `-PviTtsEngine=KOKORO|SUPERTONIC`.
  Parakeet replaced the LibriSpeech-only Zipformer-small, which failed on
  Vietnamese-accented English.
- The default source-language router is dual ASR, not a headset assignment:
  both ASRs score every bounded utterance and the app compares average emitted-
  token log-probability.
- SpeechFlow LID is retained only for build-time A/B testing with
  `-ProutingMode=LID`. Its assets, JNI frontend and TFLite Select Ops dependency
  must not be removed until the experiment is complete.

The live product premise is two people sharing one JBL stereo TWS pair, one bud
each, and speaking in turns. Android exposes the pair as one communication mic;
it cannot identify which bud supplied an utterance. Connected-headset inventory
and saved VI/EN preferences may remain in Settings, but they do not select the
source direction. Auracast is a possible one-to-many downlink, not source
language evidence. See `docs/AURACAST_ROUTING.md`.

Audio I/O is a persisted Dashboard choice (`AudioRoutePreference`), resolved at
every IDLE → CAPTURING edge: **Auto** (default) uses Bluetooth when exactly one
communication headset is usable, otherwise the phone; **Phone** uses the
built-in mic (`VOICE_RECOGNITION`) and loudspeaker so two people can share one
handset and developers/judges need no headset; a plugged-in USB mic (e.g. a
wireless lavalier receiver, `TYPE_USB_DEVICE`/`USB_HEADSET`) is preferred over
the built-in mic when attached. The tested M31-style receiver is mono (both
transmitters mixed), so it carries no per-speaker channel and routing stays
dual-ASR. `-PaudioInput=USB` locks the build to that USB mic plus the
loudspeaker: Bluetooth is never opened (a reconnecting headset or a stray tap
on the selector cannot take the input), the Dashboard selector is read-only,
and capture fails closed instead of falling back to the built-in mic; **Bluetooth** keeps the HFP/A2DP
contract below and fails closed without a headset. The route selects devices
only, never the translation direction.

CPU fallback ASR/MT models remain cold, whole-utterance recovery assets; CPU
Kokoro is the active target-speech renderer. There is no user-facing CPU-only
or Full-QNN mode. The old fixed-window QNN Kokoro path remains historical
evidence only.

Target hardware is fixed: **arm64-v8a, Snapdragon 8 Gen 2**
(`SM8550` / `kalama` / Hexagon **v73**, `qnnSocModel` 43). No other ABI is built.

## Build and deploy

```bash
cd android-app
./gradlew :app:assembleDebug
./gradlew :app:installDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:test --tests '*ExampleUnitTest*'
```

Model binaries are not in the APK. Stage both Hybrid directions and the retained
SpeechFlow A/B files separately; a debuggable build is required for `run-as`:

```bash
tools/models/push-npu-models.sh auto R5CW72TP4EP
adb logcat -s OneVoiceNpu:* OneVoiceRouting:* OneVoiceHalfDuplex:* OneVoiceQNN:* OneVoiceCore:* adsprpc:V
```

Gradle properties that change runtime behavior:

```bash
-ProutingMode=DUAL_ASR                 # DUAL_ASR (default) or LID
-PdualAsrMarginThreshold=0.15          # provisional natural-log-prob/token margin
-PdualAsrFloorThreshold=-5.0           # provisional per-branch acceptance floor
-PdualAsrEnOffset=-0.08                # EN (CTC) score calibration offset
-PdualAsrPriorWeight=0.15              # soft turn prior, nats/token (>= margin = hard prior)
-PkokoroThreads=4                      # ORT threads per Kokoro session (2/4/5 measured)
-PvadMode=SILERO                       # SILERO (bundled asset) or legacy ENERGY RMS gate
-PaudioInput=DASHBOARD                 # DASHBOARD (code default: Auto/Phone/Bluetooth selector) or USB
                                       # (USB mic + loudspeaker only, never Bluetooth; selector locked;
                                       # fails closed without a USB mic). feat/usb-mic-input-routing sets USB
-PviTtsEngine=VIENEU                   # Vietnamese speech: VIENEU (VieNeu-TTS v3 Nano, default), KOKORO or
                                       # SUPERTONIC (Supertonic-3 int8). VIENEU needs build-sea-g2p-android.sh
                                       # (libsea_g2p_rs.so; the jniLibs merge refuses without it) and
                                       # prepare-vieneu-nano.py (models-vieneu/, staged by push-npu-models.sh)
-PvieneuVoice=minh_quan -PvieneuSteps=16 -PvieneuCfg=3.0 -PvieneuSeed=1234
-PsupertonicVoice=F1 -PsupertonicSteps=5 -PsupertonicSpeed=1.15 -PsupertonicThreads=4
-PlongTurnMode=ADAPTIVE                # ADAPTIVE (default, turns up to 120 s) or LEGACY (one 10 s window)
-PlongTurnSoftSplitMs=7000 -PlongTurnMicroPauseMs=160 -PlongTurnOverlapMs=960 -PlongTurnMaxTurnS=120
-PqnnSignedSkel=/secure/path/libQnnHtpV73Skel.so
-PqnnPdMode=auto                        # auto (default), signed or unsigned
```

Routing values come from `android-app/gradle.properties` and are embedded in
`BuildConfig`, so each tuning APK records its policy. Do not hide threshold
defaults in decoder code.

### Build environment gotchas

- Pin the JDK. Gradle may otherwise pick a VS Code extension JRE without
  `jlink`:
  `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew --no-daemon --no-parallel --max-workers=1 :app:assembleDebug`.
- VS Code Gradle/Java extensions hold Gradle locks and auto-restart when killed.
  Disable them, build in Android Studio, or isolate with
  `GRADLE_USER_HOME=/tmp/gradle-isolated`.
- Do not delete `.cxx` or run `clean` routinely. Prefer incremental builds; a
  clean LTO link may need 3–4 GB free RAM.
- `android-app/gradle.properties` caps Gradle at a 1 GiB heap, one worker, no
  parallel execution and in-process Kotlin. Do not raise these casually: the
  former 4 GiB Gradle plus 2 GiB Kotlin daemon caused the development laptop to
  be OOM-killed.
- Open the Android Studio project at `android-app/`, not the repo root.
- This checkout lives on NTFS with `core.fileMode=false`, so git never sees
  the executable bit: add new scripts with `git add --chmod=+x <file>` or a
  Linux clone gets `Permission denied` (gradlew and tools/*.sh once shipped
  as 100644).

## Architecture

`docs/ARCHITECTURE.md` is authoritative. The essential invariants follow.

### Dual-ASR confidence routing

Every accepted utterance is bounded before inference. In default `DUAL_ASR`
mode, `AutoHybridPipeline` submits both ASR branches concurrently:

- VI: the Kaldi fbank `[1,1000,80]` FP32 frontend is computed once into a
  shared direct buffer; GIPFormer graph `graph_oa00nu3e`, then CPU RNN-T
  decode/join.
- EN: Parakeet needs NeMo log-mel instead, so its task builds its own
  (`nativeExtractAsrFeatures`, per-feature normalized, first 1000 of 1001
  frames), quantizes to UFIXED_16 with the manifest's scale/offset, runs graph
  `model`, and greedy-decodes the quantized `log_probs [1,125,1025]`.

Both report the raw sum of emitted lexical-token log-probabilities (blank and
unknown excluded; for CTC, one term per token run, its peak frame), token count
and `normalizedLogProb = rawLogProb / tokenCount`. CTC peak scores run closer to
zero than RNN-T scores, so `dualAsrEnOffset` is added to the EN score before any
comparison. The decision policy is:

1. Both scores below `dualAsrFloorThreshold`: fail closed.
2. Absolute score difference at least `dualAsrMarginThreshold`: select the
   higher branch.
3. Smaller difference with no prior: fail closed.
4. Smaller difference with a prior: add `dualAsrPriorWeight` toward the expected
   language and take the sign (`soft_margin` when the scores overrule the
   prior, `prior` otherwise). A hard prior discarded real score leads.

The expected language is the opposite of the last successfully translated turn,
except after a hard 10-second boundary: that speaker was cut mid-thought, so
the same language is expected next. Length
normalization removes short-string bias, but it does not calibrate two models
with different token vocabularies or model families. The defaults (`0.15`
margin, `-0.08` EN offset, `-5.0` floor) were fit on 21 synthetic on-device
fixtures and are provisional; tune them from labeled real-speaker logs.

If either dual-routing ASR branch throws, confidence comparison is incomplete
and the utterance fails closed. Do not fabricate a CPU confidence. Once routing
has selected a direction, a selected Marian text-path failure quarantines that
NPU text path and cold-restarts CPU ASR+MT from the original bounded PCM. Do not
splice partial NPU and CPU output. If recovery also fails, fail closed.

`-ProutingMode=LID` loads the retained SpeechFlow TFLite model from `shared` and
selects a direction before the normal Hybrid branch. LID is an APK-level A/B
flag, not a UI mode, and all six QNN contexts remain resident in either mode.

### Long turns (ADAPTIVE, default)

`longTurnMode=ADAPTIVE` (the default) keeps the microphone open across ASR windows for
turns up to 120 s: windows are cut at pauses (or forced at the 10 s contract
with 960 ms grid-aligned overlap), recognized and translated while the speaker
talks, and played after the turn ends on one AudioTrack. The first window is
routed once and the direction locked; the prior is committed once per turn.
ADAPTIVE became the default on 2026-11-05, before every gate in
docs/LONG_FORM_SPEECH_VALIDATION.md passed (live mic, TWS and soak are still
open); `-PlongTurnMode=LEGACY` is the one-rebuild rollback. Spec: `docs/LONG_FORM_SPEECH_IMPLEMENTATION.md`; architecture:
`docs/ARCHITECTURE.md` "Long turns"; replay harness and recording scripts:
`tools/eval/longturn/` (`cmd=replay`, `baseline=B0..B3`). Every statement
below about one bounded utterance per turn describes LEGACY, or one ASR window
under ADAPTIVE.

### Strict half-duplex audio

The production state machine is:

`IDLE → CAPTURING → PROCESSING → PLAYING → IDLE`

- `CAPTURING`: Bluetooth route: select the shared communication device, open
  SCO and `AudioRecord(VOICE_COMMUNICATION)`. Phone route: prefer the built-in
  mic with `AudioRecord(VOICE_RECOGNITION)` and hold a 300 ms echo guard: that
  audio warms Silero and fills the pre-roll but cannot open a turn, so the
  loudspeaker tail is ignored while someone answering at once keeps their
  first syllable (`UtteranceSegmenter`). Either way, verify the actual input
  route. There is no playback.
- `PROCESSING`: stop/release `AudioRecord`; on the Bluetooth route call
  `clearCommunicationDevice()` to close SCO (the phone route never claims one).
  Then run ASR→routing→MT→TTS. Before submitting translated PCM, start a stereo
  `AudioTrack` on 100 ms of silence and poll `getRoutedDevice()`: Bluetooth
  must confirm the matching A2DP sink or fail closed; the phone route logs a
  non-speaker route instead of dropping the turn. Content is appended behind
  that silence (no pause/flush: the loudspeaker path applied flush late).
- `PLAYING`: drain the whole stereo waveform. Capture cannot reopen until the
  state returns to `IDLE`.

There is one in-flight turn and a queue capacity of one. Capture and playback
must never overlap. Capture uses 20 ms frames labelled by Silero VAD (32 ms
windows, 0.5 onset / 0.35 offset; legacy RMS gate via `-PvadMode=ENERGY`),
300 ms pre-roll, a one-second end hangover and a hard 10-second ceiling. The strict half-duplex path has no
forced-boundary overlap or carried tail.

CPU TTS returns whole-utterance mono at 24 kHz (Supertonic renders 44.1 kHz;
`resamplePcm` band-limits it to 24 kHz and `trimSilence` cuts its ~0.6 s lead
and ~0.7 s tail padding). On the Bluetooth route,
interleave it into stereo with the unused channel set to zero: target
Vietnamese (`en-vi`) is **left** and target English (`vi-en`) is **right**. On
the phone route both channels carry the waveform.

### Resident QNN contexts and threading

The two directions use exactly six resident context binaries:

- `vi-en/asr`, `vi-en/mt_encoder`, `vi-en/mt_decoder`
- `en-vi/asr`, `en-vi/mt_encoder`, `en-vi/mt_decoder`

Direction-qualified runtime keys are mandatory because both Marian directions
embed `opus_mt_encoder` and `opus_mt_decoder`. Initialization fails and releases
partial state unless all six load.

The two ASR host tasks are submitted concurrently. `QnnRuntime` still guards
all `graphExecute` calls with one global `executionMutex`; encoder execution on
HTP is therefore serial, while one branch's CPU RNN-T decoder can overlap the
other branch's HTP encoder. This is intentional. `qnn_runtime.cpp` was not
changed to assert QNN concurrency that the current shared profiling/lifecycle
state cannot safely support.

`OneVoiceNpu` logs a memory snapshot after six contexts load and another after
host glue loads. `peak_rss_kb` is app-process `VmHWM`; it does not include all
DSP-private memory.

### JNI and QNN loading

`native-core/src/main_jni.cpp` binds Kotlin direct buffers to
`QnnRuntime::executeGraph` and implements the ASR frontends.
`core/OneVoiceNative.kt` is its mirror. Changing JNI arity or argument order on
one side without the other is an ABI break. The obsolete Oboe
`executeCascade()` worker is not used by Hybrid.

`QnnRuntime` creates the backend/device once and one context per stage. Each
load reports three distinct checks:

1. `QnnSystemContext_getBinaryInfo`: parse graph name, tensor contract,
   `socModel` and build metadata without touching hardware.
2. `contextCreateFromBinary`: place the context on HTP.
3. `graphRetrieve(graphName)`: prove graph identity.

Graph names are not guessable and are not guaranteed by filenames.
`assets/npu_manifest.json` is generated from the binaries with
`python3 tools/models/gen_npu_manifest.py`; regenerate it for every model drop.

QNN libraries are `dlopen`'d, never linked. `ADSP_LIBRARY_PATH` must be set
before `libQnnHtp.so` first loads. The Hexagon skeleton is a DSP6 asset extracted
to `<filesDir>/dsp/{signed,unsigned}/`, not an arm64 `jniLib`. Models live under
internal `filesDir`, and the deploy script uses `run-as`; files placed under
external `Android/data` by another uid may be invisible on Android 16 even when
the printed path and ownership appear correct.

### Logs required for routing work

`OneVoiceRouting` writes one JSON row per bounded utterance, including rejected
turns: timestamp, audio duration, VI/EN raw score, token count and normalized
score, current prior, final decision and reason, execution path/error, plus
capture setup, SCO close, queue wait, frontend, dual-ASR wall, MT, TTS, A2DP
restore, route poll, playback and total post-bound latency.

`OneVoiceHalfDuplex` writes every state edge with turn id, monotonic timestamp,
dwell time and reason, plus route-switch measurements. Keep these tags stable;
they are the calibration contract. Long turns add `schema: 2` rows with a
`longTurn` object (legacy fields keep their meaning) and per-window/per-unit
rows under `OneVoiceLongTurn`.

## Current status: read before debugging inference

- Hybrid is the only user-facing backend. `DUAL_ASR` is the default router;
  SpeechFlow remains staged for `LID` A/B builds. Do not reintroduce a runtime
  mode picker or use headset language assignments as source direction.
- VI ASR is GIPFormer `graph_oa00nu3e` (FP32 `[1,1000,80]` → `[1,248,512]`)
  with CPU RNN-T decoder/joiner ONNX host glue. EN ASR is Parakeet graph
  `model` (UFIXED_16 `[1,1000,80]` → `[1,125,1025]`, QAIRT 2.40 binary that
  loads on the in-tree 2.47 runtime). The manifest records fixed-point scale/offset
  from the binary; `StageSpec.quantization` carries them to the host.
- Kokoro VI was trained on vig2p phonemes (tones as arrows) but sherpa-onnx
  feeds espeak-ng `vi` (tones as digits, đ as ɗ), which it silently dropped.
  `tools/models/kokoro-vi-espeak-tokens.txt` aliases them (round-trip CER
  9.8% → 0.0%). Deploy that table, never the upstream tokens.txt.
- VieNeu-TTS v3 Nano (default, staged under `en-vi/tts_vieneu/`) is a 48M
  flow-matching model ported from the `vieneu` 3.8.3 SDK to ONNX Runtime Java
  (`VieNeuNanoTts`): 16 Euler steps, CFG 3.0, a fixed noise seed so a render
  is reproducible. Text goes through sea-g2p (Rust, `libsea_g2p_rs.so` behind
  the `sea_g2p_jni` dlopen shim), which also spells out digits, dates and
  units. On the 91 EN→VI dataset turns: Whisper round-trip WER 1.8% (Kokoro
  6.4%, Supertonic 5.3%), first audio p50 3.06 s (4.30 s), PSS on par with
  Kokoro. Not yet checked: naturalness (MOS) and English names inside
  Vietnamese sentences, which upstream flags as a Nano weakness. Apache-2.0.
- Supertonic-3 (`-PviTtsEngine=SUPERTONIC`, staged under `en-vi/tts_supertonic/`)
  reads graphemes, but its `unicode_indexer` only knows NFKD text and
  sherpa-onnx 1.13.4 decomposes just a few Latin-1 accents: precomposed
  Vietnamese vowels (ơ, ư, ạ, ữ, ệ...) were silently dropped (round-trip CER
  31.9%). `SupertonicFiles.prepareText` NFKD-normalizes first. Its duration
  predictor over-allocates time on Vietnamese and the flow fills the slack
  with repeated words, hence `supertonicSpeed=1.15` (CER 4.9% at 1.0 → 0.8–3.0%
  over two runs; Kokoro 2.9%). It fixes Kokoro's tone/`th` errors and saves
  ~600 MB peak RSS, but output is random per call and still skips or repeats
  a word in roughly one sentence in six. Weights are OpenRAIL-M.
- Both Marian directions execute on HTP. Their repeated logical graph names
  require direction-qualified loaded-context keys.
- CPU TTS (Kokoro EN, VieNeu VI) is whole-utterance by design to preserve
  continuous prosody. Do not restore the old fixed-window Full-QNN TTS selector.
- With the Bluetooth route selected, a missing/ambiguous communication headset
  fails closed before capture; Auto falls back to the phone route instead. A floor/no-prior/ASR-branch failure fails closed after capture. A
  selected MT failure may take the atomic CPU whole-utterance recovery path.
- Output is stereo within one shared TWS pair: Vietnamese left, English right.
  This does not prove per-bud mic identity or independent output to unrelated
  headsets.

### QNN `0x36b1` incident guidance

The former `contextCreateFromBinary` `0x36b1` failure is resolved. `0x36b1` is
`QNN_DEVICE_ERROR_INVALID_CONFIG`; it did not prove a signature-policy failure.
The observed chain was `libcdsprpc.so not found` → FastRPC remote
symbols/transport failed → QNN surfaced 14001. Because the app targets API
36, the manifest must declare:

```xml
<uses-native-library android:name="libcdsprpc.so" />
```

- Verified on the connected SM8550 device on 2026-08-08: unsigned-PD control
  returned 0 and the then-current QNN contexts loaded (`socModel=43`, binary
  build v2.45.0).
- The working path uses QAIRT's unsigned v73 skeleton. A testsig is not required
  for this app path. Hexagon TestSig is for debug-fused/test devices; it is not
  a production trust anchor for a locked retail phone.
- If 14001 recurs, inspect preceding QNN/FastRPC transport logs first. Check
  `libcdsprpc` visibility, unsigned-control result, skeleton lookup and
  stub/skel versions; never infer "needs signed skel" from the number alone.
- The DSP serial comes from
  `adb shell cat /sys/devices/soc0/serial_number` or the SDK's `getserial`, not
  the ADB/USB serial.
- `ADSP_LIBRARY_PATH` controls DSP-side lookup. It does not grant SELinux
  permission or bypass signature validation.

Full postmortem:
`docs/incidents/2026-08-08-qnn-0x36b1-fastrpc-linker-policy.md`. See also
`docs/QNN_SIGNED_DSP.md` and `docs/NPU_DEPLOY.md`.

Full offline runtime and deployment notes: `docs/OFFLINE_PIPELINE.md`.

## Conventions

- The QNN SDK (`native-core/third_party/qnn_sdk/`) and model binaries
  (`onevoice-npu-bins/`, `models-*/`) are git-ignored, large and
  license-restricted. The core still compiles without the SDK; QNN calls become
  logged stubs behind `#if __has_include(<QnnInterface.h>)`.
- Keep headers SDK-agnostic. QNN handles live behind the `State` pimpl in the
  `.cpp`, so callers need no QNN headers.
- Comments should explain why, especially uid visibility, `dlopen` ordering,
  fail-closed routing, half-duplex route proof and non-fatal `deviceCreate`.
- Branch from `main` for each feature (`feat/...`) and merge back into `main`.
  `dev` is an older integration branch and is behind `main`.
