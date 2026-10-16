#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""How much the NPU export's shorter window costs, on a song whose parts are known.

Synthesises 20 s of music from separate parts (drums, bass, a sung-like
melody, piano chords), separates the mix with the original HTDemucs (Demucs'
own 7.8 s windows) and with the NPU export (1.95 s windows, overlap-added as
Tryptify's ChunkedSeparation does), and reports, per stem, how close each is
to the part it should have found and how close the two models are to each
other. SDR in dB; higher is closer.

Usage: compare_windows.py MODEL.onnx
"""

import sys

import numpy as np
import onnxruntime as ort
import torch

SR = 44_100
SECONDS = 20
BPM = 120
SOURCES = ["drums", "bass", "other", "vocals", "guitar", "piano"]


def song(seed: int = 3):
    rng = np.random.default_rng(seed)
    n = SR * SECONDS
    t = np.arange(n) / SR
    beat = 60 / BPM
    drums = np.zeros(n)
    for i in range(int(SECONDS / beat * 2)):
        start = int(i * beat / 2 * SR)
        if i % 2 == 0:  # kick on every beat
            k = np.arange(int(0.25 * SR)) / SR
            drums[start:start + len(k)] += 0.9 * np.sin(2 * np.pi * (50 + 80 * np.exp(-k * 30)) * k) * np.exp(-k * 12)
        if i % 4 == 2:  # snare on 2 and 4
            s = int(0.18 * SR)
            drums[start:start + s] += 0.5 * rng.standard_normal(s) * np.exp(-np.arange(s) / SR * 25)
        h = int(0.04 * SR)  # hat on every eighth
        hat = rng.standard_normal(h)
        hat = np.diff(hat, prepend=0)
        drums[start:start + h] += 0.15 * hat * np.exp(-np.arange(h) / SR * 80)
    bass_notes = [41.2, 41.2, 55.0, 49.0]
    bass = np.zeros(n)
    for i in range(int(SECONDS / (beat / 2))):
        f = bass_notes[(i // 4) % 4]
        start = int(i * beat / 2 * SR)
        d = int(beat / 2 * SR)
        k = np.arange(d) / SR
        phase = 2 * np.pi * f * k
        bass[start:start + d] += 0.5 * (np.sin(phase) + 0.3 * np.sin(2 * phase)) * np.exp(-k * 3)
    melody = [220, 247, 262, 294, 330, 294, 262, 247]
    vocals = np.zeros(n)
    for i in range(int(SECONDS / beat)):
        f = melody[i % len(melody)]
        start = int(i * beat * SR)
        d = int(beat * SR)
        k = np.arange(d) / SR
        f_t = f * (1 + 0.01 * np.sin(2 * np.pi * 5.5 * k))
        phase = 2 * np.pi * np.cumsum(f_t) / SR
        voice = sum((1 / h) * np.sin(h * phase) * np.exp(-((h * f - 700) / 500) ** 2) for h in range(1, 12))
        env = np.minimum(1, k / 0.05) * np.minimum(1, (beat - k) / 0.08)
        vocals[start:start + d] += 0.35 * voice * env
    chords = [[262, 330, 392], [220, 262, 330], [175, 220, 262], [196, 247, 294]]
    piano = np.zeros(n)
    for i in range(int(SECONDS / (beat * 2))):
        start = int(i * beat * 2 * SR)
        d = int(beat * 2 * SR)
        k = np.arange(d) / SR
        for f in chords[i % 4]:
            piano[start:start + d] += 0.12 * sum((0.6 ** h) * np.sin(2 * np.pi * f * (h + 1) * k) for h in range(4)) * np.exp(-k * 2.5)
    parts = {"drums": drums, "bass": bass, "vocals": vocals, "piano": piano}
    stereo = {name: np.stack([p, p]).astype(np.float32) for name, p in parts.items()}
    mix = sum(stereo.values())
    return mix.astype(np.float32), stereo


def sdr(reference, estimate):
    num = np.sum(reference ** 2)
    den = np.sum((reference - estimate) ** 2) + 1e-12
    return 10 * np.log10(num / den + 1e-12)


def original(mix):
    from demucs.apply import apply_model
    from demucs.pretrained import get_model
    model = get_model("htdemucs_6s")
    with torch.no_grad():
        return apply_model(model, torch.from_numpy(mix)[None], overlap=0.25, shifts=0, split=True)[0].numpy()


def chunked(onnx_path, mix, overlap=0.25):
    session = ort.InferenceSession(onnx_path, providers=["CPUExecutionProvider"])
    seg = session.get_inputs()[0].shape[-1]
    total = mix.shape[-1]
    hop = max(1, int(seg * (1 - overlap)))
    half = seg // 2
    weights = np.array([i + 1 for i in range(half)] + [seg - i for i in range(half, seg)], dtype=np.float64)
    weights = np.maximum(weights / weights.max(), 1e-6)
    out = np.zeros((6, 2, total))
    wsum = np.zeros(total)
    start = 0
    while True:
        chunk = np.zeros((2, seg), dtype=np.float32)
        valid = min(seg, total - start)
        chunk[:, :valid] = mix[:, start:start + valid]
        stems = session.run(["stems"], {"mix": chunk[None]})[0][0]
        out[:, :, start:start + valid] += stems[:, :, :valid] * weights[:valid]
        wsum[start:start + valid] += weights[:valid]
        if start + seg >= total:
            break
        start += hop
    return out / wsum


def main():
    mix, parts = song()
    ref = original(mix)
    new = chunked(sys.argv[1], mix)
    print(f"{'stem':8} {'original vs truth':>18} {'NPU export vs truth':>20} {'NPU vs original':>16}")
    for name in ["drums", "bass", "vocals", "piano"]:
        i = SOURCES.index(name)
        truth = parts[name]
        print(f"{name:8} {sdr(truth, ref[i]):18.1f} {sdr(truth, new[i]):20.1f} {sdr(ref[i], new[i]):16.1f}")
    # The chart reads drum onsets: how alike the two drum stems' envelopes are.
    def envelope(x):
        e = np.abs(x).mean(0)
        return e.reshape(-1, 441).mean(1)
    a = envelope(ref[0]); b = envelope(new[0])
    print(f"drum envelope correlation, NPU export vs original: {np.corrcoef(a, b)[0, 1]:.3f}")


if __name__ == "__main__":
    main()
