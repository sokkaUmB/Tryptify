#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Export HTDemucs as an ONNX model the Snapdragon NPU can compile.

The published HTDemucs ONNX exports work on a fixed 7.8 s window (343,980
samples). On a OnePlus 15 (SM8850, Hexagon V81) QNN compiled that graph for
over a minute, mapped about 530 MB for it, and then failed to finalize with
QNN_COMMON_ERROR_MEM_ALLOC (1002): single operations on a window that long are
too large for the NPU to tile. This export keeps the same weights, inputs and
outputs and changes two things:

* The window is a quarter as long, 1.95 s (85,995 samples). Activations are a
  quarter the size and the transformer's attention maps a sixteenth.
* The STFT and inverse STFT are plain real arithmetic built from operations the
  NPU validates: hop-sized blocks joined into frames by reshapes and
  contiguous slices, one MatMul against a DFT table each way, overlap-add by
  concatenation, and a precomputed window correction (the shapes are fixed).
  No complex tensors, no 4096-wide convolution, no strided slice.

The model has the published export's interface, so Tryptify drives it as it
drives that one: input `mix` [1, 2, 85995] float32, output `stems`
[1, S, 2, 85995] float32 in Demucs' source order. Normalisation happens inside
the model, as in Demucs.

Usage (CPU only, needs torch, demucs, onnx, onnxruntime): see README.md, which
runs this, fold_and_check.py and fp16_weights.py in order.

It checks the real STFT against torch.stft, the patched model against the
original, and the ONNX file against PyTorch, and stops if any of them differ.
"""

from __future__ import annotations

import argparse
import math
import pathlib
import types
from fractions import Fraction

import numpy as np
import torch
import torch.nn.functional as F

SAMPLE_RATE = 44_100
DEFAULT_SEGMENT = 343_980 // 4  # 85,995: a quarter of the published window


# ── real STFT ────────────────────────────────────────────────────────────────

def dft_tables(n_fft: int, bins: int):
    """Forward and inverse tables for torch.stft/istft(normalized=True, hann)."""
    n = torch.arange(n_fft, dtype=torch.float64)
    k = torch.arange(bins, dtype=torch.float64)
    angle = 2 * math.pi * torch.outer(n, k) / n_fft  # [n_fft, bins]
    window = torch.hann_window(n_fft, periodic=True, dtype=torch.float64)
    scale = n_fft ** -0.5
    # Forward: frame [.., n_fft] @ [n_fft, 2*bins] -> re | im.
    forward = torch.cat([window[:, None] * torch.cos(angle), -window[:, None] * torch.sin(angle)], dim=1) * scale
    # Inverse (irfft of a half spectrum whose Nyquist bin is zero, times sqrt(n_fft)
    # for normalized=True, times the synthesis window): [2*bins, n_fft].
    weight = torch.full((bins,), 2.0, dtype=torch.float64)
    weight[0] = 1.0
    inv_re = weight[:, None] * torch.cos(angle.t())
    inv_im = -weight[:, None] * torch.sin(angle.t())
    inv_im[0] = 0.0  # irfft ignores the DC bin's imaginary part
    inverse = torch.cat([inv_re, inv_im], dim=0) * (n_fft ** 0.5 / n_fft) * window[None, :]
    return forward.float(), inverse.float(), window.float()


class RealStft:
    """torch.stft / istft for HTDemucs' fixed n_fft = 4 * hop, in real tensors."""

    def __init__(self, n_fft: int, hop: int):
        assert n_fft == 4 * hop
        self.n_fft = n_fft
        self.hop = hop
        self.bins = n_fft // 2  # HTDemucs drops the Nyquist bin
        self.forward_table, self.inverse_table, self.window = dft_tables(n_fft, self.bins)

    def stft(self, x: torch.Tensor) -> torch.Tensor:
        """x [..., L] -> [..., bins, frames, 2], as torch.stft(center=True, reflect)[..., :-1, :]."""
        *other, length = x.shape
        x = x.reshape(-1, length)
        x = F.pad(x[:, None], (self.n_fft // 2, self.n_fft // 2), mode="reflect")[:, 0]
        frames = 1 + length // self.hop
        need = (frames + 3) * self.hop
        if x.shape[-1] < need:
            x = F.pad(x, (0, need - x.shape[-1]))
        x = x[:, :need]
        blocks = x.reshape(x.shape[0], frames + 3, self.hop)
        # Frame t is blocks t..t+3, joined: contiguous slices and a concat.
        framed = torch.cat([blocks[:, q:q + frames] for q in range(4)], dim=-1)  # [N, frames, n_fft]
        spec = framed @ self.forward_table  # [N, frames, 2*bins]
        re, im = spec[..., :self.bins], spec[..., self.bins:]
        z = torch.stack([re, im], dim=-1).transpose(1, 2)  # [N, bins, frames, 2]
        return z.reshape(*other, self.bins, frames, 2)

    def istft(self, z: torch.Tensor, length: int) -> torch.Tensor:
        """z [..., bins, frames, 2] (Nyquist bin dropped) -> [..., length], as torch.istft(center=True)."""
        *other, bins, frames, _ = z.shape
        assert bins == self.bins
        z = z.reshape(-1, bins, frames, 2).transpose(1, 2)  # [N, frames, bins, 2]
        spec = torch.cat([z[..., 0], z[..., 1]], dim=-1)  # [N, frames, 2*bins]
        framed = spec @ self.inverse_table  # [N, frames, n_fft], windowed
        quarters = framed.reshape(framed.shape[0], frames, 4, self.hop)
        n = framed.shape[0]
        out = None
        for q in range(4):
            part = quarters[:, :, q]
            pieces = []
            if q:
                pieces.append(torch.zeros(n, q, self.hop, dtype=part.dtype))
            pieces.append(part)
            if 3 - q:
                pieces.append(torch.zeros(n, 3 - q, self.hop, dtype=part.dtype))
            shifted = torch.cat(pieces, dim=1)
            out = shifted if out is None else out + shifted
        out = out.reshape(n, (frames + 3) * self.hop)
        # The window's overlap, a constant for a fixed length.
        envelope = torch.zeros((frames + 3) * self.hop, dtype=torch.float64)
        square = self.window.double() ** 2
        for t in range(frames):
            envelope[t * self.hop:t * self.hop + self.n_fft] += square
        start = self.n_fft // 2
        envelope = envelope[start:start + length]
        correction = torch.where(envelope > 1e-11, 1.0 / envelope, torch.zeros_like(envelope)).float()
        out = out[:, start:start + length] * correction
        return out.reshape(*other, length)


# ── HTDemucs, patched ────────────────────────────────────────────────────────

def patch(model, real: RealStft) -> None:
    """Swap HTDemucs' complex spectrogram methods for the real ones."""
    from demucs.hdemucs import pad1d

    def _spec(self, x):
        hl, nfft = self.hop_length, self.nfft
        le = int(math.ceil(x.shape[-1] / hl))
        pad = hl // 2 * 3
        x = pad1d(x, (pad, pad + le * hl - x.shape[-1]), mode="reflect")
        z = real.stft(x)
        return z[..., 2:2 + le, :]

    def _magnitude(self, z):
        B, C, Fr, T, _ = z.shape
        return z.permute(0, 1, 4, 2, 3).reshape(B, C * 2, Fr, T)

    def _mask(self, z, m):
        B, S, C, Fr, T = m.shape
        return m.view(B, S, -1, 2, Fr, T).permute(0, 1, 2, 4, 5, 3)

    def _ispec(self, z, length=None, scale=0):
        hl = self.hop_length // (4 ** scale)
        z = F.pad(z, (0, 0, 2, 2))  # two frames either side, as F.pad(z, (2, 2)) on the complex
        pad = hl // 2 * 3
        le = hl * int(math.ceil(length / hl)) + 2 * pad
        x = real.istft(z, le)
        return x[..., pad:pad + length]

    model._spec = types.MethodType(_spec, model)
    model._magnitude = types.MethodType(_magnitude, model)
    model._mask = types.MethodType(_mask, model)
    model._ispec = types.MethodType(_ispec, model)


class Exported(torch.nn.Module):
    def __init__(self, model):
        super().__init__()
        self.model = model

    def forward(self, mix):
        return self.model(mix)


def max_diff(a, b) -> float:
    return float((torch.as_tensor(a) - torch.as_tensor(b)).abs().max())


def check_stft(real: RealStft, length: int) -> None:
    torch.manual_seed(0)
    x = torch.randn(3, length)
    ref = torch.stft(x, real.n_fft, real.hop, window=torch.hann_window(real.n_fft), win_length=real.n_fft,
                     normalized=True, center=True, return_complex=True, pad_mode="reflect")[:, :-1]
    ours = real.stft(x)
    d = max_diff(torch.view_as_real(ref), ours)
    print(f"stft vs torch.stft: max diff {d:.2e}")
    assert d < 1e-3, d
    full = torch.cat([ref, torch.zeros_like(ref[:, :1])], dim=1)
    ref_x = torch.istft(full, real.n_fft, real.hop, window=torch.hann_window(real.n_fft), win_length=real.n_fft,
                        normalized=True, length=length, center=True)
    ours_x = real.istft(ours, length)
    d = max_diff(ref_x, ours_x)
    print(f"istft vs torch.istft: max diff {d:.2e}")
    assert d < 1e-3, d


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", default="htdemucs_6s")
    parser.add_argument("--out", type=pathlib.Path, required=True)
    # 20 for Gelu as one operation: QNN runs Gelu, and refuses the Erf that
    # opset 17 spells it with.
    parser.add_argument("--opset", type=int, default=20)
    parser.add_argument("--segment-samples", type=int, default=DEFAULT_SEGMENT,
                        help="the window, in samples at 44.1 kHz; shorter is lighter for the NPU")
    args = parser.parse_args()
    segment = args.segment_samples

    from demucs.pretrained import get_model

    bag = get_model(args.model)
    model = bag.models[0] if hasattr(bag, "models") else bag
    model.eval()
    model.segment = Fraction(segment, SAMPLE_RATE)
    assert int(model.segment * model.samplerate) == segment
    print(f"{args.model}: sources {model.sources}, nfft {model.nfft}, hop {model.hop_length}, "
          f"window {segment} samples")

    real = RealStft(model.nfft, model.hop_length)
    check_stft(real, segment + 2 * 1536 + 21)

    torch.manual_seed(1)
    mix = torch.randn(1, 2, segment) * 0.3
    with torch.no_grad():
        reference = model(mix)
    patch(model, real)
    with torch.no_grad():
        patched = model(mix)
    d = max_diff(reference, patched)
    print(f"patched model vs original: max diff {d:.2e} (output {tuple(patched.shape)})")
    assert d < 1e-3, d

    wrapper = Exported(model).eval()
    # nn.MultiheadAttention's fused eval path has no ONNX form; the plain one
    # traces to MatMul and Softmax, which the NPU runs.
    torch.backends.mha.set_fastpath_enabled(False)
    with torch.no_grad():
        torch.onnx.export(
            wrapper,
            (mix,),
            str(args.out),
            input_names=["mix"],
            output_names=["stems"],
            opset_version=args.opset,
            do_constant_folding=True,
            dynamo=False,
        )
    print(f"wrote {args.out} ({args.out.stat().st_size / 1e6:.1f} MB)")

    import onnxruntime as ort

    session = ort.InferenceSession(str(args.out), providers=["CPUExecutionProvider"])
    out = session.run(["stems"], {"mix": mix.numpy()})[0]
    d = max_diff(out, patched)
    print(f"onnx vs pytorch: max diff {d:.2e}")
    assert d < 1e-3, d
    np.save(args.out.with_suffix(".check-input.npy"), mix.numpy())
    np.save(args.out.with_suffix(".check-output.npy"), patched.numpy())


if __name__ == "__main__":
    main()
