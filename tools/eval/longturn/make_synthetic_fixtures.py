#!/usr/bin/env python3
"""Build synthetic long turns by concatenating Round 3 sample items.

Mechanics fixtures only: one speaker per item, studio-clean, joined by short
seeded gaps. They exercise windowing, merge, unit building and playback on
the device, but they are not real long-form speech and must never back a
quality claim (docs/LONG_FORM_SPEECH_IMPLEMENTATION.md section 12.3). Real
recordings come from docs/LONG_FORM_RECORDING_SCRIPT.md.

Output: <out>/<id>.wav (16 kHz mono PCM16) and <out>/manifest.json with the
reference source text of every item (the concatenated item references).
"""
from __future__ import annotations

import argparse
import array
import json
import math
import random
import sys
import wave
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from dataset import load_items  # noqa: E402

RATE = 16_000


def read_pcm(path: Path) -> bytes:
    with wave.open(str(path)) as w:
        if (w.getframerate(), w.getnchannels(), w.getsampwidth()) != (RATE, 1, 2):
            raise ValueError(f"{path}: expected 16 kHz mono PCM16")
        return w.readframes(w.getnframes())


def trim(pcm: bytes, threshold: float = 0.01, margin_ms: int = 80) -> bytes:
    """Drop the item's own lead/tail silence (20 ms RMS frames), keeping a margin.

    Dataset wavs carry ~0.3-0.6 s of silence at each end; joined as-is, those
    plus the gap exceed the 1 s end-of-turn silence and split one "long turn"
    into many short ones.
    """
    samples = array.array("h", pcm)
    frame = RATE // 50
    loud = [
        index for index in range(len(samples) // frame)
        if math.sqrt(sum(s * s for s in samples[index * frame:(index + 1) * frame]) / frame) / 32768 >= threshold
    ]
    if not loud:
        return pcm
    margin = RATE * margin_ms // 1000
    start = max(0, loud[0] * frame - margin)
    end = min(len(samples), (loud[-1] + 1) * frame + margin)
    return samples[start:end].tobytes()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--seconds", type=int, nargs="+", default=[30, 60, 120])
    parser.add_argument("--per-length", type=int, default=2, help="fixtures per language and length")
    parser.add_argument("--gap-ms", type=int, nargs=2, default=[150, 550],
                        help="seeded gap range between items; keep below the 1 s end-of-turn silence")
    parser.add_argument("--seed", type=int, default=20261104)
    args = parser.parse_args()

    rng = random.Random(args.seed)
    items = load_items(args.dataset)
    args.out.mkdir(parents=True, exist_ok=True)
    manifest = {"kind": "synthetic_concat", "seed": args.seed, "gapMs": args.gap_ms, "items": []}
    for lang in ("vi", "en"):
        pool = [item for item in items if item.source_lang == lang]
        for seconds in args.seconds:
            for copy in range(args.per_length):
                rng.shuffle(pool)
                pcm, parts, total = bytearray(), [], 0
                for item in pool:
                    if total >= seconds * RATE:
                        break
                    if parts:
                        gap = rng.randint(*args.gap_ms) * RATE // 1000
                        pcm += bytes(gap * 2)
                        total += gap
                    audio = trim(read_pcm(item.wav))
                    pcm += audio
                    total += len(audio) // 2
                    parts.append(item)
                fixture_id = f"syn_{lang}_{seconds}s_{copy}"
                with wave.open(str(args.out / f"{fixture_id}.wav"), "wb") as w:
                    w.setnchannels(1)
                    w.setsampwidth(2)
                    w.setframerate(RATE)
                    w.writeframes(bytes(pcm))
                manifest["items"].append({
                    "id": fixture_id,
                    "file": f"{fixture_id}.wav",
                    "language": lang,
                    "durationS": round(total / RATE, 2),
                    "speaker": "dataset_mixed",
                    "split": "mechanics",
                    "reference": " ".join(item.source_text for item in parts),
                    "referenceTranslation": " ".join(item.target_text for item in parts),
                    "sourceItems": [item.id for item in parts],
                })
                print(f"{fixture_id}: {total / RATE:.1f} s from {len(parts)} items")
    (args.out / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
