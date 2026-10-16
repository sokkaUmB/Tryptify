# HTDemucs for the Snapdragon NPU

Builds `htdemucs_6s_npu_fp16weights.onnx`: the 6-stem HTDemucs, with the same
weights, input (`mix`) and output (`stems`) as the published export, made so
that QNN's HTP backend can compile it.

The published export (7.8 s windows) failed to finalize on a OnePlus 15 with
`QNN_COMMON_ERROR_MEM_ALLOC` (1002), and QNN refused the parts of it that only
compute shapes. This build differs in four ways, each checked:

| | Published export | This build |
|---|---|---|
| Window | 343,980 samples (7.8 s) | 85,995 (1.95 s): a quarter of the activations, a sixteenth of the attention |
| STFT | convolutions, strided slices | frames by reshape, one MatMul each way, overlap-add by concat |
| Shapes | computed in the graph (Shape, Range, ScatterND, ConstantOfShape) | folded to constants: 1,267 nodes, none of those |
| GELU | Erf, which QNN refuses | `Gelu` (opset 20), which QNN runs |

On a synthetic song with known parts, the shorter window cost 0.4 to 0.9 dB
of SDR and kept the drum envelope (what charts are made from) at 0.997
correlation with the original's (`compare_windows.py`).

## Build

CPU only. About 2 GB for the environment, a minute or two to run.

```
python3 -m venv venv
venv/bin/pip install torch --index-url https://download.pytorch.org/whl/cpu
venv/bin/pip install onnx onnxruntime numpy soundfile einops julius pyyaml tqdm
venv/bin/pip install --no-deps demucs

venv/bin/python -I export_htdemucs_npu.py --model htdemucs_6s --out htdemucs_6s_npu.onnx
venv/bin/python -I fold_and_check.py htdemucs_6s_npu.onnx htdemucs_6s_npu.folded.onnx \
    htdemucs_6s_npu.check-input.npy htdemucs_6s_npu.check-output.npy
venv/bin/python -I fp16_weights.py htdemucs_6s_npu.folded.onnx htdemucs_6s_npu_fp16weights.onnx \
    htdemucs_6s_npu.check-input.npy htdemucs_6s_npu.check-output.npy
venv/bin/python -I compare_windows.py htdemucs_6s_npu_fp16weights.onnx
```

Each step stops if its output differs from PyTorch's by more than its limit.
The weights download from Meta's Demucs release on the first run.

`--segment-samples` sets a shorter window if the NPU still runs out of memory;
a new window is a new model, so it needs its own `KnownModels` entry (size,
hash, `segmentFrames`).

The result is imported in the app like any other model file; `KnownModels`
recognises it by its hash and size and drives it with its own window.
