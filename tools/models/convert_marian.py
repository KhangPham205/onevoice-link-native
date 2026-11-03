#!/usr/bin/env python
"""Convert a Helsinki-NLP opus-mt model (already downloaded to models-src/) to
ONNX for on-device inference with ONNX Runtime.

Produces models-hf/mt-<direction>/:
    encoder_model.onnx, decoder_model.onnx   (optimum export, no KV cache)
    source.spm, target.spm, vocab.json       (copied from the HF repo)
    mt_config.json                           (token ids the Kotlin decoder needs)

Usage (inside tools/models/.venv):
    python convert_marian.py --direction vi-en [--int8]
    python convert_marian.py --direction en-vi [--int8]
"""
import argparse
import json
import shutil
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]


def target_prefix_token_id(source: Path) -> int | None:
    """Return Marian's optional sentence-initial target-language token ID."""
    tokenizer_path = source / "tokenizer_config.json"
    vocab_path = source / "vocab.json"
    if not tokenizer_path.is_file() or not vocab_path.is_file():
        return None

    tokenizer = json.loads(tokenizer_path.read_text(encoding="utf-8"))
    target_lang = tokenizer.get("target_lang")
    if not isinstance(target_lang, str) or not target_lang:
        return None
    target_tag = (target_lang if target_lang.startswith(">>") and
                  target_lang.endswith("<<") else f">>{target_lang}<<")
    vocab = json.loads(vocab_path.read_text(encoding="utf-8"))
    token_id = vocab.get(target_tag)
    if isinstance(token_id, int) and not isinstance(token_id, bool):
        return token_id
    return None


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--direction", choices=["vi-en", "en-vi"], default="vi-en",
                        help="translation direction (selects source and output dirs)")
    parser.add_argument("--int8", action="store_true", help="dynamic-quantize the ONNX graphs")
    args = parser.parse_args()

    SRC = REPO / "models-src" / f"opus-mt-{args.direction}"
    OUT = REPO / "models-hf" / f"mt-{args.direction}"

    if not (SRC / "pytorch_model.bin").exists():
        sys.exit(f"Source model not found at {SRC} — run the HF download first.")
    OUT.mkdir(parents=True, exist_ok=True)

    from optimum.onnxruntime import ORTModelForSeq2SeqLM

    print("Exporting MarianMT to ONNX (encoder + decoder, no cache)...")
    model = ORTModelForSeq2SeqLM.from_pretrained(SRC, export=True, use_cache=False)
    model.save_pretrained(OUT)

    for name in ("source.spm", "target.spm", "vocab.json"):
        shutil.copy2(SRC / name, OUT / name)

    cfg = json.loads((SRC / "config.json").read_text(encoding="utf-8"))
    gen = {}
    gen_path = SRC / "generation_config.json"
    if gen_path.exists():
        gen = json.loads(gen_path.read_text(encoding="utf-8"))
    mt_config = {
        "decoder_start_token_id": gen.get("decoder_start_token_id",
                                          cfg.get("decoder_start_token_id")),
        "eos_token_id": gen.get("eos_token_id", cfg.get("eos_token_id")),
        "pad_token_id": gen.get("pad_token_id", cfg.get("pad_token_id")),
        "max_length": gen.get("max_length", cfg.get("max_length", 128)),
    }
    assert None not in mt_config.values(), f"missing ids: {mt_config}"
    prefix_id = target_prefix_token_id(SRC)
    if prefix_id is not None:
        # Tatoeba Marian models such as opus-mt-en-vi require the target tag
        # before SentencePiece source IDs. Persist the already-resolved ID so
        # Kotlin does not have to reproduce MarianTokenizer's special-token
        # parsing on device.
        mt_config["target_prefix_token_id"] = prefix_id
    (OUT / "mt_config.json").write_text(json.dumps(mt_config, indent=2), encoding="utf-8")

    if args.int8:
        from onnxruntime.quantization import QuantType, quantize_dynamic
        for graph in ("encoder_model.onnx", "decoder_model.onnx"):
            print(f"Quantizing {graph} to int8...")
            quantize_dynamic(OUT / graph, OUT / graph, weight_type=QuantType.QInt8)

    # The Kotlin side loads only the files listed in the module docstring —
    # drop optimum's extra decoder_with_past/config copies to save push time.
    for extra in OUT.glob("decoder_with_past_model.onnx"):
        extra.unlink()

    print(f"Done -> {OUT}")
    print(json.dumps(mt_config, indent=2))


if __name__ == "__main__":
    main()
