#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Fold an exported model's shape arithmetic into constants and check it.

PyTorch's tracer records every size() it reads as a Shape node, so a model
whose input is a fixed size still carries hundreds of Shape, Range,
ScatterND and ConstantOfShape nodes computing numbers that never change.
QNN refuses several of those, and each refused node splits the NPU's graph
into another CPU round trip. ONNX Runtime's basic optimisations (constant
folding, with every shape known) compute them once; the result is saved and
its output checked against the export's own.

Usage: fold_and_check.py IN.onnx OUT.onnx CHECK_INPUT.npy CHECK_OUTPUT.npy
"""

import collections
import sys

import numpy as np
import onnx
import onnxruntime as ort
from onnx import numpy_helper


def fold(src: str, dst: str) -> None:
    options = ort.SessionOptions()
    options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_BASIC
    options.optimized_model_filepath = dst
    ort.InferenceSession(src, options, providers=["CPUExecutionProvider"])


def report(path: str) -> None:
    model = onnx.load(path)
    graph = model.graph
    ops = collections.Counter(node.op_type for node in graph.node)
    print(f"{len(graph.node)} nodes:", dict(sorted(ops.items(), key=lambda kv: -kv[1])))
    initializers = {i.name: i for i in graph.initializer}
    strided = []
    for node in graph.node:
        if node.op_type == "Slice" and len(node.input) >= 5 and node.input[4]:
            steps = initializers.get(node.input[4])
            values = None if steps is None else numpy_helper.to_array(steps)
            if values is None or np.any(values != 1):
                strided.append((node.name, None if values is None else values.tolist()))
    print("strided slices:", strided)


def main() -> None:
    src, dst, check_in, check_out = sys.argv[1:5]
    fold(src, dst)
    report(dst)
    session = ort.InferenceSession(dst, providers=["CPUExecutionProvider"])
    out = session.run(["stems"], {"mix": np.load(check_in)})[0]
    diff = float(np.abs(out - np.load(check_out)).max())
    print(f"folded vs pytorch: max diff {diff:.2e}, output {out.shape}")
    assert diff < 1e-3, diff


if __name__ == "__main__":
    main()
