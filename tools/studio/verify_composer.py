#!/usr/bin/env python3
# ============================================================================
#  Steven Piano - Android player for the self-playing acoustic piano
#  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
#  Original author & creator: Steven Jin.
#  Licensed under the MIT License (see LICENSE). This copyright and attribution
#  notice MUST be preserved in all copies or substantial portions of the work.
#  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
# ============================================================================

"""Verify the composer ONNX exports against PyTorch (eager attention) and dump the seed fixture.

Seed: the first 15 s of fixtures/bach_bwv846.mid tokenised exactly as the anticipation package's
generate() builds its prompt (midi_to_events -> clip(0, 15 s) -> pad), after the AUTOREGRESS flag.
Greedy decoding uses the package's own per-position rules (anticipation/sample.py add_token):
safe_logits (no control or special tokens; time / duration / note by position), future_logits
(no time before the current time) and instr_logits (at most 15 instruments), then argmax instead
of sampling. PyTorch runs the package's way (the whole window every step, no cache); ONNX runs the
app's way (prefill, then one token per call through the KV cache).
Reported: the 64 greedy tokens (fp32 and INT8 against PyTorch), teacher-forced top-1 agreement
and loss over the first 1 000 Bach tokens, and Mac step timings. The fixture
(fixtures/composer_seed.json) holds the vocabulary layout, the seed tokens and PyTorch's 64 tokens.
"""

import argparse
import json
import math
import os
import time

import numpy as np
import torch

import studio_common as sc
from anticipation import ops
from anticipation.config import (DELTA, MAX_DUR, MAX_INSTR, MAX_NOTE, MAX_PITCH, MAX_TIME,
                                 TIME_RESOLUTION)
from anticipation.convert import midi_to_events
from anticipation.vocab import (ANTICIPATE, AUTOREGRESS, CONTROL_OFFSET, DUR_OFFSET, NOTE_OFFSET,
                                REST, SEPARATOR, SPECIAL_OFFSET, TIME_OFFSET, VOCAB_SIZE)

SEED_SECONDS = 15
N_TOKENS = 64
LOOKBACK = 1017                  # add_token's Markov window: history = tokens[-1017:]
TEACHER_TOKENS = 1000


EXTRA_SEEDS = (15, 30, 45, 60)   # more 15 s seeds (start second in the Bach clip), translated to 0


def seed_tokens(start_s=0):
    events = midi_to_events(os.path.join(sc.FIXTURES, "bach_bwv846.mid"))
    if start_s:
        events = ops.clip(events, start_s, start_s + SEED_SECONDS, clip_duration=False)
        events = ops.translate(events, -ops.min_time(events, seconds=False), seconds=False)
    end = TIME_RESOLUTION * SEED_SECONDS
    prompt = ops.pad(ops.clip(events, 0, end, clip_duration=False, seconds=False), end)
    return events, prompt


def mask_logits(logits, idx, curtime_rel, history_abs):
    """anticipation.sample.add_token's rules for one position; logits: float numpy [VOCAB]."""
    l = logits.astype(np.float64).copy()
    l[CONTROL_OFFSET:SPECIAL_OFFSET] = -np.inf
    l[SPECIAL_OFFSET:] = -np.inf
    slot = idx % 3
    if slot == 0:
        l[DUR_OFFSET:DUR_OFFSET + MAX_DUR] = -np.inf
        l[NOTE_OFFSET:NOTE_OFFSET + MAX_NOTE] = -np.inf
        if curtime_rel > 0:
            l[TIME_OFFSET:TIME_OFFSET + curtime_rel] = -np.inf
    elif slot == 1:
        l[TIME_OFFSET:TIME_OFFSET + MAX_TIME] = -np.inf
        l[NOTE_OFFSET:NOTE_OFFSET + MAX_NOTE] = -np.inf
    else:
        l[TIME_OFFSET:TIME_OFFSET + MAX_TIME] = -np.inf
        l[DUR_OFFSET:DUR_OFFSET + MAX_DUR] = -np.inf
        instrs = ops.get_instruments(history_abs)
        if len(instrs) >= 15:
            for instr in range(MAX_INSTR):
                if instr not in instrs:
                    l[NOTE_OFFSET + instr * MAX_PITCH:NOTE_OFFSET + (instr + 1) * MAX_PITCH] = -np.inf
    return l


def window(tokens):
    history = tokens[max(len(tokens) - LOOKBACK, 0):]
    offset = ops.min_time(history, seconds=False)
    history = history.copy()
    history[::3] = [t - offset for t in history[::3]]
    return history, offset


def greedy_torch(model, prompt, n):
    """The package's generate/add_token loop with argmax; no end_time (a fixed token count)."""
    tokens = prompt.copy()
    current_time = ops.max_time(prompt, seconds=False)
    out = []
    while len(out) < n:
        history, offset = window(tokens)
        new = []
        for i in range(3):
            x = torch.tensor([[AUTOREGRESS] + history + new])
            with torch.no_grad():
                logits = model(x).logits[0, -1].numpy()
            idx = x.shape[1] - 1
            tok = int(np.argmax(mask_logits(logits, idx, current_time - offset if i == 0 else 0, tokens)))
            new.append(tok)
            out.append(tok + (offset if i == 0 else 0))
            if len(out) == n:
                break
        if len(new) == 3:
            new[0] += offset
            tokens.extend(new)
            current_time = new[0] - TIME_OFFSET
    return out


class OnnxLM:
    """The app's way: prefill once, then one token per call, feeding present.* back as past."""

    def __init__(self, path, threads=4):
        import onnxruntime as ort
        so = ort.SessionOptions()
        so.intra_op_num_threads = threads
        so.inter_op_num_threads = 1
        so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
        self.sess = ort.InferenceSession(path, so, providers=["CPUExecutionProvider"])
        self.past_names = [i.name for i in self.sess.get_inputs() if i.name.startswith("past_key_values")]
        self.out_names = [o.name for o in self.sess.get_outputs()]
        self.reset()

    def reset(self):
        self.past = {n: np.zeros((1, 12, 0, 64), np.float32) for n in self.past_names}
        self.p = 0

    def feed(self, ids):
        n = len(ids)
        inputs = {"input_ids": np.array([ids], np.int64),
                  "attention_mask": np.ones((1, self.p + n), np.int64),
                  "position_ids": np.arange(self.p, self.p + n, dtype=np.int64)[None, :]}
        inputs.update(self.past)
        res = dict(zip(self.out_names, self.sess.run(self.out_names, inputs)))
        self.past = {n_: res[n_.replace("past_key_values", "present")] for n_ in self.past_names}
        self.p += n
        return res["logits"][0, -1]


def greedy_onnx(lm, prompt, n):
    """Same rules as greedy_torch; the window never slides for a 15 s seed + 64 tokens."""
    tokens = prompt.copy()
    current_time = ops.max_time(prompt, seconds=False)
    history, offset = window(tokens)
    assert offset == 0 and len(history) == len(tokens), "seed must fit the window unshifted"
    lm.reset()
    logits = lm.feed([AUTOREGRESS] + history)
    out = []
    idx = len(history)
    new = []
    while len(out) < n:
        slot = len(new)
        tok = int(np.argmax(mask_logits(logits, idx, current_time if slot == 0 else 0, tokens)))
        out.append(tok)
        new.append(tok)
        if len(new) == 3:
            tokens.extend(new)
            current_time = new[0] - TIME_OFFSET
            new = []
        if len(out) < n:
            logits = lm.feed([tok])
            idx += 1
    return out


def log_softmax(a):
    a = a - a.max()
    return a - np.log(np.exp(a).sum())


def teacher_forced(model, lm, seq):
    """Per-position masked top-1 and mean NLL of the true next token: torch full pass vs ONNX steps."""
    x = torch.tensor([seq])
    with torch.no_grad():
        tl = model(x).logits[0].numpy()
    lm.reset()
    ol = [lm.feed([seq[0]])]
    for t in seq[1:-1]:
        ol.append(lm.feed([t]))
    ol = np.stack(ol)
    agree, nll_t, nll_o, kls, lpdiff = 0, [], [], [], 0.0
    for i in range(len(seq) - 1):
        # position i predicts seq[i+1]; idx = i (the last input's index)
        mt = mask_logits(tl[i], i, 0, seq[1:i + 1])
        mo = mask_logits(ol[i], i, 0, seq[1:i + 1])
        agree += int(np.argmax(mt) == np.argmax(mo))
        valid = np.isfinite(mt)                     # the tokens this slot can actually choose from
        lt, lo = log_softmax(mt[valid]), log_softmax(mo[valid])
        pt = np.exp(lt)
        kls.append(float(np.sum(pt * (lt - lo))))
        likely = pt > 1e-3
        lpdiff = max(lpdiff, float(np.abs(lt[likely] - lo[likely]).max()))
        for arr, acc in ((tl[i], nll_t), (ol[i], nll_o)):
            a = arr.astype(np.float64)
            acc.append(float(np.log(np.exp(a - a.max()).sum()) + a.max() - a[seq[i + 1]]))
    return (agree / (len(seq) - 1), float(np.mean(nll_t)), float(np.mean(nll_o)),
            {"kl_mean": round(float(np.mean(kls)), 5), "kl_max": round(float(np.max(kls)), 4),
             "max_abs_logprob_diff_p_over_1e-3": round(lpdiff, 4)})


def step_timing(lm, lengths=(0, 256, 512, 1000), steps=16):
    """ms per single-token step at a few cache lengths, and a 511-token prefill."""
    rng = np.random.default_rng(0)
    res = {}
    lm.reset()
    t = time.time()
    lm.feed([AUTOREGRESS] + list(rng.integers(0, 10000, 510)))
    res["prefill_511_ms"] = round(1000 * (time.time() - t), 1)
    for p in lengths:
        lm.reset()
        if p:
            lm.feed([AUTOREGRESS] + list(rng.integers(0, 10000, p - 1)))
        t = time.time()
        for _ in range(steps):
            lm.feed([int(rng.integers(0, 10000))])
        res[f"step_ms_at_{p}"] = round(1000 * (time.time() - t) / steps, 2)
    return res


def decode(tokens):
    ev = []
    for t, d, n in zip(tokens[0::3], tokens[1::3], tokens[2::3]):
        if n == REST:
            ev.append({"time": t - TIME_OFFSET, "rest": True})
        else:
            note = n - NOTE_OFFSET
            ev.append({"time": t - TIME_OFFSET, "duration": d - DUR_OFFSET,
                       "instrument": note // MAX_PITCH, "pitch": note % MAX_PITCH})
    return ev


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--work")
    ap.add_argument("--int8", default="composer-v1.onnx")
    ap.add_argument("--no-fixture", action="store_true")
    ap.add_argument("--skip-fp32", action="store_true")
    args = ap.parse_args()
    work = sc.work_dir(args.work)
    exports = os.path.join(work, "exports")
    torch.set_num_threads(4)
    from export_composer import load_model
    model = load_model("eager")
    events, prompt = seed_tokens()
    report = {"int8_file": args.int8, "seed_tokens": 1 + len(prompt), "seed_events": len(prompt) // 3}

    t = time.time()
    ref = greedy_torch(model, prompt, N_TOKENS)
    report["torch_greedy_s"] = round(time.time() - t, 2)
    extra = {s_: seed_tokens(s_)[1] for s_ in EXTRA_SEEDS}
    extra_ref = {s_: greedy_torch(model, p_, N_TOKENS) for s_, p_ in extra.items()}
    seq = ([AUTOREGRESS] + ops.pad(events))[:TEACHER_TOKENS + 1]
    files = [("int8", args.int8)] if args.skip_fp32 else [("fp32", "composer-fp32.onnx"), ("int8", args.int8)]
    for name, fn in files:
        lm = OnnxLM(os.path.join(exports, fn))
        got = greedy_onnx(lm, prompt, N_TOKENS)
        first_diff = next((i for i, (a, b) in enumerate(zip(ref, got)) if a != b), None)
        agree, nll_t, nll_o, maxdiff = teacher_forced(model, lm, seq)
        more = {}
        for s_, p_ in extra.items():
            g_ = greedy_onnx(lm, p_, N_TOKENS)
            more[str(s_)] = next((i for i, (a, b) in enumerate(zip(extra_ref[s_], g_)) if a != b), "all 64")
        report[name] = {"file": fn, "bytes": os.path.getsize(os.path.join(exports, fn)),
                        "greedy_64_match": got == ref, "greedy_first_difference": first_diff,
                        "greedy_tokens": got,
                        "greedy_first_difference_other_seeds": more,
                        "teacher_forced_top1_agreement": round(agree, 4),
                        "teacher_forced_tokens": len(seq) - 1,
                        "loss_torch": round(nll_t, 4), "loss_onnx": round(nll_o, 4),
                        "distribution_vs_torch": maxdiff,
                        "mac_timing_4_threads": step_timing(lm)}
        print(name, json.dumps({k: v for k, v in report[name].items() if k != "greedy_tokens"}), flush=True)
    report["torch_greedy_tokens"] = ref
    with open(os.path.join(work, "logs", "verify_" + os.path.splitext(args.int8)[0] + ".json"), "w") as f:
        json.dump(report, f, indent=2)

    if not args.no_fixture:
        from anticipation import vocab
        layout = {k: getattr(vocab, k) for k in ("EVENT_OFFSET", "TIME_OFFSET", "DUR_OFFSET", "NOTE_OFFSET",
                                                 "REST", "CONTROL_OFFSET", "ATIME_OFFSET", "ADUR_OFFSET",
                                                 "ANOTE_OFFSET", "SPECIAL_OFFSET", "SEPARATOR", "AUTOREGRESS",
                                                 "ANTICIPATE", "VOCAB_SIZE")}
        layout.update({"MAX_TIME": MAX_TIME, "MAX_DUR": MAX_DUR, "MAX_PITCH": MAX_PITCH, "MAX_INSTR": MAX_INSTR,
                       "MAX_NOTE": MAX_NOTE, "TIME_RESOLUTION": TIME_RESOLUTION, "DELTA_SECONDS": DELTA,
                       "CONTEXT_SIZE": 1024, "LOOKBACK": LOOKBACK})
        fixture = {
            "about": ("The composer's seed and greedy continuation. Seed: the first 15 s of "
                      "fixtures/bach_bwv846.mid through anticipation's midi_to_events -> clip(0, 1500 ticks) "
                      "-> pad, after the AUTOREGRESS flag (arrival-time triples: time, duration, note; "
                      "times in 10 ms ticks from the start, durations in 10 ms ticks, note = instrument * 128 "
                      "+ pitch). Continuation: 64 tokens of greedy decoding with anticipation.sample's "
                      "masks (safe_logits, future_logits, instr_logits) and argmax, from PyTorch "
                      "(eager attention), the package's way (the whole window each step)."),
            "model": {"repo": sc.COMPOSER_REPO, "revision": sc.COMPOSER_REVISION,
                      "anticipation_commit": sc.ANTICIPATION_COMMIT},
            "vocabulary": layout,
            "seed_seconds": SEED_SECONDS,
            "seed_current_time_ticks": ops.max_time(prompt, seconds=False),
            "input_tokens": [AUTOREGRESS] + prompt,
            "expected_continuation": ref,
            "expected_continuation_events": decode(ref[:len(ref) // 3 * 3]),
            "onnx_int8_continuation": report["int8"]["greedy_tokens"],
            "onnx_int8_matches": report["int8"]["greedy_64_match"],
        }
        path = os.path.join(sc.FIXTURES, "composer_seed.json")
        sc.write_json(path, fixture)
        print(f"fixture: {len(fixture['input_tokens'])} input tokens, {len(ref)} expected, "
              f"{os.path.getsize(path)} bytes")


if __name__ == "__main__":
    main()
