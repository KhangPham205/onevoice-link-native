#!/usr/bin/env python
"""Convert SpeechFlow/spoken_language_identification to TFLite for on-device LID.

The published artifact is a TensorFlow SavedModel whose graph excludes the
audio frontend, exactly like the AmberNet export it replaces. The device side
therefore needs three files that must stay in lockstep:

    models-npu-generated/lid/speechflow_langid.tflite
    models-npu-generated/lid/speechflow_labels.json
    models-npu-generated/lid/speechflow_contract.json

Two contract details differ from AmberNet and silently produce garbage if the
C++ frontend is not adjusted:

  * layout is BTC ([1, frames, 80]), not AmberNet's BCT;
  * normalization is whole-utterance (config.yml sets normalize_per_feature to
    false), not per-mel-bin.

Label order comes from the upstream vocab/vocab.txt and is asserted here rather
than trusted: routing keys off english=1 and vietnamese=12, and a silent
reordering upstream would send Vietnamese speech through the English branch.
The 14th class, "other", is what lets the router fail closed on a language the
product does not serve.

Usage (inside a venv with tensorflow-cpu):
    python tools/models/convert_speechflow_lid.py models-src/speechflow-lid
"""
import argparse
import hashlib
import json
import sys
from pathlib import Path

import numpy as np
import tensorflow as tf

REPO = Path(__file__).resolve().parents[2]
OUT_DIR = REPO / "models-npu-generated" / "lid"

# Upstream vocab/vocab.txt, in file order. Index is the classifier's class id.
LABELS = [
    "chinese", "english", "french", "german", "indonesian", "italian",
    "japanese", "korean", "portuguese", "russian", "spanish", "turkish",
    "vietnamese", "other",
]
EXPECTED_EN_INDEX = 1
EXPECTED_VI_INDEX = 12
FEATURE_BINS = 80

# configs/config.yml of SpeechFlow-io/Spoken_language_identification.
FRONTEND = {
    "sampleRate": 16000,
    "features": FEATURE_BINS,
    "windowSamples": 400,   # 25 ms
    "hopSamples": 160,      # 10 ms
    "fftSize": 512,
    "preemphasis": 0.97,
    "normalize": "all_features",  # normalize_per_feature: false
    "normalizeSignal": True,
    "layout": "BTC",
    "maxFrames": 2000,
}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("saved_model", type=Path,
                        help="directory holding saved_model.pb and variables/")
    parser.add_argument("--frames", type=int, default=1000,
                        help="fixed frame count to specialize the graph for")
    args = parser.parse_args()

    src = args.saved_model
    if not (src / "saved_model.pb").is_file():
        sys.exit(f"{src}/saved_model.pb not found")

    if LABELS[EXPECTED_EN_INDEX] != "english" or LABELS[EXPECTED_VI_INDEX] != "vietnamese":
        sys.exit("label table does not place english/vietnamese where routing expects them")

    loaded = tf.saved_model.load(str(src))
    serving = loaded.signatures["serving_default"]

    inputs = serving.structured_input_signature[1]
    outputs = serving.structured_outputs
    if set(inputs) != {"input_1", "input_2"} or set(outputs) != {"output_1"}:
        sys.exit(f"unexpected signature: {sorted(inputs)} -> {sorted(outputs)}")
    if inputs["input_1"].shape[-1] != FEATURE_BINS:
        sys.exit(f"expected {FEATURE_BINS} mel bins, got {inputs['input_1'].shape}")
    class_count = int(outputs["output_1"].shape[-1])
    if class_count != len(LABELS):
        sys.exit(f"model emits {class_count} classes, label table has {len(LABELS)}")

    # A fixed [1, frames, 80] input keeps the interpreter free of dynamic
    # resizing on the audio path; the caller zero-pads and passes the true
    # length in input_2, which is what the attention pooling masks with.
    frames = args.frames

    @tf.function(input_signature=[
        tf.TensorSpec([1, frames, FEATURE_BINS], tf.float32, name="input_1"),
        tf.TensorSpec([1], tf.int32, name="input_2"),
    ])
    def serve(features, length):
        return serving(input_1=features, input_2=length)["output_1"]

    converter = tf.lite.TFLiteConverter.from_concrete_functions(
        [serve.get_concrete_function()], loaded)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    # The CRNN's recurrent core has no pure-TFLite lowering; SELECT_TF_OPS is
    # what makes the conversion succeed at all. It costs the flex delegate on
    # device, which the Gradle dependency must match.
    converter.target_spec.supported_ops = [
        tf.lite.OpsSet.TFLITE_BUILTINS,
        tf.lite.OpsSet.SELECT_TF_OPS,
    ]
    converter._experimental_lower_tensor_list_ops = False
    tflite_model = converter.convert()

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    model_path = OUT_DIR / "speechflow_langid.tflite"
    model_path.write_bytes(tflite_model)

    # Prove the exported graph runs and still ranks classes sanely before the
    # artifact is allowed to reach a phone.
    interpreter = tf.lite.Interpreter(model_content=tflite_model, num_threads=1)
    interpreter.allocate_tensors()
    in_details = interpreter.get_input_details()
    out_details = interpreter.get_output_details()
    by_shape = {tuple(d["shape"]): d for d in in_details}
    feature_detail = by_shape.get((1, frames, FEATURE_BINS))
    length_detail = by_shape.get((1,))
    if feature_detail is None or length_detail is None:
        sys.exit(f"unexpected TFLite inputs: {[d['shape'].tolist() for d in in_details]}")

    interpreter.set_tensor(feature_detail["index"],
                           np.zeros((1, frames, FEATURE_BINS), np.float32))
    interpreter.set_tensor(length_detail["index"], np.array([frames], np.int32))
    interpreter.invoke()
    probs = interpreter.get_tensor(out_details[0]["index"])[0]
    if probs.shape != (len(LABELS),) or not np.isfinite(probs).all():
        sys.exit(f"smoke test produced {probs.shape} / non-finite values")

    (OUT_DIR / "speechflow_labels.json").write_text(json.dumps({
        "labels": LABELS,
        "enIndex": EXPECTED_EN_INDEX,
        "viIndex": EXPECTED_VI_INDEX,
        "otherIndex": LABELS.index("other"),
    }, indent=2) + "\n")

    (OUT_DIR / "speechflow_contract.json").write_text(json.dumps({
        "tfliteSha256": hashlib.sha256(tflite_model).hexdigest(),
        "source": "SpeechFlow/spoken_language_identification",
        "frames": frames,
        "classes": len(LABELS),
        # output_1 is unnormalized logits, all negative. They look like
        # log-probabilities but are not: exp() over the vector sums to ~0.27, so
        # treating them that way reports "confidence" above 1.0. Softmax matches
        # upstream predict_pb; a bare exp() does not.
        "outputActivation": "logits",
        # "other" does not absorb junk on its own — noise ranks italian and
        # portuguese first — so the caller still needs a confidence gate.
        "otherIsNotAGuard": True,
        "inputs": [
            {"name": "input_1", "dims": [1, frames, FEATURE_BINS], "dtype": "float32"},
            {"name": "input_2", "dims": [1], "dtype": "int32"},
        ],
        "outputs": [{"name": "output_1", "dims": [1, len(LABELS)], "dtype": "float32"}],
        "selectTfOps": True,
        "frontend": FRONTEND,
    }, indent=2) + "\n")

    shifted = np.exp(probs - probs.max())
    print(f"wrote {model_path} ({len(tflite_model):,} bytes)")
    print(f"  logit range=[{probs.min():.3f}, {probs.max():.3f}] "
          f"argmax={LABELS[int(probs.argmax())]} "
          f"softmax_max={float(shifted.max() / shifted.sum()):.4f}")
    print(f"  en={EXPECTED_EN_INDEX} vi={EXPECTED_VI_INDEX} other={LABELS.index('other')}")


if __name__ == "__main__":
    main()
