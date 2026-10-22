"""Pass/fail evaluation for the OneVoice acceptance gates.

Thresholds come from docs/proposal/sections_5_1_5_2_6_FINAL.md §5.1, which is
the authoritative list — the §4.2 table in the PDF template is unfilled
boilerplate from the organisers and states no OneVoice numbers at all.

Anything measured but not required by §5.1 is reported without a verdict rather
than invented into a gate.
"""
from __future__ import annotations

import statistics
from dataclasses import dataclass
from typing import Any, Iterable, Sequence

# §5.1 item 2. Whole-utterance Kokoro means playback cannot start before every
# stage has finished, so TTFA equals total compute — see SKILL.md.
TTFA_P95_MS = 1500.0
# Not a §5.1 gate. Recorded because a reader will ask, marked advisory so it is
# never presented as a requirement the proposal made.
E2E_ADVISORY_MS = 3000.0
PSS_STEADY_MB = 1024.0
PSS_TRANSIENT_MB = 1229.0
MEM_HEADROOM_PCT = 20.0
SOAK_DRIFT_PCT = 10.0
RTF_MAX = 1.0


def percentile(values: Sequence[float], pct: float) -> float:
    """Nearest-rank percentile; no interpolation, no scipy dependency."""
    if not values:
        return float("nan")
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, round(pct / 100.0 * len(ordered) + 0.5) - 1))
    return ordered[index]


def summarize(values: Sequence[float]) -> dict[str, float]:
    """
    Median/p90/p95, deliberately no mean.

    DVFS ramps and scheduler preemption give this distribution a long right
    tail; a mean would be dragged by outliers that a user experiences as rare
    stalls, and would flatter the p95 the gate actually cares about.
    """
    clean = [v for v in values if v == v]  # drop NaN
    if not clean:
        return {"n": 0, "median": float("nan"), "p90": float("nan"),
                "p95": float("nan"), "min": float("nan"), "max": float("nan")}
    return {
        "n": len(clean),
        "median": statistics.median(clean),
        "p90": percentile(clean, 90),
        "p95": percentile(clean, 95),
        "min": min(clean),
        "max": max(clean),
    }


@dataclass
class GateResult:
    gate: str
    title: str
    source: str
    passed: bool | None      # None = could not be evaluated
    observed: str
    threshold: str
    note: str = ""

    @property
    def verdict(self) -> str:
        if self.passed is None:
            return "N/A"
        return "PASS" if self.passed else "FAIL"


def _float(row: dict[str, Any], key: str) -> float:
    try:
        return float(row.get(key, "nan"))
    except (TypeError, ValueError):
        return float("nan")


def evaluate(
    rows: Sequence[dict[str, Any]],
    qnn_samples: Sequence[dict[str, Any]] = (),
    memory: dict[str, Any] | None = None,
    soak_windows: Sequence[float] = (),
    low_memory_kill: bool | None = None,
    rss_series: Sequence[float] = (),
) -> list[GateResult]:
    memory = memory or {}
    results: list[GateResult] = []

    # ---- G1: p95 TTFA -----------------------------------------------------
    ttfa = summarize([_float(r, "ttfa_ms") for r in rows])
    results.append(GateResult(
        gate="G1", title="p95 time-to-first-audio",
        source="§5.1 item 2",
        passed=None if ttfa["n"] == 0 else ttfa["p95"] <= TTFA_P95_MS,
        observed=f"{ttfa['p95']:.0f} ms (median {ttfa['median']:.0f}, n={ttfa['n']})",
        threshold=f"≤ {TTFA_P95_MS:.0f} ms",
        note="TTFA == total compute: Kokoro is whole-utterance, so no audio "
             "exists until every stage has finished.",
    ))

    # ---- G2: e2e (advisory) ----------------------------------------------
    e2e = summarize([_float(r, "e2e_ms") for r in rows])
    results.append(GateResult(
        gate="G2", title="p95 end-to-end (advisory)",
        source="not in §5.1",
        passed=None,
        observed=f"{e2e['p95']:.0f} ms (median {e2e['median']:.0f})",
        threshold=f"{E2E_ADVISORY_MS:.0f} ms (advisory only)",
        note="No 3.0 s SLA appears in §5.1. Reported for context, not scored.",
    ))

    # ---- G3: no unintended CPU fallback ----------------------------------
    if qnn_samples:
        by_stage: dict[str, list[int]] = {}
        for sample in qnn_samples:
            by_stage.setdefault(str(sample.get("stage", "?")), []).append(
                int(sample.get("accelerator_us", 0) or 0))
        offenders = [s for s, values in by_stage.items() if not values or min(values) <= 0]
        results.append(GateResult(
            gate="G3", title="every HTP stage ran on the accelerator",
            source="§5.1 item 1",
            passed=not offenders,
            observed=(f"{len(by_stage)} stages profiled, "
                      f"{'all accel_us > 0' if not offenders else 'zero on ' + ', '.join(offenders)}"),
            threshold="accel_us > 0 on every HTP stage",
            note="Measured from QNN_HTP_PROFILE_EVENTTYPE_GRAPH_EXECUTE_ACCEL_"
                 "TIME_MICROSEC (3004), the vendor's own accelerator timing.",
        ))
    else:
        results.append(GateResult(
            gate="G3", title="every HTP stage ran on the accelerator",
            source="§5.1 item 1", passed=None,
            observed="no profile samples", threshold="accel_us > 0 on every HTP stage",
            note="Run the profiled cohort; the baseline cohort deliberately "
                 "leaves profiling off so it does not pay the collection cost.",
        ))

    # ---- G4: memory ceiling ----------------------------------------------
    pss_mb = float(memory.get("pss_kb", 0)) / 1024.0
    peak_mb = float(memory.get("peak_pss_kb", memory.get("pss_kb", 0))) / 1024.0
    results.append(GateResult(
        gate="G4", title="process memory ceiling",
        source="§4.3",
        passed=None if pss_mb <= 0 else (pss_mb <= PSS_STEADY_MB and peak_mb <= PSS_TRANSIENT_MB),
        observed=f"PSS {pss_mb:.0f} MB steady, {peak_mb:.0f} MB peak",
        threshold=f"≤ {PSS_STEADY_MB:.0f} MB steady, ≤ {PSS_TRANSIENT_MB:.0f} MB transient",
        note="DSP-side allocations are absent from PSS; see the dmabuf delta.",
    ))

    # ---- G5: system headroom ---------------------------------------------
    headroom = float(memory.get("mem_headroom_pct", float("nan")))
    results.append(GateResult(
        gate="G5", title="system memory headroom",
        source="§5.1 item 3",
        passed=None if headroom != headroom else headroom >= MEM_HEADROOM_PCT,
        observed=f"{headroom:.0f}%" if headroom == headroom else "unknown",
        threshold=f"≥ {MEM_HEADROOM_PCT:.0f}%",
    ))

    # ---- G6: soak drift ---------------------------------------------------
    if len(soak_windows) >= 2:
        first, worst = soak_windows[0], max(soak_windows)
        drift = (worst - first) / first * 100.0 if first > 0 else float("nan")
        results.append(GateResult(
            gate="G6", title="60-minute soak p95 drift",
            source="§5.1 item 4",
            passed=drift <= SOAK_DRIFT_PCT,
            observed=f"{drift:+.1f}% ({first:.0f} → {worst:.0f} ms)",
            threshold=f"≤ {SOAK_DRIFT_PCT:.0f}%",
        ))
    else:
        results.append(GateResult(
            gate="G6", title="60-minute soak p95 drift",
            source="§5.1 item 4", passed=None,
            observed="soak not run", threshold=f"≤ {SOAK_DRIFT_PCT:.0f}%",
            note="Run with --soak 60. Requires a settled battery; see SKILL.md.",
        ))

    # ---- G7: real-time factors -------------------------------------------
    asr = summarize([_float(r, "asr_rtf") for r in rows])
    tts = summarize([_float(r, "tts_rtf") for r in rows])
    both_known = asr["n"] > 0 and tts["n"] > 0
    results.append(GateResult(
        gate="G7", title="ASR and TTS real-time factor",
        source="§5.1 item 2",
        passed=None if not both_known else (asr["p95"] < RTF_MAX and tts["p95"] < RTF_MAX),
        observed=f"ASR p95 {asr['p95']:.3f}, TTS p95 {tts['p95']:.3f}",
        threshold=f"both < {RTF_MAX:.1f}",
        note="RTF is per second of REAL SPEECH, not per 10 s ASR window. The "
             "window definition would make ASR pass trivially by describing "
             "the graph shape instead of the wait a speaker experiences.",
    ))

    # ---- G8: no low-memory kill, no monotonic RSS growth ------------------
    growing = False
    if len(rss_series) >= 4:
        # Monotonic in the practical sense: every later window above the one
        # before it. A single dip is normal GC behaviour, not a leak.
        growing = all(b > a for a, b in zip(rss_series, rss_series[1:]))
    results.append(GateResult(
        gate="G8", title="no low-memory kill, RSS not monotonically rising",
        source="§4.3",
        passed=None if low_memory_kill is None else (not low_memory_kill and not growing),
        observed=(f"LOW_MEMORY kill={low_memory_kill}, "
                  f"RSS monotonic={'yes' if growing else 'no'} "
                  f"over {len(rss_series)} samples"),
        threshold="no kill, no monotonic growth",
    ))

    # ---- Energy: explicitly deferred -------------------------------------
    results.append(GateResult(
        gate="E1", title="active/idle power and 8-hour rundown",
        source="§5.1 item 5 / §5.2",
        passed=None,
        observed="deferred — Phase B",
        threshold="5.60 W active, 0.43 W armed-idle, 8 h shift",
        note="The device charges over USB throughout Phase A. While plugged, "
             "the PMIC feeds the system rail from VBUS, so battery current "
             "carries no load signal — not noise, absence of signal.",
    ))
    return results


def overall(results: Iterable[GateResult]) -> tuple[int, int, int]:
    """(passed, failed, not-evaluated)"""
    scored = list(results)
    return (
        sum(1 for r in scored if r.passed is True),
        sum(1 for r in scored if r.passed is False),
        sum(1 for r in scored if r.passed is None),
    )
