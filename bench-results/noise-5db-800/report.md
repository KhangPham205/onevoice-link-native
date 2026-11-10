# OneVoice benchmark — R5CW72TP4EP

SoC `SM8550` · power mode `burst` ·
800 iterations after 10 warm-up ·
800 rows

Gates from `docs/proposal/sections_5_1_5_2_6_FINAL.md` §5.1.
**2 pass, 3 fail, 4 not evaluated.**

## Gate results

| Gate | Requirement | Observed | Threshold | Verdict |
|---|---|---|---|---|
| G1 | p95 time-to-first-audio <br><sub>§5.1 item 2</sub> | 5374 ms (median 3014, n=800) | ≤ 1500 ms | **FAIL** |
| G2 | p95 end-to-end (advisory) <br><sub>not in §5.1</sub> | 12568 ms (median 6457) | 3000 ms (advisory only) | **N/A** |
| G3 | every HTP stage ran on the accelerator <br><sub>§5.1 item 1</sub> | no profile samples | accel_us > 0 on every HTP stage | **N/A** |
| G4 | process memory ceiling <br><sub>§4.3</sub> | PSS 1408 MB steady, 1408 MB peak | ≤ 1024 MB steady, ≤ 1229 MB transient | **FAIL** |
| G5 | system memory headroom <br><sub>§5.1 item 3</sub> | 16% | ≥ 20% | **FAIL** |
| G6 | 60-minute soak p95 drift <br><sub>§5.1 item 4</sub> | soak not run | ≤ 10% | **N/A** |
| G7 | ASR and TTS real-time factor <br><sub>§5.1 item 2</sub> | ASR p95 0.082, TTS p95 0.938 | both < 1.0 | **PASS** |
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

Production router on every fixture: 800 routed rows, **66 misroutes** (fixtures: en_EV-0182__loom.f32, en_EV-0228__cutting_steam.f32, en_EV-0250__cutting_steam.f32, en_EV-0250__loom.f32, en_EV-0250__workshop.f32, en_EV-0333__cutting_steam.f32, en_EV-0333__loom.f32, en_EV-0333__sewing.f32, en_EV-0333__workshop.f32, en_EV-0523__loom.f32, en_EV-0523__sewing.f32, en_EV-0523__workshop.f32, en_EV-1212__sewing.f32, en_EV-1212__workshop.f32, en_EV-1508__loom.f32, en_EV-1829__loom.f32, en_EV-1829__sewing.f32, en_EV-1829__workshop.f32, en_EV-1863__loom.f32, en_EV-2364__cutting_steam.f32, en_EV-2401__loom.f32, en_EV-2401__sewing.f32, en_EV-2748__cutting_steam.f32, en_EV-2748__loom.f32, en_EV-2748__sewing.f32, en_EV-2748__workshop.f32, en_EV-3791__loom.f32, en_EV-4177__cutting_steam.f32, en_EV-4177__loom.f32, en_EV-4177__workshop.f32, en_EV-4250__loom.f32, en_EV-4294__workshop.f32, en_EV-4321__cutting_steam.f32, en_EV-5269__loom.f32, en_EV-5301__cutting_steam.f32, en_EV-5600__loom.f32, en_EV-5600__sewing.f32, en_EV-5935__loom.f32, en_EV-5935__sewing.f32, en_EV-6054__loom.f32, en_EV-6218__loom.f32, en_EV-6239__cutting_steam.f32, en_EV-6239__loom.f32, en_EV-6239__workshop.f32, en_EV-6502__sewing.f32, en_EV-6602__sewing.f32, en_EV-6618__cutting_steam.f32, en_EV-6618__workshop.f32, en_EV-6960__cutting_steam.f32, en_EV-6960__loom.f32, en_EV-6960__sewing.f32, en_EV-6960__workshop.f32, en_EV-7227__sewing.f32, en_EV-7690__loom.f32, en_EV-7690__workshop.f32, en_EV-8596__cutting_steam.f32, en_EV-8596__workshop.f32, en_EV-8694__workshop.f32, en_EV-9018__workshop.f32, en_EV-9341__cutting_steam.f32, en_EV-9341__loom.f32, en_EV-9341__sewing.f32, en_EV-9341__workshop.f32, en_EV-9953__cutting_steam.f32, vi_VE-5468__sewing.f32, vi_VE-8860__sewing.f32). Decision reasons: margin=751, prior=49. Router rejections appear as lost rows.

## Stage latency

| Stage | median | p90 | p95 | max |
|---|---:|---:|---:|---:|
| ASR (shared frontend + dual QNN encoders + CPU RNN-T) | 231 | 313 | 337 | 377 |
| MT (QNN Marian) | 146 | 332 | 377 | 568 |
| TTS (Kokoro, CPU, whole utterance) | 2602 | 4109 | 4834 | 7005 |
| Queue wait | 0 | 0 | 0 | 0 |
| **TTFA (total compute)** | 3014 | 4742 | 5374 | 7863 |
| e2e (TTFA + audio duration) | 6457 | 10703 | 12568 | 18061 |

Percentiles only — no mean. DVFS ramp and scheduler preemption give this
distribution a long right tail, and a mean would flatter the p95 the gates
actually test.

## TTFA versus utterance length

| Utterance | n | TTFA median | TTFA p95 | vs 1500 ms gate |
|---|---:|---:|---:|---|
| 2s | 56 | 2413 | 3296 | FAIL (2.2×) |
| 3s | 256 | 2829 | 4046 | FAIL (2.7×) |
| 4s | 192 | 2864 | 4307 | FAIL (2.9×) |
| 5s | 76 | 3360 | 5092 | FAIL (3.4×) |
| 6s | 72 | 3047 | 6156 | FAIL (4.1×) |
| 7s | 44 | 3998 | 5690 | FAIL (3.8×) |
| 8s | 24 | 4715 | 7034 | FAIL (4.7×) |
| 9s | 60 | 4379 | 6305 | FAIL (4.2×) |
| 10s | 20 | 5316 | 7863 | FAIL (5.2×) |

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
| PSS | 1408 MB |
| RSS | 962 MB |
| Peak RSS | 1234 MB |
| System headroom | 16% |
| dmabuf delta (load 6 contexts) | — |

DSP allocations never appear in PSS; the dmabuf delta is the only visible
evidence of memory held for the HTP.

### Paging

| Metric | Value |
|---|---:|
| Major faults during run | 222233 |
| Swap at end | 477 MB |

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
