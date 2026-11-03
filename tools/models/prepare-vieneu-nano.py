#!/usr/bin/env python3
"""Stage VieNeu-TTS v3 Nano for the app's en-vi/tts_vieneu/ directory.

VieNeu v3 Nano (Apache-2.0, pnnbao-ump/VieNeu-TTS-v3-Nano) is a 48M-parameter
flow-matching Vietnamese TTS shipped as four ONNX graphs. The reference SDK
keeps its tables in numpy/JSON containers the app should not parse, so this
script flattens them into raw little-endian float32 files plus one JSON:

    text_encoder.onnx  duration_predictor.onnx  vector_estimator.onnx
    codec_decoder.onnx                       (copied unchanged, pinned revision)
    nano.json        vocab, bos/eos/pad, flow_fps, voice names
    null_spk.bin     [192]       unconditional speaker for CFG
    null_style.bin   [50, 256]   unconditional style for CFG
    voices.bin       [V, 192 + 50*256]  preset x-vector + style tokens, nano.json order

The presets live in the `vieneu` SDK wheel (vieneu/assets/voices_v3_nano.json);
only that JSON is read from the wheel, nothing in it is executed. sea_g2p.bin,
the G2P dictionary, comes from build-sea-g2p-android.sh.

Usage: python3 tools/models/prepare-vieneu-nano.py [--out models-vieneu/tts_vieneu]
"""
from __future__ import annotations

import argparse
import io
import json
import shutil
import unicodedata
import urllib.request
import zipfile
from pathlib import Path

import numpy as np

REPO = "pnnbao-ump/VieNeu-TTS-v3-Nano"
REVISION = "aba295eb96a6fa6003ebe417cc1f2802a7adc1dc"  # 2026-09-05
SDK_VERSION = "3.8.3"
GRAPHS = ["text_encoder.onnx", "duration_predictor.onnx", "vector_estimator.onnx",
          "codec_decoder.onnx"]
ROOT = Path(__file__).resolve().parents[2]


def fetch(url: str) -> bytes:
    with urllib.request.urlopen(url) as response:
        return response.read()


def slug(name: str) -> str:
    """'Minh Quân' -> 'minh_quan': ASCII so it can travel through gradle.properties."""
    folded = unicodedata.normalize("NFKD", name.replace("Đ", "D").replace("đ", "d"))
    return "_".join("".join(c for c in folded if c.isascii()).lower().split())


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out", type=Path, default=ROOT / "models-vieneu" / "tts_vieneu")
    parser.add_argument("--src", type=Path, default=ROOT / "models-vieneu" / "src",
                        help="cache for the downloaded files")
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    args.src.mkdir(parents=True, exist_ok=True)

    for name in GRAPHS + ["config.json", "constants.npz"]:
        cached = args.src / name
        if not cached.is_file():
            print(f"download {name}")
            cached.write_bytes(fetch(f"https://huggingface.co/{REPO}/resolve/{REVISION}/{name}"))
    for name in GRAPHS:
        shutil.copyfile(args.src / name, args.out / name)

    config = json.loads((args.src / "config.json").read_text(encoding="utf-8"))
    constants = np.load(args.src / "constants.npz", allow_pickle=False)
    null_spk = constants["null_spk"].astype("<f4")
    null_style = constants["null_style"].astype("<f4")
    assert null_spk.shape == (192,) and null_style.shape == (50, 256)
    null_spk.tofile(args.out / "null_spk.bin")
    null_style.tofile(args.out / "null_style.bin")

    wheel_cache = args.src / f"vieneu-{SDK_VERSION}.whl"
    if not wheel_cache.is_file():
        meta = json.loads(fetch(f"https://pypi.org/pypi/vieneu/{SDK_VERSION}/json"))
        url = next(u["url"] for u in meta["urls"] if u["filename"].endswith(".whl"))
        wheel_cache.write_bytes(fetch(url))
    with zipfile.ZipFile(io.BytesIO(wheel_cache.read_bytes())) as wheel:
        presets = json.loads(wheel.read("vieneu/assets/voices_v3_nano.json").decode("utf-8"))

    names, rows = [], []
    for name, voice in presets["presets"].items():
        spk = np.asarray(voice["speaker_emb"], dtype="<f4").reshape(-1)
        style = np.asarray(voice["style"], dtype="<f4")
        assert spk.shape == (192,) and style.shape == (50, 256), name
        names.append({"name": name, "id": slug(name), "gender": voice.get("gender", "")})
        rows.append(np.concatenate([spk, style.reshape(-1)]))
    np.stack(rows).astype("<f4").tofile(args.out / "voices.bin")

    nano = {
        "source": f"{REPO}@{REVISION}",
        "sample_rate": config["sample_rate"],
        "flow_fps": config["flow_fps"],
        "latent_channels": config["latent_dim"] * config["group"],  # 24 x 6 = 144
        "n_style": config["n_style"],
        "style_dim": config["style_dim"],
        "speaker_dim": 192,
        "bos_id": config["bos_id"],
        "eos_id": config["eos_id"],
        "pad_id": config["pad_id"],
        "steps_default": config["steps_default"],
        "cfg_default": config["cfg_default"],
        "vocab": config["vocab"],
        "voices": names,
        "default_voice": slug(presets.get("default_voice") or names[0]["name"]),
    }
    (args.out / "nano.json").write_text(json.dumps(nano, ensure_ascii=False, indent=1),
                                        encoding="utf-8")
    dictionary = ROOT / "models-vieneu" / "sea_g2p.bin"
    if dictionary.is_file():
        shutil.copyfile(dictionary, args.out / "sea_g2p.bin")
    else:
        print("note: sea_g2p.bin missing; run tools/models/build-sea-g2p-android.sh")
    print(f"voices: {', '.join(v['id'] for v in names)} (default {nano['default_voice']})")
    for f in sorted(args.out.iterdir()):
        print(f"{f.stat().st_size / 1e6:9.1f} MB  {f.name}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
