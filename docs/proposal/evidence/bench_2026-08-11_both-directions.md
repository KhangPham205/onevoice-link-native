# OneVoice benchmark — R5CW72TP4EP

SoC `SM8550` · power mode `burst` ·
30 iterations after 10 warm-up ·
60 rows

Gates from `docs/proposal/sections_5_1_5_2_6_FINAL.md` §5.1.
**2 pass, 4 fail, 3 not evaluated.**

## Gate results

| Gate | Requirement | Observed | Threshold | Verdict |
|---|---|---|---|---|
| G1 | p95 time-to-first-audio <br><sub>§5.1 item 2</sub> | 32772 ms (median 15644, n=30) | ≤ 1500 ms | **FAIL** |
| G2 | p95 end-to-end (advisory) <br><sub>not in §5.1</sub> | 40372 ms (median 19318) | 3000 ms (advisory only) | **N/A** |
| G3 | every HTP stage ran on the accelerator <br><sub>§5.1 item 1</sub> | 6 stages profiled, all accel_us > 0 | accel_us > 0 on every HTP stage | **PASS** |
| G4 | process memory ceiling <br><sub>§4.3</sub> | PSS 1376 MB steady, 1376 MB peak | ≤ 1024 MB steady, ≤ 1229 MB transient | **FAIL** |
| G5 | system memory headroom <br><sub>§5.1 item 3</sub> | 14% | ≥ 20% | **FAIL** |
| G6 | 60-minute soak p95 drift <br><sub>§5.1 item 4</sub> | soak not run | ≤ 10% | **N/A** |
| G7 | ASR and TTS real-time factor <br><sub>§5.1 item 2</sub> | ASR p95 0.200, TTS p95 4.125 | both < 1.0 | **FAIL** |
| G8 | no low-memory kill, RSS not monotonically rising <br><sub>§4.3</sub> | LOW_MEMORY kill=False, RSS monotonic=no over 12 samples | no kill, no monotonic growth | **PASS** |
| E1 | active/idle power and 8-hour rundown <br><sub>§5.1 item 5 / §5.2</sub> | deferred — Phase B | 5.60 W active, 0.43 W armed-idle, 8 h shift | **N/A** |

### Notes

- **G1**: TTFA == total compute: Kokoro is whole-utterance, so no audio exists until every stage has finished.
- **G2**: No 3.0 s SLA appears in §5.1. Reported for context, not scored.
- **G3**: Measured from QNN_HTP_PROFILE_EVENTTYPE_GRAPH_EXECUTE_ACCEL_TIME_MICROSEC (3004), the vendor's own accelerator timing.
- **G4**: DSP-side allocations are absent from PSS; see the dmabuf delta.
- **G6**: Run with --soak 60. Requires a settled battery; see SKILL.md.
- **G7**: RTF is per second of REAL SPEECH, not per 10 s ASR window. The window definition would make ASR pass trivially by describing the graph shape instead of the wait a speaker experiences.
- **E1**: The device charges over USB throughout Phase A. While plugged, the PMIC feeds the system rail from VBUS, so battery current carries no load signal — not noise, absence of signal.

## Stage latency

| Stage | median | p90 | p95 | max |
|---|---:|---:|---:|---:|
| LID (SpeechFlow, CPU/TFLite) | 244 | 302 | 306 | 308 |
| ASR (QNN encoder + CPU RNN-T) | 361 | 439 | 442 | 446 |
| MT (QNN Marian) | 453 | 840 | 844 | 847 |
| TTS (Kokoro, CPU, whole utterance) | 14580 | 31222 | 31365 | 31417 |
| Queue wait | 0 | 1030 | 4379 | 5462 |
| **TTFA (total compute)** | 15644 | 32657 | 32799 | 32849 |
| e2e (TTFA + audio duration) | 19318 | 40257 | 40399 | 40449 |

Percentiles only — no mean. DVFS ramp and scheduler preemption give this
distribution a long right tail, and a mean would flatter the p95 the gates
actually test.

## TTFA versus utterance length

| Utterance | n | TTFA median | TTFA p95 | vs 1500 ms gate |
|---|---:|---:|---:|---|
| 2s | 20 | 8058 | 10288 | FAIL (6.9×) |
| 3s | 10 | 14186 | 14309 | FAIL (9.5×) |
| 6s | 20 | 25120 | 32849 | FAIL (21.9×) |
| 8s | 10 | 28378 | 28484 | FAIL (19.0×) |

The proposal template's §4.2 table was never filled in (it still carries the
organisers' `[e.g., VITS / MMS-TTS]` placeholders), so there is no published
curve to compare against. This is the measured one.

## HTP placement and host overhead

| HTP stage | n | accel µs (median) | wall µs (median) | host overhead µs | overhead % |
|---|---:|---:|---:|---:|---:|
| `en-vi/asr` | 21 | 65462 | 67459 | 2149 | 3.2% |
| `en-vi/mt_decoder` | 357 | 3179 | 5678 | 2499 | 44.0% |
| `en-vi/mt_encoder` | 21 | 4100 | 5449 | 1345 | 24.7% |
| `vi-en/asr` | 19 | 83996 | 86155 | 2092 | 2.4% |
| `vi-en/mt_decoder` | 243 | 3200 | 5845 | 2636 | 45.1% |
| `vi-en/mt_encoder` | 19 | 4111 | 5527 | 1398 | 25.3% |

`host overhead = wall − accelerator`: buffer binding, FastRPC transport and the
driver round trip. It is the portion the app itself can shrink.

## Memory

| Metric | Value |
|---|---:|
| PSS | 1376 MB |
| RSS | 1258 MB |
| Peak RSS | 1493 MB |
| System headroom | 14% |
| dmabuf delta (load 6 contexts) | — |

DSP allocations never appear in PSS; the dmabuf delta is the only visible
evidence of memory held for the HTP.

### Paging

| Metric | Value |
|---|---:|
| Major faults during run | — |
| Swap at end | 0 MB |

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

- **60-minute thermal soak (§5.1 item 4)** — needs `--soak 60` on a settled battery.
- **Energy: 5.60 W active / 0.43 W armed-idle / 8-hour shift (§5.1 item 5, §5.2)** — Phase B. Requires wireless ADB and a calibrated CURRENT_NOW sign/unit on this handset.
- **Accuracy (WER/BLEU/MOS)** — this harness measures latency, memory and placement only. A fixed fixture proves nothing about transcription or translation quality.
- **Field acoustics** — fixtures are clean studio-style audio. The 95 dB industrial target needs recordings from that environment, plus the headset in the loop.
- **DSP-side memory** — `dumpsys meminfo -d` reported no dmabuf line on this build, so HTP allocations remain unquantified.
