#!/usr/bin/env python3
# ============================================================================
#  Steven Piano - Android player for the self-playing acoustic piano
#  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
#  Original author & creator: Steven Jin.
#  Licensed under the MIT License (see LICENSE). This copyright and attribution
#  notice MUST be preserved in all copies or substantial portions of the work.
#  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
# ============================================================================

"""Export the Anticipatory Music Transformer (stanford-crfm/music-small-800k) to ONNX, then INT8.

music-small-800k is a GPT-2 (12 layers, 12 heads, 768 wide, 1024 positions, vocabulary 55 028,
tied embeddings) whose config sets scale_attn_by_inverse_layer_idx. transformers >= 4.4x loads
GPT-2 with SDPA attention by default, and its SDPA path silently drops that layer scaling: on
the Bach seed the eager model scores 0.72 nats/token and the SDPA model 5.33. A plain
`optimum-cli export onnx --task text-generation-with-past` therefore exports the broken model
(its own check passes, because it compares against the same SDPA model). This script loads the
model with attn_implementation="eager" and hands it to optimum's exporter
(onnx_export_from_model, task text-generation-with-past: the same graph the CLI builds).

Then two changes for the tablet:
  * logits for the last input position only (a Slice before lm_head): prefilling 1 000 tokens
    would otherwise make 1 000 x 55 028 float logits (220 MB) that nobody reads;
  * dynamic INT8 (onnxruntime.quantization.quantize_dynamic, per-channel S8 weights) of every
    MatMul/Gemm with a constant weight (the 48 transformer Gemms and lm_head; the attention's
    activation x activation MatMuls stay float) and of the token embedding's Gather.
    Measured (verify_composer.py): loss on 1 000 Bach tokens 0.6191 vs 0.6158 in PyTorch,
    teacher-forced top-1 98.2 %, 173 MB. Per-tensor scales: 0.6324 / 96.0 %; leaving the
    embedding in float adds 130 MB for nothing.

  inputs  input_ids                    int64   [1, n]        n new tokens (the seed, then 1)
          attention_mask               int64   [1, p + n]    all ones
          position_ids                 int64   [1, n]        p .. p + n - 1
          past_key_values.{0..11}.key  float32 [1, 12, p, 64] (p = 0 on the first call)
          past_key_values.{0..11}.value float32 [1, 12, p, 64]
  outputs logits                       float32 [1, 1, 55028] the next token's logits
          present.{0..11}.key/value    float32 [1, 12, p + n, 64]

Writes <work>/exports/composer-fp32.onnx (with the last-position slice) and composer-v1.onnx.
"""

import argparse
import json
import os
import shutil
import time

import numpy as np
import onnx
import torch
from onnx import helper, numpy_helper

import studio_common as sc


def load_model(attn="eager"):
    from transformers import AutoModelForCausalLM
    return AutoModelForCausalLM.from_pretrained(sc.COMPOSER_REPO, revision=sc.COMPOSER_REVISION,
                                                attn_implementation=attn).eval()


def seed_loss(model, tokens):
    x = torch.tensor([tokens])
    with torch.no_grad():
        return float(model(x, labels=x).loss)


def last_position_logits(model):
    """Insert Slice(hidden, -1:, axis 1) before lm_head, so logits are [batch, 1, vocab]."""
    g = model.graph
    producer = {o: n for n in g.node for o in n.output}
    lm = producer["logits"]
    if lm.op_type != "MatMul":
        raise SystemExit(f"logits come from {lm.op_type}, expected MatMul")
    g.initializer.extend([
        numpy_helper.from_array(np.array([-1], np.int64), "last_position_starts"),
        numpy_helper.from_array(np.array([np.iinfo(np.int64).max], np.int64), "last_position_ends"),
        numpy_helper.from_array(np.array([1], np.int64), "last_position_axes"),
    ])
    sl = helper.make_node("Slice", [lm.input[0], "last_position_starts", "last_position_ends", "last_position_axes"],
                          ["last_position_hidden"], name="/lm_head/last_position")
    idx = list(g.node).index(lm)
    g.node.insert(idx, sl)
    lm.input[0] = "last_position_hidden"
    for o in g.output:
        if o.name == "logits":
            dim = o.type.tensor_type.shape.dim[1]
            dim.Clear()
            dim.dim_value = 1
    return model


def quantize(src, dst, per_channel, gather):
    from onnxruntime.quantization import QuantType, quantize_dynamic
    ops = ["MatMul", "Gemm"] + (["Gather"] if gather else [])
    quantize_dynamic(src, dst, op_types_to_quantize=ops, weight_type=QuantType.QInt8, per_channel=per_channel,
                     reduce_range=False, extra_options={"MatMulConstBOnly": True})
    return {"op_types": ops, "weight_type": "QInt8", "per_channel": per_channel, "MatMulConstBOnly": True}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--work")
    ap.add_argument("--per-tensor", action="store_true", help="per-tensor weight scales (default per-channel)")
    ap.add_argument("--no-gather", action="store_true", help="leave the token embedding in float")
    ap.add_argument("--out", default="composer-v1.onnx")
    ap.add_argument("--skip-export", action="store_true", help="reuse <work>/exports/composer-fp32.onnx")
    args = ap.parse_args()
    work = sc.work_dir(args.work)
    exports = os.path.join(work, "exports")
    fp32 = os.path.join(exports, "composer-fp32.onnx")
    int8 = os.path.join(exports, args.out)
    info = {"model": sc.COMPOSER_REPO, "revision": sc.COMPOSER_REVISION, "torch": torch.__version__,
            "onnx": onnx.__version__}

    if not args.skip_export:
        import transformers
        from optimum.exporters.onnx import onnx_export_from_model
        from anticipation import ops
        from anticipation.convert import midi_to_events
        from anticipation.vocab import AUTOREGRESS
        info["transformers"] = transformers.__version__
        model = load_model("eager")
        events = midi_to_events(os.path.join(sc.FIXTURES, "bach_bwv846.mid"))
        seq = [AUTOREGRESS] + ops.pad(ops.clip(events, 0, 30, clip_duration=False))[:1000]
        info["bach_seed_loss_nats_per_token"] = {"eager": round(seed_loss(model, seq), 4),
                                                 "sdpa": round(seed_loss(load_model("sdpa"), seq), 4)}
        print("loss on the Bach tokens:", info["bach_seed_loss_nats_per_token"])
        raw_dir = os.path.join(exports, "composer-optimum")
        shutil.rmtree(raw_dir, ignore_errors=True)
        t = time.time()
        onnx_export_from_model(model, raw_dir, task="text-generation-with-past", do_validation=True)
        info["export_seconds"] = round(time.time() - t, 1)
        m = onnx.load(os.path.join(raw_dir, "model.onnx"))
        info["optimum_opset"] = [o.version for o in m.opset_import if o.domain in ("", "ai.onnx")][0]
        m = last_position_logits(m)
        m.producer_name = "steven-piano-studio-spike"
        m.doc_string = ("Anticipatory Music Transformer music-small-800k (stanford-crfm, Apache-2.0; "
                        "Thickstun, Hall, Donahue, Liang 2023), eager attention, exported with optimum "
                        "(text-generation-with-past), logits for the last position only")
        onnx.checker.check_model(m)
        onnx.save(m, fp32)
        shutil.rmtree(raw_dir, ignore_errors=True)
    info["quant"] = quantize(fp32, int8, not args.per_tensor, not args.no_gather)
    q = onnx.load(int8)
    q.doc_string = onnx.load(fp32, load_external_data=False).doc_string + "; quantised to INT8 (dynamic) for Steven Piano"
    onnx.save(q, int8)
    for path in (fp32, int8):
        m = onnx.load(path, load_external_data=False)
        info[os.path.basename(path)] = {
            "bytes": os.path.getsize(path), "sha256": sc.sha256_file(path),
            "inputs": [[i.name, [d.dim_param or d.dim_value for d in i.type.tensor_type.shape.dim],
                        onnx.TensorProto.DataType.Name(i.type.tensor_type.elem_type)] for i in m.graph.input],
            "outputs": [[o.name, [d.dim_param or d.dim_value for d in o.type.tensor_type.shape.dim],
                         onnx.TensorProto.DataType.Name(o.type.tensor_type.elem_type)] for o in m.graph.output],
            "opset": [o.version for o in m.opset_import if o.domain in ("", "ai.onnx")][0],
        }
    with open(os.path.join(exports, os.path.splitext(args.out)[0] + ".export.json"), "w") as f:
        json.dump(info, f, indent=2)
    print(json.dumps({k: v for k, v in info.items() if not isinstance(v, dict) or "bytes" not in v}, indent=2))
    for path in (fp32, int8):
        print(os.path.basename(path), info[os.path.basename(path)]["bytes"], info[os.path.basename(path)]["sha256"])


if __name__ == "__main__":
    main()
