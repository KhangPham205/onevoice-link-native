#!/usr/bin/env python3
"""Score long-turn replay runs from run_long_replay.py.

Per run (one fixture under one baseline): turns, end reasons, windows, units,
ASR WER/CER of the whole recording against the manifest reference (all turns
joined), word coverage, endpoint -> first translated sample per turn, TTS
starvation/underruns, inference backlog, capture overruns and failures.
Per baseline: aggregate WER/CER and p50/p95 of the post-endpoint latency of
long turns (accepted audio > 10 s) and of all turns.

Text normalisation: lowercase, punctuation removed, whitespace collapsed. The
ASR reference of a synthetic fixture is the concatenation of dataset items;
of a real recording, the script text that was read aloud. WER is only an
ASR proxy: it says nothing about translation adequacy or TTS prosody, which
need listening (docs/LONG_FORM_SPEECH_VALIDATION.md).
"""
from __future__ import annotations

import argparse
import csv
import json
import re
import statistics
import unicodedata
from collections import defaultdict
from pathlib import Path


def normalize(text: str) -> list[str]:
    text = unicodedata.normalize("NFC", text.lower())
    text = re.sub(r"[^\w\s']", " ", text)
    return text.split()


def edit_distance(ref: list[str], hyp: list[str]) -> int:
    previous = list(range(len(hyp) + 1))
    for i, r in enumerate(ref, 1):
        current = [i] + [0] * len(hyp)
        for j, h in enumerate(hyp, 1):
            current[j] = min(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + (r != h))
        previous = current
    return previous[-1]


def percentile(values: list[float], q: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    position = (len(ordered) - 1) * q
    low, high = int(position), min(int(position) + 1, len(ordered) - 1)
    return round(ordered[low] + (ordered[high] - ordered[low]) * (position - low), 1)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("run_dir", type=Path)
    args = parser.parse_args()

    meta = json.loads((args.run_dir / "runs.json").read_text())
    manifest = json.loads(Path(meta["manifest"]).read_text())
    references = {item["id"]: item for item in manifest["items"]}
    rows_by_run: dict[str, list[dict]] = defaultdict(list)
    for line in (args.run_dir / "turns.jsonl").read_text(encoding="utf-8").splitlines():
        if not line.startswith("{"):
            continue
        row = json.loads(line)
        if row.get("type") == "turn" and row.get("replayTag"):
            rows_by_run[row["replayTag"]].append(row)
        elif row.get("type") == "turn" and row.get("tag") and "__" in row["tag"]:
            # Legacy (B0) replay rows carry only the run tag.
            rows_by_run[row["tag"]].append(row)

    per_run = []
    for run in meta["runs"]:
        rows = sorted(rows_by_run.get(run["tag"], []), key=lambda r: r["turnId"])
        item = references[run["id"]]
        hypothesis = " ".join(r.get("transcript") or "" for r in rows)
        ref_words, hyp_words = normalize(item["reference"]), normalize(hypothesis)
        ref_chars, hyp_chars = list("".join(ref_words)), list("".join(hyp_words))
        latencies = [r["latencyMs"]["firstAudio"] for r in rows if r["latencyMs"].get("firstAudio") is not None]
        long_latencies = [r["latencyMs"]["firstAudio"] for r in rows
                          if r["latencyMs"].get("firstAudio") is not None and r["audioMs"] > 10_000]
        long_turn = [r.get("longTurn") or {} for r in rows]
        per_run.append({
            "tag": run["tag"], "id": run["id"], "baseline": run["baseline"], "mode": run["mode"],
            "language": item["language"], "durationS": item["durationS"], "status": run["status"],
            "turns": len(rows),
            "endReasons": "|".join((lt.get("endReason") or ("legacy_forced" if r.get("forcedBoundary") else "legacy"))
                                   for r, lt in zip(rows, long_turn)),
            "acceptedS": round(sum(r["audioMs"] for r in rows) / 1000, 1),
            "errors": sum(1 for r in rows if r.get("error")),
            "wer": round(edit_distance(ref_words, hyp_words) / max(1, len(ref_words)), 4),
            "cer": round(edit_distance(ref_chars, hyp_chars) / max(1, len(ref_chars)), 4),
            "wordCoverage": round(len(hyp_words) / max(1, len(ref_words)), 3),
            "windows": sum(lt.get("windows", 1) for lt in long_turn),
            "forcedCuts": sum(lt.get("forcedCuts", 0) for lt in long_turn),
            "units": sum(lt.get("units", 1) for lt in long_turn),
            "firstAudioMsMax": max(latencies) if latencies else None,
            "firstAudioMsLongTurns": "|".join(str(v) for v in long_latencies),
            "starvedMs": sum(lt.get("starvedMs") or 0 for lt in long_turn),
            "underruns": sum(lt.get("underruns") or 0 for lt in long_turn),
            "maxBacklogMs": max((lt.get("maxBacklogMs", 0) for lt in long_turn), default=0),
            "captureOverruns": sum(lt.get("captureOverrunSuspected", 0) for lt in long_turn),
            "outputAudioS": round(sum((lt.get("outputAudioMs") or r.get("ttsAudioMs") or 0)
                                      for r, lt in zip(rows, long_turn)) / 1000, 1),
            "_latencies": latencies, "_long": long_latencies,
            "_ref": len(ref_words), "_err": edit_distance(ref_words, hyp_words),
        })

    summary = {}
    for baseline in sorted({r["baseline"] for r in per_run}):
        runs = [r for r in per_run if r["baseline"] == baseline]
        all_latency = [v for r in runs for v in r["_latencies"]]
        long_latency = [v for r in runs for v in r["_long"]]
        summary[baseline] = {
            "runs": len(runs),
            "failedRuns": sum(1 for r in runs if r["status"] != "done"),
            "turnsWithError": sum(r["errors"] for r in runs),
            "werAggregate": round(sum(r["_err"] for r in runs) / max(1, sum(r["_ref"] for r in runs)), 4),
            "longTurnFirstAudioP50Ms": percentile(long_latency, 0.5),
            "longTurnFirstAudioP95Ms": percentile(long_latency, 0.95),
            "longTurnSamples": len(long_latency),
            "allTurnFirstAudioP50Ms": percentile(all_latency, 0.5),
            "allTurnFirstAudioP95Ms": percentile(all_latency, 0.95),
            "allTurnSamples": len(all_latency),
            "starvedMs": sum(r["starvedMs"] for r in runs),
            "captureOverruns": sum(r["captureOverruns"] for r in runs),
        }

    fields = [key for key in per_run[0] if not key.startswith("_")] if per_run else []
    with open(args.run_dir / "per_run.csv", "w", newline="", encoding="utf-8") as sink:
        writer = csv.DictWriter(sink, fieldnames=fields, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(per_run)
    (args.run_dir / "summary.json").write_text(json.dumps(summary, indent=2))
    for row in per_run:
        print(f"{row['tag']:<34} turns={row['turns']:<2} acc={row['acceptedS']:>6}s/{row['durationS']}s "
              f"wer={row['wer']:.3f} cov={row['wordCoverage']:.2f} win={row['windows']} units={row['units']} "
              f"firstAudio(long)={row['firstAudioMsLongTurns'] or '-'} starved={row['starvedMs']} err={row['errors']}")
    print(json.dumps(summary, indent=2))
    if per_run:
        print("median WER", statistics.median(r["wer"] for r in per_run))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
