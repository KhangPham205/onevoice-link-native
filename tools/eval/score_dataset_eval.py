#!/usr/bin/env python3
"""Score a run_dataset_eval.py output directory against the dataset references.

Metrics, and why each one:

* Routing accuracy, with the production prior and score-only. Alternating
  turns let the soft prior help; the score-only figure is what the dual-ASR
  scores alone would have decided, the conservative number.
* ASR WER (Vietnamese also CER) on correctly routed items. Vietnamese words are
  single syllables separated by spaces, so WER is a syllable error rate.
* End-to-end speech translation: BLEU and chrF++ (lexical) and COMET
  wmt22-comet-da (semantic, the Brief's "semantic accuracy"). Failed turns
  count as empty output in the "all items" figures; nothing is dropped
  silently.
* TTS intelligibility: an independent recognizer (Whisper large-v3-turbo on
  the workstation) transcribes the on-device waveform; error is measured
  against the text the TTS was asked to speak. The same recognizer on the
  human dataset audio gives its own error floor for comparison.
* Latency, RTF, memory and model size from the run's own rows and samples.

Text normalisation for WER/BLEU/chrF: Unicode NFC, lowercase, digits spelled
out (num2words, per language), punctuation to spaces, whitespace collapsed,
Vietnamese "ngàn" == "nghìn". The EN->VI references are already normalised
this way; applying it to both sides keeps casing and punctuation from being
scored as translation errors. COMET sees the raw text.
"""
from __future__ import annotations

import argparse
import csv
import json
import math
import re
import sys
import tarfile
import unicodedata
import wave
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from dataset import load_items  # noqa: E402

DIRECTION_OF = {"vi": "vi-en", "en": "en-vi"}
TARGET_LANG = {"vi-en": "en", "en-vi": "vi"}


def normalize(text: str | None, lang: str) -> str:
    """
    Compare words, not formatting. The references spell numbers out ("một
    nghìn chín trăm"), while MT and Whisper often emit digits, so digits are
    verbalised with num2words on both sides. "ngàn" and "nghìn" are the
    Southern and Northern words for thousand, a dialect choice, not an error.
    """
    from num2words import num2words
    if not text:
        return ""
    text = unicodedata.normalize("NFC", text).lower()
    text = re.sub(r"(?<=\d)[.,](?=\d{3}\b)", "", text)  # 1,000 / 1.000 -> 1000
    text = re.sub(r"\d+", lambda m: f" {num2words(int(m.group()), lang=lang)} ", text)
    text = "".join(" " if unicodedata.category(c).startswith("P") else c for c in text)
    words = text.split()
    if lang == "vi":
        words = ["nghìn" if w == "ngàn" else w for w in words]
    return " ".join(words)


def percentile(values: list[float], pct: float) -> float:
    """Nearest rank, identical to the bench harness's gates.percentile."""
    if not values:
        return float("nan")
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, round(pct / 100.0 * len(ordered) + 0.5) - 1))
    return ordered[index]


def summarize(values: list[float]) -> dict[str, float]:
    return {"n": len(values), "p50": percentile(values, 50), "p90": percentile(values, 90),
            "p95": percentile(values, 95), "max": max(values) if values else float("nan")}


def error_rates(refs: list[str], hyps: list[str]) -> dict[str, float]:
    import jiwer
    pairs = [(r, h) for r, h in zip(refs, hyps) if r]
    refs, hyps = [p[0] for p in pairs], [p[1] for p in pairs]
    return {"wer": jiwer.wer(refs, hyps), "cer": jiwer.cer(refs, hyps), "n": len(refs)}


def lexical(refs: list[str], hyps: list[str]) -> dict[str, float]:
    import sacrebleu
    return {
        "bleu": sacrebleu.corpus_bleu(hyps, [refs]).score,
        "chrf++": sacrebleu.corpus_chrf(hyps, [refs], word_order=2).score,
        "n": len(refs),
    }


def score_only_decision(row: dict, floor: float, margin: float, offset: float) -> str | None:
    """
    The policy without its prior: what the two ASR scores alone decide.

    Mirrors DualAsrTurnRouter.decide: a branch that ran but emitted no token
    logs a null score and counts as -inf; a branch that threw (null object)
    leaves the comparison incomplete and fails closed.
    """
    if row.get("vi") is None or row.get("en") is None:
        return None
    vi = row["vi"].get("normalizedLogProb")
    en = row["en"].get("normalizedLogProb")
    vi = -math.inf if vi is None else vi
    en = -math.inf if en is None else en + offset
    if vi < floor and en < floor:
        return None
    if abs(vi - en) >= margin:
        return "vi-en" if vi > en else "en-vi"
    return None


def comet_scores(data: list[dict[str, str]], gpus: int) -> list[float]:
    from comet import download_model, load_from_checkpoint
    model = load_from_checkpoint(download_model("Unbabel/wmt22-comet-da"))
    return list(model.predict(data, batch_size=16, gpus=gpus, progress_bar=False).scores)


def load_whisper():
    from faster_whisper import WhisperModel
    try:
        model = WhisperModel("large-v3-turbo", device="cuda", compute_type="int8_float16")
        # CTranslate2 resolves cuBLAS/cuDNN lazily: prove CUDA with one decode.
        list(model.transcribe(np.zeros(16000, dtype=np.float32), language="en")[0])
        return model, "cuda"
    except Exception:
        return WhisperModel("large-v3-turbo", device="cpu", compute_type="int8"), "cpu"


def transcribe(model, audio_16k: np.ndarray, lang: str) -> str:
    """Arrays, not paths: faster-whisper's own decoder breaks on newer PyAV."""
    segments, _ = model.transcribe(audio_16k.astype(np.float32), language=lang, beam_size=5,
                                   condition_on_previous_text=False, vad_filter=False)
    return " ".join(s.text.strip() for s in segments)


def read_wav_16k(path: Path) -> np.ndarray:
    with wave.open(str(path)) as w:
        assert w.getframerate() == 16000 and w.getsampwidth() == 2
        return np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(np.float32) / 32768.0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("run", type=Path)
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--skip-comet", action="store_true")
    parser.add_argument("--skip-tts", action="store_true")
    parser.add_argument("--comet-gpus", type=int, default=0)
    args = parser.parse_args()

    items = {i.id: i for i in load_items(args.dataset)}
    header: dict = {}
    rows: dict[str, dict] = {}
    for line in (args.run / "turns.jsonl").read_text(encoding="utf-8").splitlines():
        record = json.loads(line)
        if record.get("type") == "header":
            header = record
        elif record.get("tag"):
            rows[record["tag"]] = record
    meta = json.loads((args.run / "run_meta.json").read_text(encoding="utf-8"))
    floor = header.get("floorThreshold", -5.0)
    margin = header.get("marginThreshold", 0.15)
    offset = header.get("enScoreOffset", -0.08)

    per_item: list[dict] = []
    for item_id, row in rows.items():
        item = items[item_id]
        truth = DIRECTION_OF[item.source_lang]
        lat = row.get("latencyMs", {})
        per_item.append({
            "id": item_id, "truth": truth, "decision": row.get("decision"),
            "reason": row.get("reason"), "score_only": score_only_decision(row, floor, margin, offset),
            "vi_score": (row.get("vi") or {}).get("normalizedLogProb"),
            "en_score": (row.get("en") or {}).get("normalizedLogProb"),
            "path": row.get("executionPath"), "error": row.get("error"),
            "speech_ms": row.get("audioMs"), "tts_audio_ms": row.get("ttsAudioMs"),
            "frontend_ms": lat.get("frontend"), "dual_asr_ms": lat.get("dualAsrWall"),
            "mt_ms": lat.get("mt"), "tts_ms": lat.get("tts"),
            "first_audio_ms": lat.get("firstAudio"), "post_bound_ms": lat.get("postBoundTotal"),
            "src_ref": item.source_text, "tgt_ref": item.target_text,
            "transcript": row.get("transcript"), "translation": row.get("translation"),
        })
    per_item.sort(key=lambda r: r["id"])
    missing = sorted(set(items) - set(rows))

    metrics: dict = {"run": str(args.run), "items_expected": len(items),
                     "items_with_rows": len(rows), "missing": missing,
                     "policy": {"floor": floor, "margin": margin, "en_offset": offset,
                                "prior_weight": header.get("priorWeight")}}

    # ---------------------------------------------------------------- routing
    routing = {}
    for truth in ("vi-en", "en-vi"):
        subset = [r for r in per_item if r["truth"] == truth]
        routing[truth] = {
            "n": len(subset),
            "correct": sum(r["decision"] == truth for r in subset),
            "misrouted": sum(r["decision"] not in (None, truth) for r in subset),
            "rejected": sum(r["decision"] is None for r in subset),
            "score_only_correct": sum(r["score_only"] == truth for r in subset),
            "score_only_misrouted": sum(r["score_only"] not in (None, truth) for r in subset),
            "reasons": {k: sum(r["reason"] == k for r in subset)
                        for k in sorted({r["reason"] for r in subset})},
        }
    metrics["routing"] = routing

    # -------------------------------------------------------- ASR and S2TT
    quality = {}
    comet_inputs: list[tuple[str, dict[str, str]]] = []
    for truth in ("vi-en", "en-vi"):
        src, tgt = truth.split("-")
        subset = [r for r in per_item if r["truth"] == truth]
        routed = [r for r in subset if r["decision"] == truth]
        asr = error_rates([normalize(r["src_ref"], src) for r in routed],
                          [normalize(r["transcript"], src) for r in routed])
        translated = [r for r in routed if r["translation"]]
        quality[truth] = {
            "asr_on_correctly_routed": asr,
            # A misrouted turn's text is in the wrong language: scored as given.
            "translation_all_items": lexical([normalize(r["tgt_ref"], tgt) for r in subset],
                                             [normalize(r["translation"], tgt) for r in subset]),
            "translation_translated_items": lexical(
                [normalize(r["tgt_ref"], tgt) for r in translated],
                [normalize(r["translation"], tgt) for r in translated]),
        }
        for r in translated:
            comet_inputs.append((r["id"], {"src": r["src_ref"], "mt": r["translation"],
                                           "ref": r["tgt_ref"]}))
    if not args.skip_comet and comet_inputs:
        scores = comet_scores([d for _, d in comet_inputs], args.comet_gpus)
        by_id = {item_id: s for (item_id, _), s in zip(comet_inputs, scores)}
        for r in per_item:
            r["comet"] = by_id.get(r["id"])
        for truth in ("vi-en", "en-vi"):
            values = [r["comet"] for r in per_item if r["truth"] == truth and r.get("comet") is not None]
            quality[truth]["comet_wmt22_da_translated_items"] = {
                "mean": float(np.mean(values)) if values else float("nan"), "n": len(values)}
    metrics["quality"] = quality

    # ----------------------------------------------------- TTS intelligibility
    tts_tar = args.run / "raw" / "tts_f32.tar"
    if not args.skip_tts and tts_tar.is_file():
        model, device = load_whisper()
        intelligibility = {"recognizer": f"faster-whisper large-v3-turbo ({device})"}
        from scipy.signal import resample_poly
        with tarfile.open(tts_tar) as tar:
            members = {Path(m.name).stem: m for m in tar.getmembers() if m.name.endswith(".f32")}
            for r in per_item:
                member = members.get(r["id"])
                if member is None or not r["translation"]:
                    continue
                samples = np.frombuffer(tar.extractfile(member).read(), dtype="<f4")
                # Kokoro renders 24 kHz; Whisper expects 16 kHz (ratio 2/3).
                r["tts_asr"] = transcribe(model, resample_poly(samples, 2, 3),
                                          TARGET_LANG[r["decision"]])
                # Whisper on the human source audio: the recognizer's own floor.
                r["source_asr_whisper"] = transcribe(model, read_wav_16k(items[r["id"]].wav),
                                                     items[r["id"]].source_lang)
        for truth in ("vi-en", "en-vi"):
            src, tgt = truth.split("-")
            # Grouped by the direction actually rendered, so the TTS voice
            # being judged matches the language Whisper was told to expect.
            spoken = [r for r in per_item if r["decision"] == truth and r.get("tts_asr") is not None]
            heard = [r for r in per_item if r["truth"] == truth and r.get("source_asr_whisper") is not None]
            intelligibility[truth] = {
                "tts_vs_requested_text": error_rates([normalize(r["translation"], tgt) for r in spoken],
                                                     [normalize(r["tts_asr"], tgt) for r in spoken]),
                "whisper_floor_on_human_source": error_rates(
                    [normalize(r["src_ref"], src) for r in heard],
                    [normalize(r["source_asr_whisper"], src) for r in heard]),
                "target_language": tgt,
            }
        metrics["tts_intelligibility"] = intelligibility

    # ---------------------------------------------------------------- latency
    latency = {}
    for direction in ("vi-en", "en-vi"):
        ok = [r for r in per_item if r["decision"] == direction and r["first_audio_ms"] is not None]
        latency[direction] = {
            "first_audio_ms": summarize([r["first_audio_ms"] for r in ok]),
            "first_audio_plus_hangover_ms": summarize([r["first_audio_ms"] + 1000 for r in ok]),
            "asr_ms": summarize([r["frontend_ms"] + r["dual_asr_ms"] for r in ok]),
            "mt_ms": summarize([r["mt_ms"] for r in ok]),
            "tts_ms": summarize([r["tts_ms"] for r in ok]),
            "speech_s": summarize([r["speech_ms"] / 1000 for r in ok]),
            "tts_audio_s": summarize([r["tts_audio_ms"] / 1000 for r in ok if r["tts_audio_ms"]]),
            "asr_rtf": summarize([(r["frontend_ms"] + r["dual_asr_ms"]) / r["speech_ms"] for r in ok]),
            "tts_rtf": summarize([r["tts_ms"] / r["tts_audio_ms"] for r in ok if r["tts_audio_ms"]]),
        }
    metrics["latency"] = latency

    # ------------------------------------------------------ memory and size
    memory = meta.get("memory", [])
    metrics["memory"] = {
        "peak_vm_hwm_mb": max((m.get("vm_hwm_kb", 0) for m in memory), default=0) / 1024,
        "max_pss_mb": max((m.get("pss_kb", 0) for m in memory), default=0) / 1024,
        "max_rss_mb": max((m.get("vm_rss_kb", 0) for m in memory), default=0) / 1024,
        "min_mem_available_mb": min((m.get("mem_available_kb", math.inf) for m in memory)) / 1024,
        "mem_total_mb": memory[0].get("mem_total_kb", 0) / 1024 if memory else 0,
        "majflt_delta": (memory[-1].get("majflt", 0) - memory[0].get("majflt", 0)) if memory else 0,
        "samples": len(memory),
    }
    groups: dict[str, int] = {}
    for f in meta.get("model_files", []):
        key = "/".join(f["path"].split("/")[:3])
        groups[key] = groups.get(key, 0) + f["bytes"]
    metrics["model_size"] = {
        "total_mb": sum(groups.values()) / 1e6,
        "by_component_mb": {k: v / 1e6 for k, v in sorted(groups.items())},
        "apk_mb": meta.get("apk_bytes", 0) / 1e6,
    }

    out_json = args.run / "metrics.json"
    out_json.write_text(json.dumps(metrics, indent=2, ensure_ascii=False), encoding="utf-8")
    with open(args.run / "per_item.csv", "w", newline="", encoding="utf-8") as fh:
        fields = list(dict.fromkeys(k for r in per_item for k in r))
        writer = csv.DictWriter(fh, fieldnames=fields)
        writer.writeheader()
        writer.writerows(per_item)
    print(json.dumps(metrics, indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
