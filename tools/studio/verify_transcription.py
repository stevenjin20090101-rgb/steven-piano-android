#!/usr/bin/env python3
# ============================================================================
#  Steven Piano - Android player for the self-playing acoustic piano
#  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
#  Original author & creator: Steven Jin.
#  Licensed under the MIT License (see LICENSE). This copyright and attribution
#  notice MUST be preserved in all copies or substantial portions of the work.
#  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
# ============================================================================

"""Verify the transcription ONNX exports against PyTorch, and dump the NotePostProcessor fixture.

For each clip (16 kHz mono WAVs from make_test_audio.py) the audio is cut into the app's windows
(10 s, hop 5 s, the package's enframe), run through the package's own Note_pedal in PyTorch and
through the fp32 and INT8 ONNX files with onnxruntime (CPU, 4 intra-op threads), stitched with the
package's deframe, and post-processed with the package's RegressionPostProcessor (its thresholds:
onset 0.3, offset 0.3, frame 0.1, pedal offset 0.2). Reported per clip and model:
  * max abs diff vs PyTorch for every output;
  * note agreement vs PyTorch's notes: one-to-one matches with equal pitch and onsets within 50 ms
    (mir_eval.transcription.match_notes, offsets ignored) -> precision, recall, F1 ("agreement");
  * velocity and pedal agreement; onset F1 against the MIDI ground truth for the rendered Bach clip.
Gates: agreement F1 >= 99 % (fp32) and >= 97 % (INT8).

The fixture (fixtures/transcription_window.json): one 10 s window of the licence-clean Bach clip,
the INT8 model's seven raw outputs (sparse where the post-processor provably ignores the rest; the
reconstruction is asserted to give the same notes), and the notes and pedals the package's
RegressionPostProcessor produces from that single window. fixtures/transcription_window.wav is the
window's input (16 kHz mono PCM-16) for an on-device end-to-end check.
"""

import argparse
import json
import os
import time

import numpy as np
import soundfile as sf
import torch

import studio_common as sc
from export_transcription import reference_model

CLIPS = ["cut_liszt.wav", "bach_bwv846.wav", "gymnopedie1.wav"]
FIXTURE_CLIP = "bach_bwv846.wav"
FIXTURE_WINDOW = 2          # samples 160000..320000 (10–20 s): bars 3–6
GATES = {"fp32": 0.99, "int8": 0.97}


def post_process(outputs):
    """outputs: {name: (frames, classes)} -> (notes [on, off, pitch, vel], pedals [on, off])."""
    from piano_transcription_inference.utilities import RegressionPostProcessor
    pp = RegressionPostProcessor(sc.FRAMES_PER_SECOND, classes_num=sc.CLASSES,
                                 onset_threshold=sc.ONSET_THRESHOLD, offset_threshold=sc.OFFSET_THRESHOLD,
                                 frame_threshold=sc.FRAME_THRESHOLD,
                                 pedal_offset_threshold=sc.PEDAL_OFFSET_THRESHOLD)
    notes, pedals = pp.output_dict_to_midi_events({k: v.copy() for k, v in outputs.items()})
    notes = [[float(n["onset_time"]), float(n["offset_time"]), int(n["midi_note"]), int(n["velocity"])] for n in notes]
    pedals = [[float(p["onset_time"]), float(p["offset_time"])] for p in (pedals or [])]
    return notes, pedals


def match(ref_notes, est_notes, tol=0.05):
    import mir_eval
    if not ref_notes or not est_notes:
        return [], 0.0, 0.0, 0.0
    to_iv = lambda ns: np.array([[n[0], max(n[1], n[0] + 1e-3)] for n in ns])
    to_hz = lambda ns: np.array([440.0 * 2 ** ((n[2] - 69) / 12) for n in ns])
    pairs = mir_eval.transcription.match_notes(to_iv(ref_notes), to_hz(ref_notes), to_iv(est_notes), to_hz(est_notes),
                                               onset_tolerance=tol, pitch_tolerance=1.0, offset_ratio=None)
    p = len(pairs) / len(est_notes)
    r = len(pairs) / len(ref_notes)
    f = 0.0 if p + r == 0 else 2 * p * r / (p + r)
    return pairs, p, r, f


def ort_session(path, threads=4):
    import onnxruntime as ort
    so = ort.SessionOptions()
    so.intra_op_num_threads = threads
    so.inter_op_num_threads = 1
    so.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
    so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    return ort.InferenceSession(path, so, providers=["CPUExecutionProvider"])


def run_torch(model, windows):
    outs = {k: [] for k in sc.OUTPUT_NAMES}
    t = time.time()
    with torch.no_grad():
        for w in windows:
            d = model(torch.from_numpy(w[None, :]))
            for k in sc.OUTPUT_NAMES:
                outs[k].append(d[k][0].numpy())
    return {k: np.stack(v) for k, v in outs.items()}, time.time() - t


def run_ort(sess, windows):
    outs = {k: [] for k in sc.OUTPUT_NAMES}
    t = time.time()
    for w in windows:
        res = sess.run(sc.OUTPUT_NAMES, {"audio": w[None, :]})
        for k, v in zip(sc.OUTPUT_NAMES, res):
            outs[k].append(v[0])
    return {k: np.stack(v) for k, v in outs.items()}, time.time() - t


def f32s(x):
    """Shortest decimal that parses back to the same float32."""
    return float(np.format_float_positional(np.float32(x), unique=True, trim="-"))


def near(mask, radius):
    """Frames within `radius` of any True frame, per column."""
    out = mask.copy()
    for s in range(1, radius + 1):
        out[s:] |= mask[:-s]
        out[:-s] |= mask[s:]
    return out


def sparse_fixture(outs):
    """Keep exactly what RegressionPostProcessor reads; everything else becomes 0."""
    keep = {
        # peaks need x > threshold and monotonic neighbours within 2 (onset) / 4 (offset) frames,
        # and the shift reads x[n-1], x[n+1]
        "reg_onset_output": near(outs["reg_onset_output"] > sc.ONSET_THRESHOLD, 3),
        "reg_offset_output": near(outs["reg_offset_output"] > sc.OFFSET_THRESHOLD, 5),
        # only `frame <= 0.1` is asked, so values at or below 0.1 may read as 0
        "frame_output": outs["frame_output"] > sc.FRAME_THRESHOLD,
        # velocity is read at onset frames only, and an onset frame has reg_onset > 0.3
        "velocity_output": outs["velocity_output"] * 0 + (outs["reg_onset_output"] > sc.ONSET_THRESHOLD),
    }
    fx, rebuilt = {}, {}
    for name in sc.OUTPUT_NAMES:
        v = outs[name]
        if name in keep:
            m = keep[name].astype(bool)
            idx = np.flatnonzero(m)
            vals = [f32s(x) for x in v.reshape(-1)[idx]]
            fx[name] = {"shape": list(v.shape), "encoding": "sparse", "index": idx.tolist(), "value": vals}
            r = np.zeros(v.size, dtype=np.float32)
            r[idx] = np.array(vals, dtype=np.float32)
            rebuilt[name] = r.reshape(v.shape)
        else:
            vals = [f32s(x) for x in v.reshape(-1)]
            fx[name] = {"shape": list(v.shape), "encoding": "dense", "value": vals}
            rebuilt[name] = np.array(vals, dtype=np.float32).reshape(v.shape)
    return fx, rebuilt


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--work")
    ap.add_argument("--int8", default="transcription-v1.onnx")
    ap.add_argument("--no-fixture", action="store_true")
    args = ap.parse_args()
    work = sc.work_dir(args.work)
    exports, audio_dir = os.path.join(work, "exports"), os.path.join(work, "audio")
    torch.set_num_threads(4)
    ref = reference_model(sc.checkpoint_path(work))
    sessions = {"fp32": ort_session(os.path.join(exports, "transcription-fp32.onnx")),
                "int8": ort_session(os.path.join(exports, args.int8))}
    report = {"int8_file": args.int8, "clips": {}}
    passed = True
    for clip in CLIPS:
        audio = sc.load_wav_16k(os.path.join(audio_dir, clip))
        windows, n = sc.enframe(audio)
        pt, pt_s = run_torch(ref, windows)
        pt_notes, pt_pedals = post_process({k: sc.deframe(v) for k, v in pt.items()})
        entry = {"seconds": round(n / sc.SAMPLE_RATE, 2), "windows": len(windows),
                 "torch_notes": len(pt_notes), "torch_pedals": len(pt_pedals),
                 "torch_s_per_window": round(pt_s / len(windows), 3)}
        truth = None
        if clip == FIXTURE_CLIP:
            truth = [[a, b, p, v] for a, b, p, v in json.load(open(os.path.join(audio_dir, "bach_bwv846.notes.json")))]
            entry["torch_vs_truth_onset_f1"] = round(match(truth, pt_notes)[3], 4)
        for name, sess in sessions.items():
            o, s = run_ort(sess, windows)
            diffs = {k: float(np.abs(o[k] - pt[k]).max()) for k in sc.OUTPUT_NAMES}
            notes, pedals = post_process({k: sc.deframe(v) for k, v in o.items()})
            pairs, p, r, f = match(pt_notes, notes)
            vel = [abs(pt_notes[i][3] - notes[j][3]) for i, j in pairs]
            e = {"max_abs_diff": {k: float(f"{v:.3g}") for k, v in diffs.items()},
                 "notes": len(notes), "matched": len(pairs),
                 "precision": round(p, 4), "recall": round(r, 4), "agreement_f1": round(f, 4),
                 "velocity_mean_abs_diff": round(float(np.mean(vel)), 3) if vel else None,
                 "velocity_max_abs_diff": int(max(vel)) if vel else None,
                 "pedals": len(pedals),
                 "pedal_max_time_diff_s": (round(max(max(abs(a[0] - b[0]), abs(a[1] - b[1]))
                                                     for a, b in zip(pt_pedals, pedals)), 4)
                                           if pedals and len(pedals) == len(pt_pedals) else None),
                 "mac_ort_s_per_window": round(s / len(windows), 3),
                 "gate": GATES[name], "pass": f >= GATES[name]}
            if truth is not None:
                e["vs_truth_onset_f1"] = round(match(truth, notes)[3], 4)
            passed &= e["pass"]
            entry[name] = e
        report["clips"][clip] = entry
        print(clip, json.dumps(entry, indent=1))

    report["all_gates_pass"] = bool(passed)
    with open(os.path.join(work, "logs", "verify_" + os.path.splitext(args.int8)[0] + ".json"), "w") as f:
        json.dump(report, f, indent=2)

    if not args.no_fixture:
        audio = sc.load_wav_16k(os.path.join(audio_dir, FIXTURE_CLIP))
        windows, _ = sc.enframe(audio)
        w = windows[FIXTURE_WINDOW]
        pcm = np.clip(np.round(w * 32767), -32768, 32767).astype(np.int16)
        wav_path = os.path.join(sc.FIXTURES, "transcription_window.wav")
        sf.write(wav_path, pcm, sc.SAMPLE_RATE, subtype="PCM_16")
        w16 = pcm.astype(np.float32) / 32768.0            # exactly what the app reads back from the WAV
        res = sessions["int8"].run(sc.OUTPUT_NAMES, {"audio": w16[None, :]})
        outs = {k: v[0] for k, v in zip(sc.OUTPUT_NAMES, res)}
        notes, pedals = post_process(outs)
        fx, rebuilt = sparse_fixture(outs)
        notes2, pedals2 = post_process(rebuilt)
        if notes2 != notes or pedals2 != pedals:
            raise SystemExit("sparse fixture does not reproduce the notes")
        with torch.no_grad():
            d = ref(torch.from_numpy(w16[None, :]))
        pt_notes, _ = post_process({k: d[k][0].numpy() for k in sc.OUTPUT_NAMES})
        fixture = {
            "about": ("One 10 s window of the licence-clean Bach clip (tools/studio/make_test_audio.py): "
                      "the INT8 transcription model's raw outputs and what piano_transcription_inference's "
                      "RegressionPostProcessor makes of this single window (no stitching). Sparse outputs "
                      "are 0 wherever the post-processor never reads them (the reconstruction was checked "
                      "to give the same notes). Input: transcription_window.wav read as int16 / 32768."),
            "model": args.int8, "model_sha256": sc.sha256_file(os.path.join(exports, args.int8)),
            "input": {"file": "transcription_window.wav", "sample_rate": sc.SAMPLE_RATE,
                      "samples": sc.SEGMENT_SAMPLES, "clip": FIXTURE_CLIP,
                      "clip_start_sample": FIXTURE_WINDOW * sc.HOP_SAMPLES,
                      "scale": "float = int16 / 32768"},
            "frames": sc.FRAMES_PER_SEGMENT, "classes": sc.CLASSES, "begin_note": sc.BEGIN_NOTE,
            "frames_per_second": sc.FRAMES_PER_SECOND, "velocity_scale": sc.VELOCITY_SCALE,
            "thresholds": {"onset": sc.ONSET_THRESHOLD, "offset": sc.OFFSET_THRESHOLD,
                           "frame": sc.FRAME_THRESHOLD, "pedal_offset": sc.PEDAL_OFFSET_THRESHOLD,
                           "pedal_frame": 0.5},
            "outputs": fx,
            "expected_notes_order": "by key (A0 up), then onset: RegressionPostProcessor's own order",
            "expected_notes": [[round(a, 6), round(b, 6), p, v] for a, b, p, v in notes],
            "expected_pedals": [[round(a, 6), round(b, 6)] for a, b in pedals],
            "torch_notes_same_window": len(pt_notes),
            "torch_agreement_f1": round(match(pt_notes, notes)[3], 4),
        }
        path = os.path.join(sc.FIXTURES, "transcription_window.json")
        sc.write_json(path, fixture)
        print(f"fixture: {len(notes)} notes, {len(pedals)} pedals, {os.path.getsize(path)} bytes json, "
              f"{os.path.getsize(wav_path)} bytes wav; torch agreement {fixture['torch_agreement_f1']}")
    print("ALL GATES PASS" if passed else "A GATE FAILED")


if __name__ == "__main__":
    main()
