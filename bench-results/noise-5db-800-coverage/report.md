# OneVoice benchmark — R5CW72TP4EP

SoC `SM8550` · power mode `burst` ·
800 iterations after 10 warm-up ·
793 rows

Gates from `docs/proposal/sections_5_1_5_2_6_FINAL.md` §5.1.
**2 pass, 3 fail, 4 not evaluated.**

## Gate results

| Gate | Requirement | Observed | Threshold | Verdict |
|---|---|---|---|---|
| G1 | p95 time-to-first-audio <br><sub>§5.1 item 2</sub> | 5377 ms (median 2967, n=793) | ≤ 1500 ms | **FAIL** |
| G2 | p95 end-to-end (advisory) <br><sub>not in §5.1</sub> | 12416 ms (median 6472) | 3000 ms (advisory only) | **N/A** |
| G3 | every HTP stage ran on the accelerator <br><sub>§5.1 item 1</sub> | no profile samples | accel_us > 0 on every HTP stage | **N/A** |
| G4 | process memory ceiling <br><sub>§4.3</sub> | PSS 1440 MB steady, 1440 MB peak | ≤ 1024 MB steady, ≤ 1229 MB transient | **FAIL** |
| G5 | system memory headroom <br><sub>§5.1 item 3</sub> | 19% | ≥ 20% | **FAIL** |
| G6 | 60-minute soak p95 drift <br><sub>§5.1 item 4</sub> | soak not run | ≤ 10% | **N/A** |
| G7 | ASR and TTS real-time factor <br><sub>§5.1 item 2</sub> | ASR p95 0.082, TTS p95 0.917 | both < 1.0 | **PASS** |
| G8 | no low-memory kill, RSS not monotonically rising <br><sub>§4.3</sub> | LOW_MEMORY kill=False, RSS monotonic=no over 160 samples | no kill, no monotonic growth | **PASS** |
| E1 | active/idle power and 8-hour rundown <br><sub>§5.1 item 5 / §5.2</sub> | deferred — Phase B | 5.60 W active, 0.43 W armed-idle, 8 h shift | **N/A** |

### Notes

- **G1**: TTFA == total compute: Kokoro is whole-utterance, so no audio exists until every stage has finished.
- **G2**: No 3.0 s SLA appears in §5.1. Reported for context, not scored.
- **G3**: Run the profiled cohort; the baseline cohort deliberately leaves profiling off so it does not pay the collection cost.
- **G4**: DSP-side allocations are absent from PSS; see the dmabuf delta.
- **G6**: Run with --soak 60. Requires a settled battery; see SKILL.md.
- **G7**: RTF is per second of REAL SPEECH, not per 10 s ASR window. The window definition would make ASR pass trivially by describing the graph shape instead of the wait a speaker experiences.
- **E1**: The device charges over USB throughout Phase A. While plugged, the PMIC feeds the system rail from VBUS, so battery current carries no load signal — not noise, absence of signal.

## Routing

Production router on every fixture: 793 routed rows, **9 misroutes** (fixtures: en_EV-0250__loom.f32, en_EV-0523__loom.f32, en_EV-0523__workshop.f32, en_EV-1829__sewing.f32, en_EV-1829__workshop.f32, en_EV-1863__loom.f32, en_EV-2401__loom.f32, en_EV-6618__workshop.f32, en_EV-8596__workshop.f32). Decision reasons: coverage=390, coverage_tie=9, margin=390, prior=4. Router rejections appear as lost rows.

## Stage latency

| Stage | median | p90 | p95 | max |
|---|---:|---:|---:|---:|
| ASR (shared frontend + dual QNN encoders + CPU RNN-T) | 229 | 316 | 339 | 394 |
| MT (QNN Marian) | 154 | 335 | 379 | 564 |
| TTS (Kokoro, CPU, whole utterance) | 2580 | 4018 | 4820 | 7138 |
| Queue wait | 0 | 0 | 0 | 0 |
| **TTFA (total compute)** | 2967 | 4623 | 5377 | 7993 |
| e2e (TTFA + audio duration) | 6472 | 10851 | 12416 | 18191 |

Percentiles only — no mean. DVFS ramp and scheduler preemption give this
distribution a long right tail, and a mean would flatter the p95 the gates
actually test.

## TTFA versus utterance length

| Utterance | n | TTFA median | TTFA p95 | vs 1500 ms gate |
|---|---:|---:|---:|---|
| 2s | 56 | 2294 | 3108 | FAIL (2.1×) |
| 3s | 255 | 2715 | 3991 | FAIL (2.7×) |
| 4s | 190 | 2767 | 4279 | FAIL (2.9×) |
| 5s | 76 | 3393 | 5053 | FAIL (3.4×) |
| 6s | 68 | 3190 | 6081 | FAIL (4.1×) |
| 7s | 44 | 3954 | 5794 | FAIL (3.9×) |
| 8s | 24 | 4692 | 7113 | FAIL (4.7×) |
| 9s | 60 | 4468 | 6337 | FAIL (4.2×) |
| 10s | 20 | 5254 | 7993 | FAIL (5.3×) |

The proposal template's §4.2 table was never filled in (it still carries the
organisers' `[e.g., VITS / MMS-TTS]` placeholders), so there is no published
curve to compare against. This is the measured one.

## HTP placement and host overhead

_No profiled cohort in this run._

`host overhead = wall − accelerator`: buffer binding, FastRPC transport and the
driver round trip. It is the portion the app itself can shrink.

## Memory

| Metric | Value |
|---|---:|
| PSS | 1440 MB |
| RSS | 1276 MB |
| Peak RSS | 1440 MB |
| System headroom | 19% |
| dmabuf delta (load 6 contexts) | — |

DSP allocations never appear in PSS; the dmabuf delta is the only visible
evidence of memory held for the HTP.

### Paging

| Metric | Value |
|---|---:|
| Major faults during run | 1211 |
| Swap at end | 204 MB |

A large major-fault count means mmap'd model weights are being evicted and
re-read, so the latency above is partly measuring storage rather than compute.
Both branches stay resident (two Kokoro sessions plus six QNN contexts), which
is what drives the process toward the memory ceiling in the first place.

## Energy

Not measured. Phase A does not collect energy; see SKILL.md.

While the handset is plugged in, the PMIC feeds the system rail directly from
VBUS, so current through the cell reflects charging rather than workload — that
is an absence of signal, not noise to average away. `energy_valid=false` on
every row and the remaining energy columns are empty by construction.

## What this run does not establish

- **No unintended CPU fallback (§5.1 item 1)** — needs the profiled cohort; rerun without `--no-profile-cohort`.
- **60-minute thermal soak (§5.1 item 4)** — needs `--soak 60` on a settled battery.
- **Energy: 5.60 W active / 0.43 W armed-idle / 8-hour shift (§5.1 item 5, §5.2)** — Phase B. Requires wireless ADB and a calibrated CURRENT_NOW sign/unit on this handset.
- **Accuracy (WER/BLEU/MOS)** — this harness measures latency, memory and placement only. A fixed fixture proves nothing about transcription or translation quality.
- **Field acoustics** — fixtures are clean studio-style audio. The 95 dB industrial target needs recordings from that environment, plus the headset in the loop.
- **DSP-side memory** — `dumpsys meminfo -d` reported no dmabuf line on this build, so HTP allocations remain unquantified.
