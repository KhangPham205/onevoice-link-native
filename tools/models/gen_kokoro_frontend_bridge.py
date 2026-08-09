#!/usr/bin/env python3
"""Generate a tiny ONNX bridge that exposes sherpa/eSpeak Kokoro token IDs.

The bridge has Kokoro's public ONNX contract (tokens, style, speed -> audio),
but its "audio" output is just the input token IDs cast to float. Loading it
through sherpa-onnx therefore runs the exact production eSpeak frontend without
loading the 330 MB CPU Kokoro network. Android converts the returned floats back
to IDs and sends them to the separately compiled QNN Kokoro stages.

Requires the development-only packages ``onnx`` and ``numpy``. Neural inference
does not run in this bridge and these Python packages never ship on the phone.
"""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper


SPEAKERS = [
    "af",
    "af_bella",
    "af_nicole",
    "af_sarah",
    "af_sky",
    "am_adam",
    "am_michael",
    "bf_emma",
    "bf_isabella",
    "bm_george",
    "bm_lewis",
]


def build_model() -> onnx.ModelProto:
    tokens = helper.make_tensor_value_info(
        "tokens", TensorProto.INT64, [1, "num_tokens"]
    )
    style = helper.make_tensor_value_info("style", TensorProto.FLOAT, [1, 256])
    speed = helper.make_tensor_value_info("speed", TensorProto.FLOAT, [1])
    audio = helper.make_tensor_value_info(
        "audio", TensorProto.FLOAT, [1, "num_tokens"]
    )

    # Keep style and speed in the executable graph because sherpa supplies all
    # three inputs. Their value is multiplied by zero before being broadcast
    # over the cast token tensor.
    zero = numpy_helper.from_array(np.asarray(0.0, dtype=np.float32), "zero")
    nodes = [
        helper.make_node("Cast", ["tokens"], ["token_values"], to=TensorProto.FLOAT),
        helper.make_node("ReduceSum", ["style"], ["style_sum"], keepdims=0),
        helper.make_node("ReduceSum", ["speed"], ["speed_sum"], keepdims=0),
        helper.make_node("Add", ["style_sum", "speed_sum"], ["unused_sum"]),
        helper.make_node("Mul", ["unused_sum", "zero"], ["unused_zero"]),
        helper.make_node("Add", ["token_values", "unused_zero"], ["audio"]),
    ]
    graph = helper.make_graph(
        nodes,
        "onevoice_kokoro_espeak_token_bridge",
        [tokens, style, speed],
        [audio],
        [zero],
    )
    model = helper.make_model(
        graph,
        producer_name="onevoice-link",
        opset_imports=[helper.make_opsetid("", 17)],
    )
    # ONNX Runtime bundled by sherpa 1.13.4 accepts IR v9.
    model.ir_version = 9

    speaker_to_id = ",".join(f"{name}->{index}" for index, name in enumerate(SPEAKERS))
    id_to_speaker = ",".join(f"{index}->{name}" for index, name in enumerate(SPEAKERS))
    metadata = {
        "model_type": "kokoro",
        "language": "English",
        "has_espeak": "1",
        "sample_rate": "24000",
        "style_dim": "511,1,256",
        "n_speakers": str(len(SPEAKERS)),
        "speaker2id": speaker_to_id,
        "id2speaker": id_to_speaker,
        "speaker_names": ",".join(SPEAKERS),
        "model_url": "onevoice://kokoro-espeak-token-bridge",
    }
    helper.set_model_props(model, metadata)
    onnx.checker.check_model(model)
    return model


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("models-npu-generated/tts/kokoro_frontend_bridge.onnx"),
    )
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    onnx.save_model(build_model(), args.output)
    print(f"wrote {args.output} ({args.output.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
