#!/usr/bin/env python3
"""Render a benchmark run as markdown.

Reads the artifacts bench.py wrote and produces a report that states what was
measured, what each gate concluded, and — importantly — which proposal claims
the run does NOT establish. A benchmark that only lists its passes is a
marketing document.
"""
from __future__ import annotations

import argparse
import csv
import json
import sys
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gates  # noqa: E402


def load(results_dir: Path) -> tuple[dict, list[dict], list[dict]]:
    summary = json.loads((results_dir / "summary.json").read_text())
    with (results_dir / "bench.csv").open() as handle:
        rows = list(csv.DictReader(handle))
    qnn: list[dict] = []
    qnn_path = results_dir / "qnn.csv"
    if qnn_path.is_file():
        with qnn_path.open() as handle:
            qnn = list(csv.DictReader(handle))
    return summary, rows, qnn


def fmt(value: float, digits: int = 0) -> str:
    return "—" if value != value else f"{value:.{digits}f}"


def stage_table(rows: list[dict]) -> str:
    columns = [
        ("asr_ms", "ASR (shared frontend + dual QNN encoders + CPU RNN-T)"),
        ("mt_ms", "MT (QNN Marian)"),
        ("tts_ms", "TTS (Kokoro, CPU, whole utterance)"),
        ("queue_wait_ms", "Queue wait"),
        ("ttfa_ms", "**TTFA (total compute)**"),
        ("e2e_ms", "e2e (TTFA + audio duration)"),
    ]
    lines = ["| Stage | median | p90 | p95 | max |", "|---|---:|---:|---:|---:|"]
    for key, label in columns:
        values = [float(r[key]) for r in rows if r.get(key, "").replace(".", "", 1).isdigit()]
        stats = gates.summarize(values)
        lines.append(
            f"| {label} | {fmt(stats['median'])} | {fmt(stats['p90'])} | "
            f"{fmt(stats['p95'])} | {fmt(stats['max'])} |")
    return "\n".join(lines)


def qnn_table(qnn: list[dict]) -> str:
    if not qnn:
        return "_No profiled cohort in this run._"
    grouped: dict[str, list[dict]] = defaultdict(list)
    for sample in qnn:
        grouped[sample["stage"]].append(sample)
    lines = [
        "| HTP stage | n | accel µs (median) | wall µs (median) | "
        "host overhead µs | overhead % |",
        "|---|---:|---:|---:|---:|---:|",
    ]
    for stage, samples in sorted(grouped.items()):
        accel = gates.summarize([float(s["accelerator_us"]) for s in samples])
        wall = gates.summarize([float(s["wall_us"]) for s in samples])
        host = gates.summarize([float(s["host_overhead_us"]) for s in samples])
        pct = (host["median"] / wall["median"] * 100.0) if wall["median"] else float("nan")
        lines.append(
            f"| `{stage}` | {len(samples)} | {fmt(accel['median'])} | "
            f"{fmt(wall['median'])} | {fmt(host['median'])} | {fmt(pct, 1)}% |")
    return "\n".join(lines)


def ttfa_by_length(rows: list[dict]) -> str:
    """
    TTFA against utterance length.

    The proposal template's §4.2 latency table was never filled in, so there is
    no published curve to compare against. This is the measured one, offered as
    the thing that should populate it.
    """
    buckets: dict[str, list[float]] = defaultdict(list)
    for row in rows:
        try:
            seconds = float(row["speech_duration_s"])
            ttfa = float(row["ttfa_ms"])
        except (KeyError, ValueError):
            continue
        label = f"{round(seconds)}s"
        buckets[label].append(ttfa)
    if not buckets:
        return "_No rows._"
    lines = ["| Utterance | n | TTFA median | TTFA p95 | vs 1500 ms gate |",
             "|---|---:|---:|---:|---|"]
    for label in sorted(buckets, key=lambda x: float(x.rstrip("s"))):
        stats = gates.summarize(buckets[label])
        verdict = "PASS" if stats["p95"] <= gates.TTFA_P95_MS else \
            f"FAIL ({stats['p95'] / gates.TTFA_P95_MS:.1f}×)"
        lines.append(f"| {label} | {stats['n']} | {fmt(stats['median'])} | "
                     f"{fmt(stats['p95'])} | {verdict} |")
    return "\n".join(lines)


def unproven_claims(summary: dict, qnn: list[dict]) -> str:
    """Claims the run cannot support, and what each would need."""
    items: list[str] = []
    if not qnn:
        items.append("**No unintended CPU fallback (§5.1 item 1)** — needs the "
                     "profiled cohort; rerun without `--no-profile-cohort`.")
    if not any(g["gate"] == "G6" and g["verdict"] != "N/A" for g in summary["gates"]):
        items.append("**60-minute thermal soak (§5.1 item 4)** — needs "
                     "`--soak 60` on a settled battery.")
    items.append("**Energy: 5.60 W active / 0.43 W armed-idle / 8-hour shift "
                 "(§5.1 item 5, §5.2)** — Phase B. Requires wireless ADB and a "
                 "calibrated CURRENT_NOW sign/unit on this handset.")
    items.append("**Accuracy (WER/BLEU/MOS)** — this harness measures latency, "
                 "memory and placement only. A fixed fixture proves nothing "
                 "about transcription or translation quality.")
    items.append("**Field acoustics** — fixtures are clean studio-style audio. "
                 "The 95 dB industrial target needs recordings from that "
                 "environment, plus the headset in the loop.")
    if summary.get("dmabuf_delta_kb") is None:
        items.append("**DSP-side memory** — `dumpsys meminfo -d` reported no "
                     "dmabuf line on this build, so HTP allocations remain "
                     "unquantified.")
    return "\n".join(f"- {item}" for item in items)


def routing_section(summary: dict) -> str:
    routing = summary.get("routing")
    if not routing or routing.get("mode") != "auto":
        return "Explicit branch per fixture; the router was not exercised."
    reasons = ", ".join(f"{k}={v}" for k, v in routing["reasons"].items()) or "none"
    misrouted = ", ".join(routing["misrouted_fixtures"]) or "none"
    return (f"Production router on every fixture: {routing['routed_rows']} routed rows, "
            f"**{routing['misroutes']} misroutes** (fixtures: {misrouted}). "
            f"Decision reasons: {reasons}. Router rejections appear as lost rows.")


def render(results_dir: Path) -> str:
    summary, rows, qnn = load(results_dir)
    passed, failed, skipped = (
        sum(1 for g in summary["gates"] if g["verdict"] == "PASS"),
        sum(1 for g in summary["gates"] if g["verdict"] == "FAIL"),
        sum(1 for g in summary["gates"] if g["verdict"] == "N/A"),
    )
    gate_lines = ["| Gate | Requirement | Observed | Threshold | Verdict |",
                  "|---|---|---|---|---|"]
    for gate in summary["gates"]:
        gate_lines.append(
            f"| {gate['gate']} | {gate['title']} <br><sub>{gate['source']}</sub> | "
            f"{gate['observed']} | {gate['threshold']} | **{gate['verdict']}** |")

    notes = [f"- **{g['gate']}**: {g['note']}" for g in summary["gates"] if g.get("note")]
    memory = summary.get("memory", {})
    dmabuf = summary.get("dmabuf_delta_kb")

    return f"""# OneVoice benchmark — {summary['device']}

SoC `{summary['soc']}` · power mode `{summary['power_mode']}` ·
{summary['iterations']} iterations after {summary['warmup']} warm-up ·
{len(rows)} rows

Gates from `docs/proposal/sections_5_1_5_2_6_FINAL.md` §5.1.
**{passed} pass, {failed} fail, {skipped} not evaluated.**

## Gate results

{chr(10).join(gate_lines)}

### Notes

{chr(10).join(notes)}

## Routing

{routing_section(summary)}

## Stage latency

{stage_table(rows)}

Percentiles only — no mean. DVFS ramp and scheduler preemption give this
distribution a long right tail, and a mean would flatter the p95 the gates
actually test.

## TTFA versus utterance length

{ttfa_by_length(rows)}

The proposal template's §4.2 table was never filled in (it still carries the
organisers' `[e.g., VITS / MMS-TTS]` placeholders), so there is no published
curve to compare against. This is the measured one.

## HTP placement and host overhead

{qnn_table(qnn)}

`host overhead = wall − accelerator`: buffer binding, FastRPC transport and the
driver round trip. It is the portion the app itself can shrink.

## Memory

| Metric | Value |
|---|---:|
| PSS | {memory.get('pss_kb', 0) / 1024:.0f} MB |
| RSS | {memory.get('rss_kb', 0) / 1024:.0f} MB |
| Peak RSS | {memory.get('peak_rss_kb', 0) / 1024:.0f} MB |
| System headroom | {memory.get('mem_headroom_pct', '—')}% |
| dmabuf delta (load 6 contexts) | {'—' if dmabuf is None else f'{dmabuf / 1024:.1f} MB'} |

DSP allocations never appear in PSS; the dmabuf delta is the only visible
evidence of memory held for the HTP.

### Paging

| Metric | Value |
|---|---:|
| Major faults during run | {summary.get('majflt_delta', '—')} |
| Swap at end | {summary.get('paging_after', {}).get('swap_kb', 0) / 1024:.0f} MB |

A large major-fault count means mmap'd model weights are being evicted and
re-read, so the latency above is partly measuring storage rather than compute.
Both branches stay resident (two Kokoro sessions plus six QNN contexts), which
is what drives the process toward the memory ceiling in the first place.

## Energy

Not measured. {summary['energy']['reason']}.

While the handset is plugged in, the PMIC feeds the system rail directly from
VBUS, so current through the cell reflects charging rather than workload — that
is an absence of signal, not noise to average away. `energy_valid=false` on
every row and the remaining energy columns are empty by construction.

## What this run does not establish

{unproven_claims(summary, qnn)}
"""


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("results", nargs="?", default="bench-results")
    parser.add_argument("--out", default=None, help="write markdown here")
    args = parser.parse_args()

    results_dir = Path(args.results)
    if not (results_dir / "summary.json").is_file():
        print(f"error: {results_dir}/summary.json not found — run bench.py first",
              file=sys.stderr)
        return 2
    markdown = render(results_dir)
    if args.out:
        Path(args.out).write_text(markdown)
        print(f"wrote {args.out}")
    else:
        print(markdown)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
