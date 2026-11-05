#!/usr/bin/env python
"""Package contextboxai/Kokoro-Vietnamese for sherpa-onnx OfflineTts (kokoro).

Produces models-hf/tts/:
    model.onnx    (copy of kokoro_vi.onnx)
    voices.bin    (raw float32 style vectors, speaker-major — sherpa layout)
    tokens.txt    (phoneme -> id map from config.json vocab)
    (espeak-ng-data/ comes from fetch-sherpa-assets.ps1)

Usage (inside tools/models/.venv):
    python convert_kokoro.py
"""
import json
import shutil
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SRC = REPO / "models-src" / "kokoro-vietnamese"
OUT = REPO / "models-hf" / "tts"


def main() -> None:
    onnx_path = SRC / "kokoro_vi.onnx"
    if not onnx_path.exists():
        sys.exit(f"{onnx_path} not found — run the HF download first.")
    OUT.mkdir(parents=True, exist_ok=True)

    shutil.copy2(onnx_path, OUT / "model.onnx")

    # ---- tokens.txt from config.json vocab ---------------------------------
    cfg = json.loads((SRC / "config.json").read_text(encoding="utf-8"))
    vocab = cfg.get("vocab") or cfg.get("phoneme_id_map")
    if not isinstance(vocab, dict):
        sys.exit(f"config.json has no vocab/phoneme map; keys = {list(cfg)}")
    with (OUT / "tokens.txt").open("w", encoding="utf-8", newline="\n") as f:
        for token, idx in sorted(vocab.items(), key=lambda kv: kv[1]):
            f.write(f"{token} {idx}\n")
    print(f"tokens.txt: {len(vocab)} phonemes")

    # ---- voices.bin from the torch voicepack -------------------------------
    import numpy as np
    import torch

    pack = torch.load(SRC / "kokoro_vi_voicepack.pt", map_location="cpu", weights_only=True)
    if isinstance(pack, dict):
        names = sorted(pack.keys())
        arrays = [pack[n].numpy().astype(np.float32) for n in names]
    else:  # single-voice tensor
        names = ["default"]
        arrays = [pack.numpy().astype(np.float32)]

    with (OUT / "voices.bin").open("wb") as f:
        for arr in arrays:
            f.write(arr.tobytes())

    # ---- sherpa metadata on model.onnx --------------------------------------
    # sherpa-onnx's kokoro loader hard-requires these metadata_props (same
    # failure mode as the NeMo CTC model: missing keys abort the process).
    import onnx

    m = onnx.load(str(OUT / "model.onnx"))
    speaker2id = ",".join(f"{n}->{i}" for i, n in enumerate(names))
    id2speaker = ",".join(f"{i}->{n}" for i, n in enumerate(names))
    meta = {
        "model_type": "kokoro",
        "language": "Vietnamese",
        "has_espeak": 1,
        "sample_rate": 24000,
        "version": 1,
        "voice": "vi",  # espeak-ng voice used for phonemization (vi_dict)
        "style_dim": ",".join(map(str, arrays[0].shape)),
        "n_speakers": len(names),
        "speaker2id": speaker2id,
        "id2speaker": id2speaker,
        "speaker_names": ",".join(names),
        "model_url": "https://huggingface.co/contextboxai/Kokoro-Vietnamese",
    }
    del m.metadata_props[:]
    for k, v in meta.items():
        p = m.metadata_props.add()
        p.key = k
        p.value = str(v)
    onnx.save(m, str(OUT / "model.onnx"))
    print(f"metadata -> model.onnx: {meta}")

    print(f"voices.bin: {len(names)} speaker(s), order = {names}")
    print("Use sid = index in that order from the app (default sid=0 ->", names[0], ")")
    print(f"Done -> {OUT}")


if __name__ == "__main__":
    main()
