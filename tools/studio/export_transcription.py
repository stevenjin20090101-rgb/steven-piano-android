#!/usr/bin/env python3
# ============================================================================
#  Steven Piano - Android player for the self-playing acoustic piano
#  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
#  Original author & creator: Steven Jin.
#  Licensed under the MIT License (see LICENSE). This copyright and attribution
#  notice MUST be preserved in all copies or substantial portions of the work.
#  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
# ============================================================================

"""Export ByteDance's high-resolution piano transcription (notes + pedal) to ONNX, then INT8.

The model is piano_transcription_inference's Note_pedal: Regress_onset_offset_frame_velocity_CRNN
(notes) plus Regress_pedal_CRNN (pedal), loaded strictly from the Zenodo checkpoint
CRNN_note_F1=0.9677_pedal_F1=0.9186.pth. The two sub-models carry bit-identical log-mel front ends
(torchlibrosa's Conv1d STFT + mel matrix), so the export computes the log-mel once and feeds both;
the outputs are bit-identical to Note_pedal's (checked below before exporting).

  input   audio                    float32 [1, 160000]   10 s of 16 kHz mono, samples in [-1, 1]
  outputs reg_onset_output         float32 [1, 1001, 88]
          reg_offset_output        float32 [1, 1001, 88]
          frame_output             float32 [1, 1001, 88]
          velocity_output          float32 [1, 1001, 88]
          reg_pedal_onset_output   float32 [1, 1001, 1]
          reg_pedal_offset_output  float32 [1, 1001, 1]
          pedal_frame_output       float32 [1, 1001, 1]

Steps: torch.onnx.export (TorchScript exporter, opset 17, static shapes) -> onnxsim -> 
onnxruntime.quantization.quantize_dynamic (weights INT8, activations quantised at run time).
Writes <work>/exports/transcription-fp32.onnx and transcription-v1.onnx (INT8).

Usage: python export_transcription.py --work DIR [--quant matmul|conv] [--out NAME]
"""

import argparse
import json
import os
import time

import numpy as np
import onnx
import torch
import torch.nn as nn

import studio_common as sc


class NotePedalSharedFrontEnd(nn.Module):
    """Note_pedal with one log-mel front end feeding both sub-models; outputs as a tuple."""

    def __init__(self, checkpoint):
        super().__init__()
        from piano_transcription_inference.models import (Regress_onset_offset_frame_velocity_CRNN,
                                                          Regress_pedal_CRNN)
        state = torch.load(checkpoint, map_location="cpu", weights_only=True)["model"]
        note = Regress_onset_offset_frame_velocity_CRNN(sc.FRAMES_PER_SECOND, sc.CLASSES)
        pedal = Regress_pedal_CRNN(sc.FRAMES_PER_SECOND, sc.CLASSES)
        note.load_state_dict(state["note_model"], strict=True)
        pedal.load_state_dict(state["pedal_model"], strict=True)
        for key in ("spectrogram_extractor.stft.conv_real.weight",
                    "spectrogram_extractor.stft.conv_imag.weight", "logmel_extractor.melW"):
            if not torch.equal(state["note_model"][key], state["pedal_model"][key]):
                raise SystemExit(f"front ends differ at {key}; cannot share")
        self.spectrogram_extractor = note.spectrogram_extractor
        self.logmel_extractor = note.logmel_extractor
        note.spectrogram_extractor = nn.Identity()
        note.logmel_extractor = nn.Identity()
        pedal.spectrogram_extractor = nn.Identity()
        pedal.logmel_extractor = nn.Identity()
        self.note_model = note
        self.pedal_model = pedal

    def forward(self, audio):
        logmel = self.logmel_extractor(self.spectrogram_extractor(audio))  # [1, 1, 1001, 229]
        out = dict(self.note_model(logmel))
        out.update(self.pedal_model(logmel))
        return tuple(out[k] for k in sc.OUTPUT_NAMES)


def reference_model(checkpoint):
    """The package's own Note_pedal, loaded strictly (its load_state_dict defaults to strict=False)."""
    from piano_transcription_inference.models import Note_pedal
    state = torch.load(checkpoint, map_location="cpu", weights_only=True)["model"]
    m = Note_pedal(sc.FRAMES_PER_SECOND, sc.CLASSES)
    m.note_model.load_state_dict(state["note_model"], strict=True)
    m.pedal_model.load_state_dict(state["pedal_model"], strict=True)
    return m.eval()


def frontend_nodes(model):
    """The STFT convolutions and the mel MatMul: kept in float (INT8 there costs the log-mel's floor)."""
    return [n.name for n in model.graph.node
            if n.name.startswith("/spectrogram_extractor/") or n.name.startswith("/logmel_extractor/")]


def first_convs(model):
    """Each branch's first convolution (1 -> 48 channels over the normalised log-mel)."""
    return [n.name for n in model.graph.node if n.name.endswith("conv_block1/conv1/Conv")]


# Measured on the three verification clips (tools/studio/verify_transcription.py, docs/STUDIO_SPIKE.md):
#   matmul: fc5 MatMuls and the Gemm heads INT8 (per-channel S8), convolutions and GRUs float:
#           agreement with PyTorch 99.6-100 %, no faster than fp32 on the Mac. The default.
#   conv:   also the convolutions (per-channel U8) except each branch's first one: 97.8-99.0 %,
#           about a third faster; the fallback if a tablet misses the time gate.
#   (Conv INT8 including the first convolutions: 93.0-98.5 %, fails the 97 % gate.)
# ORT's dynamic quantisation has no GRU kernel, so the GRUs (20.8 M of 38.5 M weights) stay float.
QUANT_MODES = ("matmul", "conv")


def quantize(src, dst, mode):
    from onnxruntime.quantization import QuantType, quantize_dynamic
    model = onnx.load(src)
    if mode == "matmul":
        ops, exclude, wtype = ["MatMul", "Gemm"], frontend_nodes(model), QuantType.QInt8
    elif mode == "conv":
        ops, exclude, wtype = ["Conv", "MatMul", "Gemm"], frontend_nodes(model) + first_convs(model), QuantType.QUInt8
    else:
        raise SystemExit(f"unknown --quant {mode}")
    quantize_dynamic(src, dst, op_types_to_quantize=ops, nodes_to_exclude=exclude, weight_type=wtype,
                     per_channel=True, reduce_range=False)
    return {"mode": mode, "op_types": ops, "weight_type": wtype.name, "per_channel": True,
            "excluded_nodes": exclude}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--work")
    ap.add_argument("--quant", default="matmul", choices=QUANT_MODES)
    ap.add_argument("--out", default="transcription-v1.onnx", help="INT8 file name under <work>/exports")
    args = ap.parse_args()
    work = sc.work_dir(args.work)
    ckpt = sc.checkpoint_path(work)
    exports = os.path.join(work, "exports")
    raw = os.path.join(exports, "transcription-fp32-raw.onnx")
    fp32 = os.path.join(exports, "transcription-fp32.onnx")
    int8 = os.path.join(exports, args.out)
    info = {"checkpoint": sc.CHECKPOINT_NAME, "checkpoint_sha256": sc.CHECKPOINT_SHA256,
            "torch": torch.__version__, "onnx": onnx.__version__}

    model = NotePedalSharedFrontEnd(ckpt).eval()
    ref = reference_model(ckpt)
    x = torch.from_numpy(np.random.default_rng(0).standard_normal((1, sc.SEGMENT_SAMPLES)).astype(np.float32) * 0.1)
    with torch.no_grad():
        a, b = ref(x), model(x)
    diff = max(float((a[k] - v).abs().max()) for k, v in zip(sc.OUTPUT_NAMES, b))
    print(f"shared front end vs Note_pedal: max abs diff {diff}")
    if diff != 0.0:
        raise SystemExit("the shared front end changed the outputs")

    t = time.time()
    torch.onnx.export(model, (torch.zeros(1, sc.SEGMENT_SAMPLES),), raw, opset_version=17,
                      input_names=["audio"], output_names=sc.OUTPUT_NAMES, dynamo=False,
                      do_constant_folding=True)
    info["export_seconds"] = round(time.time() - t, 1)

    import onnxsim
    simplified, ok = onnxsim.simplify(onnx.load(raw))
    if not ok:
        raise SystemExit("onnxsim check failed")
    simplified.producer_name = "steven-piano-studio-spike"
    simplified.doc_string = ("ByteDance high-resolution piano transcription (Note_pedal), checkpoint "
                             + sc.CHECKPOINT_NAME + " (Zenodo 10.5281/zenodo.4034264, CC-BY-4.0, "
                             "Qiuqiang Kong et al.); converted to ONNX")
    onnx.save(simplified, fp32)
    os.remove(raw)
    info["quant"] = quantize(fp32, int8, args.quant)
    q = onnx.load(int8)
    q.doc_string = simplified.doc_string + " and quantised to INT8 (dynamic) for Steven Piano"
    onnx.save(q, int8)

    for path in (fp32, int8):
        m = onnx.load(path)
        onnx.checker.check_model(m)
        info[os.path.basename(path)] = {
            "bytes": os.path.getsize(path), "sha256": sc.sha256_file(path),
            "inputs": [[i.name, [d.dim_value for d in i.type.tensor_type.shape.dim]] for i in m.graph.input],
            "outputs": [[o.name, [d.dim_value for d in o.type.tensor_type.shape.dim]] for o in m.graph.output],
            "opset": [o.version for o in m.opset_import if o.domain in ("", "ai.onnx")][0],
        }
    with open(os.path.join(exports, os.path.splitext(args.out)[0] + ".export.json"), "w") as f:
        json.dump(info, f, indent=2)
    print(json.dumps(info, indent=2))


if __name__ == "__main__":
    main()
