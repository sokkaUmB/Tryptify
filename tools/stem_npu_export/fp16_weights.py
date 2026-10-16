#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Store a model's large float32 weights as float16, cast back on load.

Half the download for the same graph: every float32 initializer of at least
MIN_ELEMENTS values is stored as float16 and read through a Cast to float32,
which ONNX Runtime folds back to float32 when the session opens. Small ones
(scalars, norms' epsilons) stay float32, where a rounding would matter and
the saving would not.

Usage: fp16_weights.py IN.onnx OUT.onnx CHECK_INPUT.npy CHECK_OUTPUT.npy
"""

import sys

import numpy as np
import onnx
import onnxruntime as ort
from onnx import helper, numpy_helper

MIN_ELEMENTS = 1024


def convert(src: str, dst: str) -> None:
    model = onnx.load(src)
    graph = model.graph
    casts = []
    kept = []
    for init in graph.initializer:
        array = numpy_helper.to_array(init)
        if array.dtype == np.float32 and array.size >= MIN_ELEMENTS:
            half_name = init.name + "_fp16"
            kept.append(numpy_helper.from_array(array.astype(np.float16), half_name))
            casts.append(helper.make_node("Cast", [half_name], [init.name], to=onnx.TensorProto.FLOAT,
                                          name=init.name + "_cast"))
        else:
            kept.append(init)
    del graph.initializer[:]
    graph.initializer.extend(kept)
    nodes = list(graph.node)
    del graph.node[:]
    graph.node.extend(casts + nodes)
    onnx.save(model, dst)
    print(f"{len(casts)} weights stored as float16")


def main() -> None:
    src, dst, check_in, check_out = sys.argv[1:5]
    convert(src, dst)
    session = ort.InferenceSession(dst, providers=["CPUExecutionProvider"])
    out = session.run(["stems"], {"mix": np.load(check_in)})[0]
    diff = float(np.abs(out - np.load(check_out)).max())
    print(f"fp16 weights vs pytorch: max diff {diff:.2e}")
    assert diff < 1e-2, diff


if __name__ == "__main__":
    main()
