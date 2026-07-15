#!/usr/bin/env python
"""Export nvidia/parakeet-ctc-0.6b-Vietnamese (.nemo) to ONNX for sherpa-onnx
(model type `nemo_ctc`).

Produces models-hf/asr/:
    model.onnx   (encoder+decoder CTC graph; add --int8 for a quantized copy)
    tokens.txt   (BPE vocab + <blk> as the final line — sherpa convention)

Requires nemo_toolkit[asr] (heavy — installed by setup-venv.ps1 -WithNemo).

Usage (inside tools/models/.venv):
    python convert_parakeet.py [--int8]
"""
import argparse
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SRC = REPO / "models-src" / "parakeet-ctc-0.6b-vi" / "parakeet-ctc-0.6b-vi.nemo"
OUT = REPO / "models-hf" / "asr"


def add_sherpa_metadata(onnx_file: Path, tokens_file: Path,
                        normalize_type: str, subsampling_factor: int) -> None:
    """sherpa-onnx's nemo_ctc loader hard-requires these metadata_props; a bare
    NeMo model.export() writes none of them and the app aborts with
    "'vocab_size' does not exist in the metadata"."""
    import onnx

    m = onnx.load(str(onnx_file))
    vocab_size = sum(1 for _ in tokens_file.open(encoding="utf-8"))
    meta = {
        "vocab_size": vocab_size,            # = line count of tokens.txt (incl. <blk>)
        "normalize_type": normalize_type,     # from cfg.preprocessor.normalize
        "subsampling_factor": subsampling_factor,  # FastConformer (parakeet) = 8
        "model_type": "EncDecCTCModelBPE",
        "version": "1",
        "model_author": "nemo",
    }
    del m.metadata_props[:]  # idempotent re-runs
    for k, v in meta.items():
        p = m.metadata_props.add()
        p.key = k
        p.value = str(v)
    onnx.save(m, str(onnx_file))
    print(f"metadata -> {onnx_file.name}: {meta}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--int8", action="store_true")
    parser.add_argument("--meta-only", action="store_true",
                        help="only (re)write sherpa metadata on existing ONNX files")
    args = parser.parse_args()

    if not SRC.exists():
        sys.exit(f"{SRC} not found — run the HF download first.")
    OUT.mkdir(parents=True, exist_ok=True)

    import nemo.collections.asr as nemo_asr

    # Config-only restore is cheap — used by both paths for the meta values.
    cfg = nemo_asr.models.EncDecCTCModelBPE.restore_from(str(SRC), return_config=True)
    normalize_type = str(cfg.preprocessor.get("normalize", "per_feature"))
    subsampling_factor = int(cfg.encoder.get("subsampling_factor", 8))

    if args.meta_only:
        patched = False
        for name in ("model.int8.onnx", "model.onnx"):
            f = OUT / name
            if f.exists():
                add_sherpa_metadata(f, OUT / "tokens.txt", normalize_type, subsampling_factor)
                patched = True
        if not patched:
            sys.exit("no ONNX files found to patch — run the full export first")
        return

    print("Restoring .nemo (this loads 2.4 GB — be patient)...")
    model = nemo_asr.models.EncDecCTCModelBPE.restore_from(str(SRC), map_location="cpu")
    model.eval()

    print("Exporting to ONNX...")
    model.export(str(OUT / "model.onnx"))

    # tokens.txt: one piece per line "<piece> <id>", blank token last (sherpa).
    vocab = model.tokenizer.vocab
    with (OUT / "tokens.txt").open("w", encoding="utf-8", newline="\n") as f:
        for i, piece in enumerate(vocab):
            f.write(f"{piece} {i}\n")
        f.write(f"<blk> {len(vocab)}\n")
    print(f"tokens.txt: {len(vocab)} pieces + <blk>")

    if args.int8:
        from onnxruntime.quantization import QuantType, quantize_dynamic
        print("Quantizing to int8 (model.int8.onnx)...")
        quantize_dynamic(OUT / "model.onnx", OUT / "model.int8.onnx",
                         weight_type=QuantType.QInt8)
        # NeMo writes the >2GB fp32 graph as model.onnx + hundreds of external
        # weight-shard files; the int8 file is self-contained, so drop the fp32
        # artifacts to keep the on-device push small (~650 MB instead of ~3 GB).
        for f in OUT.iterdir():
            if f.is_file() and f.name not in ("model.int8.onnx", "tokens.txt"):
                f.unlink()

    for name in ("model.int8.onnx", "model.onnx"):
        f = OUT / name
        if f.exists():
            add_sherpa_metadata(f, OUT / "tokens.txt", normalize_type, subsampling_factor)

    print(f"Done -> {OUT}")


if __name__ == "__main__":
    main()
