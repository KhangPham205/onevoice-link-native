---
name: onevoice-bench
description: Measure OneVoice Link on-device latency, HTP placement and memory on a connected Snapdragon 8 Gen 2 phone, and score the results against the §5.1 acceptance gates. Use when asked to benchmark this app, measure TTFA/end-to-end latency, check whether a stage really ran on the NPU, investigate memory headroom or a thermal soak, or produce evidence for the technical proposal's performance claims. Not for accuracy (WER/BLEU/MOS) and not for energy — energy is Phase B and deliberately unimplemented.
---

# OneVoice benchmark

Runs fixed audio fixtures through the real Hybrid pipeline on a real device,
collects per-stage timings plus QNN accelerator counters, and scores them
against the acceptance gates.

## Before anything else: two facts that shape every number

**TTFA equals total compute.** The CPU TTS (Kokoro EN; VieNeu-TTS v3 Nano for
Vietnamese by default, or Kokoro VI / Supertonic-3 by `-PviTtsEngine`)
synthesises the whole utterance in one call (`NpuPipeline.synthesize`), and
playback only starts once the full waveform exists. Nothing reaches the speaker
earlier, so there is no meaningful "first chunk" time:

```
ttfa_ms = asr + mt + tts
e2e_ms  = ttfa_ms + duration_of_the_synthesised_audio
```

Measured on R5CW72TP4EP, TTS is **~80–86% of TTFA**. Any latency work that does
not touch TTS is rounding error.

**Routing is on the critical path.** In a routed run (`--routing auto`, the
default) both ASR branches run before MT, so `asr_ms` is the shared frontend
plus the dual-ASR wall clock, not one encoder. QNN `graphExecute` is serialized,
so the two HTP encoders never overlap; only one branch's CPU decode overlaps the
other's encoder. In a `-ProutingMode=LID` build SpeechFlow instead runs
synchronously before ASR (16–45 ms) and its time is **not** inside `asr_ms`; it
is logged as `lid.latencyMs` in the `OneVoiceRouting` row.

## Gate source

Gates come from `docs/proposal/sections_5_1_5_2_6_FINAL.md` §5.1.

`docs/proposal/1V_P2_TechProposal_Template.pdf` is the organisers' **blank
template** — its §4.2 still contains `[e.g., VITS / MMS-TTS]` placeholders and
publishes no OneVoice latency numbers. Do not cite a §4.2 TTFA table; there
isn't one. The report emits a measured TTFA-versus-length table intended to fill
that section in.

| Gate | Requirement | Threshold | §5.1 |
|---|---|---|---|
| G1 | p95 TTFA | ≤ 1500 ms | item 2 |
| G2 | p95 e2e | advisory only — no such gate exists | — |
| G3 | every HTP stage ran on the accelerator | `accel_us > 0` | item 1 |
| G4 | process memory | ≤ 1024 MB steady, ≤ 1229 MB transient | §4.3 |
| G5 | system memory headroom | ≥ 20% | item 3 |
| G6 | 60-minute soak p95 drift | ≤ 10% | item 4 |
| G7 | ASR and TTS real-time factor | both < 1.0 | item 2 |
| G8 | no LOW_MEMORY kill, RSS not monotonic | — | §4.3 |
| E1 | energy / 8-hour rundown | **deferred, Phase B** | item 5, §5.2 |

### RTF definition — declared once, used everywhere

**RTF is per second of real speech**, not per 10-second ASR window:

```
asr_rtf = asr_ms / (speech_duration_s * 1000)
tts_rtf = tts_ms / (tts_output_audio_s * 1000)
```

The window definition would make ASR pass trivially (350 ms / 10 s = 0.035) by
describing the graph shape rather than the wait a speaker experiences. The
consequence to keep in mind: the encoder takes a static `[1,1000,80]` window, so
`asr_ms` is roughly constant regardless of utterance length. Short utterances
therefore show a *high* RTF even though nothing changed — that is real
(a 0.3 s utterance genuinely costs the same as a 9 s one), not an artefact.

## Running it

The app must be installed (debuggable), models pushed, and the Hybrid pipeline
already loaded in the UI.

```bash
# One-time per install: arm the benchmark surface.
adb -s R5CW72TP4EP shell run-as com.onevoice.link mkdir -p files/bench

# Default run: both directions, 10 warm-up + 30 iterations, two cohorts.
python3 .claude/skills/onevoice-bench/scripts/bench.py --serial R5CW72TP4EP

# Report.
python3 .claude/skills/onevoice-bench/scripts/report.py bench-results \
  --out bench-results/report.md
```

Useful flags: `--iterations N`, `--direction vi-en|en-vi|both` (which fixtures
to feed; both directions stay resident either way),
`--routing auto|explicit` (production router, or force the fixture language's
branch), `--power-mode burst|sustained_high_performance|balanced`,
`--no-profile-cohort` (faster, but forfeits G3 evidence).

### Why two cohorts

A QNN profile handle costs real time inside `graphExecute`. Latency gates are
therefore read from the **baseline** cohort, which never enables profiling, and
accelerator evidence from the **profiled** cohort. Cohort order is shuffled and
separated by a cooldown so a thermal trend cannot be mistaken for a cohort
difference.

Warm-up is **10**, not 3: the HTP governor ramps across the first several
executions and a shorter warm-up leaves that ramp inside the measured
population.

### Power mode is part of the measurement

Every row carries `power_mode`. A latency number without it is unreadable —
`burst` pins the core voltage corner to TURBO and disables sleep, which is the
only way successive runs are comparable. The harness applies it before each
cohort via `QnnHtpPerfInfrastructure`.

## What it measures

Per utterance (`bench.csv`): stage timings, `ttfa_ms`, `e2e_ms`, and the
explanatory variables without which those timings cannot be compared —
`mt_output_tokens` (the Marian decoder is autoregressive), `tts_output_audio_s`
(TTS cost scales with audio produced), `speech_duration_s`,
`forced_boundary`, `route_source` (the routing reason: `explicit`, `margin`,
`soft_margin`, `prior`, `lid`), `execution_path`.

`hangover_ms` (1000) and `echo_guard_ms` (300) are reported **beside** e2e, not
inside it. They are fixed VAD policy; folding them in would make a genuine model
speed-up look like no change at all. `e2e_minus_hangover_ms` is the derived
column.

Per HTP execute (`qnn.csv`): `wall_us`, `execute_us`, `accelerator_us`,
`queue_wait_us` and `host_overhead_us = wall − accelerator`. The last one is the
actionable number: buffer binding, FastRPC transport and driver round trip, all
of which the app controls. Measured baseline — ASR encoder ~6% overhead, Marian
decoder ~27%.

Event IDs are **not hardcoded from documentation**. They come from
`native-core/third_party/qnn_sdk/include/QNN/{QnnProfile.h,HTP/QnnHtpProfile.h}`,
and the first execute of a process logs each event's `identifier`, `type` and
`unit` verbatim so the contract can be checked rather than trusted. On this
device (QAIRT 2.47.0) the useful one is `3004`
`GRAPH_EXECUTE_ACCEL_TIME_MICROSEC` — `"Accelerator (execute) time"` — the
vendor's own measurement. Generic `EXECUTE_DEVICE` (407) is accepted as a
fallback but is not emitted here.

## Phase B — Energy (deferred, not implemented)

Phase A collects **no** energy data. Every row has `energy_valid=false` and the
remaining energy columns are empty; the schema keeps them so Phase B needs no
migration.

While the phone is plugged in, the PMIC supplies the system rail directly from
VBUS. Current through the cell then reflects charging, not workload. That is an
absence of signal, not noise — averaging it produces a fabricated number.

**Never run `dumpsys battery unplug`.** It mocks only the framework's view; the
hardware keeps charging and the fuel gauge keeps reporting a misleading current.
`device.py` refuses to execute it and raises instead. If it ever appears in this
repo, delete it.

When Phase B is implemented, it must:

1. **Go wireless.** `adb pair` / `adb connect`, then physically unplug. USB must
   be out, not merely mocked.
2. **Hold WiFi constant.** WiFi stays on for both the idle baseline and the
   loaded measurement, so its draw subtracts out instead of appearing as
   workload cost.
3. **Buffer on device.** Write results to `filesDir` and drain afterwards.
   Streaming logcat during a measurement adds radio and CPU load to the very
   thing being measured.
4. **Calibrate first.** Confirm the sign and unit of
   `BatteryManager.BATTERY_PROPERTY_CURRENT_NOW` on this exact handset — some
   Samsung builds report µA, some mA, and the sign convention for discharge
   varies. Verify against a known load before trusting any derived watt.
5. **Settle before starting.** Battery ≥ 99% and charge current near zero;
   charging heat otherwise contaminates both energy and latency.

## Soak

```bash
python3 .claude/skills/onevoice-bench/scripts/bench.py --serial R5CW72TP4EP --soak 60
```

Computes p95 over a 10-minute sliding window and reports drift. It **aborts with
a reason** if the battery is below 99% or charge current has not settled —
charging heat is indistinguishable from workload heat, and a soak that cannot
separate them proves nothing.

## Known environment traps

**ntfs3 inode corruption.** This repo sits on an NTFS partition. Directory reads
under `android-app/app/build/intermediates/cxx/Debug/<hash>/logs/arm64-v8a`
intermittently deadlock in the kernel (`iterate_dir`, D-state, unkillable), which
hangs `buildCMakeDebug` indefinitely. `stat` still works; only `readdir` hangs.
Workaround — rename the parent out of the way (rename needs no readdir):

```bash
mv android-app/app/build/intermediates/cxx/Debug/<hash>{,.bad}
```

**Broadcast reachability.** `BenchControlReceiver` must be
`android:exported="true"`. A non-exported receiver has its shell broadcast
dropped before it is even enqueued on this build, even when `-n` names the
component, and a `signature`-level `android:permission` silently swallows it
too. Access is instead gated on `<filesDir>/bench` existing, which requires
`run-as` and therefore a debuggable build.

**Debuggable target.** Macrobenchmark wants a non-debuggable, `profileable`
target, but pushing models through `run-as` needs debuggable. This skill does
not use Macrobenchmark — it measures inference latency, not startup, so the
framework buys nothing here. Consequence: ART runs without some optimisations,
so reported latency is a **pessimistic upper bound**.

**Uninstalling wipes the models.** `adb uninstall` clears `filesDir` and costs a
~1.7 GB re-push. Prefer `install -r`.

## Fixtures

`fixtures/*.f32` — little-endian float32, 16 kHz mono, the exact representation
the capture loop hands the inference worker.

| File | Language | Duration | Origin |
|---|---|---|---|
| `vi_real_3.0s.f32` | Vietnamese | 3.04 s | real microphone capture |
| `vi_synth_1.6s.f32` | Vietnamese | 1.63 s | on-device Kokoro VI |
| `vi_synth_5.8s.f32` | Vietnamese | 5.80 s | on-device Kokoro VI |
| `en_synth_2.2s.f32` | English | 2.18 s | on-device Kokoro EN |
| `en_synth_5.7s.f32` | English | 5.73 s | on-device Kokoro EN |
| `en_synth_7.9s.f32` | English | 7.90 s | on-device Kokoro EN |

All six were verified with SpeechFlow LID at confidence 1.0000 when created. A
routed run checks each decision against the language in the filename and
reports misroutes; a misroute or rejected utterance signals a regression, not a
weak fixture. Regenerate with the `synth` command (see `BenchControlReceiver`;
it uses the installed APK's TTS, so new VI fixtures would come from VieNeu, not
Kokoro VI) only if a model changes — fixtures must stay byte-identical across
runs or the percentiles are not comparable.

They are clean synthetic speech. They establish latency and placement; they say
nothing about accuracy or about the 95 dB industrial environment.

## Memory pressure distorts latency — check this before blaming a model

Both directions are always resident: six QNN contexts, the VI RNN-T
decoder/joiner, Kokoro EN, the Vietnamese TTS engine (VieNeu by default) and,
only in LID builds, SpeechFlow. On the 2026-11-02 dataset run that measured
PSS ~1.6 GB, peak RSS ~1.7 GB, with ~25% system headroom
(`docs/proposal/evidence/P3_ket-qua-do_2026-11-02.md`).

An earlier build (two Kokoro sessions, two transducer pairs, LID resident)
reached system headroom of 13–17%, and the kernel started paging. The symptom
was a TTS RTF that looked model-specific but was not:

| Where | Model | RTF |
|---|---|---|
| Host CPU, 2 threads | Kokoro EN | 0.605 |
| Host CPU, 2 threads | Kokoro VI | 0.578 |
| Device, vi-en branch | Kokoro EN | 0.676 |
| Device, en-vi branch | Kokoro VI | **3.92** |

On the host the two models are within 5% of each other, so the 6× gap is not the
Vietnamese model being slow. The device tells the real story:

```
VmRSS   1322628 kB      VmSwap  198220 kB
majflt   258843         MemAvailable  994796 kB
```

258k **major** faults means mmap'd model pages are being evicted and re-read on
every inference. Whichever branch ran least recently pays for it.

Before concluding anything about a model, sample `/proc/<pid>/stat` field 12
(`majflt`) and `VmSwap`. If majflt is climbing across a run, the latency numbers
are measuring paging, not compute — and G4/G5 failing is the cause, not a
separate finding. `--direction` only filters fixtures and does not unload the
other direction, so it cannot relieve the pressure.

## Interpreting a failure

- **G1 fails** — look at `tts_ms` first; it dominates. Streaming TTS per
  sentence is the structural fix, at the cost of the prosody continuity that
  whole-utterance synthesis buys.
- **G3 fails on one stage** — that stage silently fell back to CPU. Check
  `graphRetrieve` in the load log and confirm `npu_manifest.json` matches the
  binaries.
- **G7 fails on ASR for short utterances only** — expected, and real. The static
  window means a 0.3 s utterance costs what a 9 s one does.
- **Rows missing** — the harness prints a timeout per lost utterance. A silently
  short population would move the p95, so lost rows are always reported.
