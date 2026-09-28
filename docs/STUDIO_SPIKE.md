<!-- ============================================================================
     Steven Piano - Android player for the self-playing acoustic piano
     Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
     Original author & creator: Steven Jin.
     Licensed under the MIT License (see LICENSE). This copyright and attribution
     notice MUST be preserved in all copies or substantial portions of the work.
     Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
     ============================================================================ -->

# Studio spike (M22): can the tablet transcribe and compose piano MIDI locally?

2026-09-28, branch `m22-studio-spike` from `main` at `7a1197d` (1.6.1). No app code ships: the
output is `tools/studio/` (scripts and fixtures), `releases/models.json`, the GitHub release
`models`, and this document. The Mac is an Apple M3 Max (Python 3.13.13, torch 2.14.0,
onnxruntime 1.30.0); the emulator is the `steven_piano` AVD (API 34, arm64-v8a, 4 vCPUs running
natively on the M3 through the hypervisor; booted with `-memory 4096` so the 2 GB in its config
could not push the bench into zram and hide resident pages; the AVD's config was not changed).

## Verdicts

| | Gate | Transcription (INT8) | Composer (INT8) |
|---|---|---|---|
| Time | ≤ 5 min for 3 min of audio; ≤ 60 ms/token (≤ 4 min for 2 min) | **57.4–59.0 s** (6 runs) | **5.69–6.08 ms/token**; 3 600 tokens in 20.5–21.9 s (8 runs) |
| Peak memory | ≤ 1 GB; ≤ 1.5 GB | **715 868–744 072 kB** VmHWM (0.68–0.71 GiB) with ORT memory patterns off; 1 031 508–1 035 032 kB with ORT's defaults | **616 968–644 580 kB** (0.59–0.61 GiB) |
| Agreement | ≥ 99 % fp32, ≥ 97 % INT8; 64 greedy tokens = PyTorch | fp32 **100 %** on all 3 clips; INT8 **100 / 99.93 / 99.59 %** | fp32: **64/64 on 5 of 5 seeds**; INT8: **64/64 on 2 of 5** (diverges at tokens 45, 21, 7) |

- **Transcription: go on the numbers; the tablet must confirm.** Its weights are licensed
  **CC BY 4.0**, not Apache-2.0 as the plan assumed, which was outside this run's original
  Apache-2.0/MIT/CC0 rule: the file was held back until the coordinator accepted CC BY 4.0
  (2026-09-28; the app credits it in About and AUTHORS, as it credits Wikipedia and MAESTRO), and
  `transcription-v1.onnx` is now published (§ Licences). The emulator is the M3's cores, not the
  tablet's: transcription runs at 0.32 × real time with 4 threads and 1.03 × real time on one M3
  core, so a tablet more than about 5 × slower than the emulator misses the 5-minute gate. M23
  confirms on the tablet (§ Rerunning the bench).
- **Composer: go.** The time gate passes with a tenfold margin, the memory gate with more than a
  twofold one. The letter of "64 greedy tokens match PyTorch" holds for the fp32 file only: no INT8
  variant reproduces PyTorch's greedy tokens on every seed, because greedy decoding here meets
  near-ties (PyTorch's own top-two margin is 0.15 logits where the INT8 file turns). The INT8 file's
  distribution is close: teacher-forced top-1 agreement 98.2 %, loss 0.6191 vs 0.6158 nats/token,
  mean KL 0.0019 nats. M24 samples (top-p, temperature), so this is the measure that matters.
- **Runtime:** `com.microsoft.onnxruntime:onnxruntime-android` pinned at **1.28.0** (confirmed by
  the coordinator, 2026-09-28). The newest 1.x that resolves is 1.30.0, but **1.29.0 and 1.30.0
  send telemetry to Microsoft from the app** (§ ONNX Runtime). 1.27.0, 1.28.0 and 1.30.0 were all
  measured: same speed, same memory, same outputs.

## What exists now

- Release **`models`**: https://github.com/stevenjin20090101-rgb/steven-piano-android/releases/tag/models
  (`--latest=false`; the tag points at `main` 6b9931a). Assets:
  - `transcription-v1.onnx`: 124 511 036 bytes, SHA-256
    `f5db051a0af4a3601c18b3ecf679be3150912d8d535e3a03554c9662c8525383`;
  - `composer-v1.onnx`: 173 193 820 bytes, SHA-256
    `86ddb19c7afce2bab6be13706cb0a0f44cd7a4271c021706d02394c10cbda7b1`;
  - `models.json` (14 175 bytes, identical to `releases/models.json`).

  Each asset was downloaded back through GitHub into an empty folder and its size and SHA-256
  checked against `models.json`. The repository is still private, so these URLs answer 404 to the
  app until it is public (as for `latest.json`).
- `releases/models.json` (the same file): `{"models": [{name, version, file, url, sizeBytes, sha256,
  licence, source, attribution, inputs, outputs}]}` with both models (composer first, then
  transcription); `source` and `attribution` are additions to the brief's fields: `attribution` is
  the one-line credit the app shows (§ Licences). It reaches
  `raw.githubusercontent.com/.../main/releases/models.json` once the branch is merged.
- `tools/studio/`: `requirements.txt` (the pinned environment), `studio_common.py`,
  `make_test_audio.py`, `export_transcription.py`, `verify_transcription.py`, `export_composer.py`,
  `verify_composer.py`, `publish_models.py`, `run_bench.sh`, and the fixtures.
- Fixtures (`tools/studio/fixtures/`, 474 KB):
  - `transcription_window.json` (146 634 bytes) + `transcription_window.wav` (320 044 bytes): one
    licence-clean 10 s window (the Bach clip, samples 160 000–320 000), the INT8 model's seven raw
    outputs and the **84 notes and 1 pedal** the package's `RegressionPostProcessor` makes of that
    window alone. Outputs are sparse only where the post-processor provably never reads (values
    within 3 frames of an onset peak candidate > 0.3, within 5 of an offset candidate > 0.3,
    frame values > 0.1, velocity where the onset output > 0.3; pedal outputs dense); the script
    asserts that the reconstructed arrays give the same notes. Floats are the shortest decimals
    that parse back to the same float32. For `NotePostProcessor` tests, and for an on-device
    check of the model itself (the emulator reproduced these outputs within 0.00087).
  - `composer_seed.json` (4 367 bytes): the vocabulary layout, the tokenised 15 s seed (214
    tokens with the AUTOREGRESS flag), PyTorch's 64 greedy tokens (`expected_continuation`, also
    decoded to events) and the INT8 file's own 64 (`onnx_int8_continuation`; identical on the Mac
    and on the emulator with ORT 1.27, 1.28 and 1.30).
  - `bach_bwv846.mid` (3 141 bytes): the source of the seed and of the rendered Bach clip.
- Commits: `73e6375` (step 1), `33e8d28` (step 2), `9e6661e` (step 3), `482c804` (the bench,
  temporary), `e6af177` (its revert), `07af894` (this document), then the commit that publishes
  the transcription model after the licence decision.

## Transcription — ByteDance high-resolution piano transcription

**Model.** `piano_transcription_inference`'s `Note_pedal` = `Regress_onset_offset_frame_velocity_CRNN`
(notes) + `Regress_pedal_CRNN` (pedal), checkpoint `CRNN_note_F1=0.9677_pedal_F1=0.9186.pth`
(171 966 578 bytes, SHA-256 `c3fa9730…76141`, Zenodo record 4034264). The pedal outputs the brief
lists come from the pedal model, so the export is the pair. Both sub-models load with
`strict=True` (the package's own loader is `strict=False`, which would hide a mismatch).

**Export** (`export_transcription.py`). The two sub-models carry bit-identical log-mel front ends
(torchlibrosa's STFT as two Conv1d with 1025 × 2048 DFT kernels, then a 1025 × 229 mel matrix), so
the export computes the log-mel once and feeds both; outputs are bit-identical to `Note_pedal`'s
(checked before every export). torchlibrosa's front end exports as ordinary ops (Pad reflect,
Conv, Pow, MatMul, Clip, Log), so **the app feeds raw audio and computes no features**.
`torch.onnx.export(..., opset_version=17, dynamo=False)` (the TorchScript exporter: torch 2.14
warns it is deprecated, and it maps `nn.GRU` to ONNX `GRU`), static shapes, then onnxsim (which
also fixes the outputs' declared shapes), then `quantize_dynamic`. Deterministic: the fp32 file's
hash is the same on every run. ONNX IR 8, opset 17.

| File | Bytes | SHA-256 |
|---|---|---|
| `transcription-fp32.onnx` | 154 182 898 | `fcc4bbff7b5bc74432f170e55bba5def1a6e9fa545b0eb9a089b1b621b369982` |
| `transcription-v1.onnx` (INT8, default) | 124 511 036 | `f5db051a0af4a3601c18b3ecf679be3150912d8d535e3a03554c9662c8525383` |
| `transcription-v1-conv.onnx` (INT8 `--quant conv`) | 114 502 921 | `42748d1dc4c3f9aae85ab539680d556afa7e18c2fcdbc7e5f98e1f8202adfe19` |

**Quantisation, and why the file is 124.5 MB, not ~45 MB.** ONNX Runtime's dynamic quantisation
has no GRU kernel, and the 16 GRUs hold 20.8 M of the model's 38.5 M weights (83 MB), so they stay
float whatever is chosen. Measured on the three clips (agreement with PyTorch, see below):

| INT8 on | Agreement (Liszt / Bach / Satie) | Mac s/window | Emulator peak |
|---|---|---|---|
| Conv + MatMul + Gemm, per-tensor (front end float) | 98.5 / 95.0 / 93.0 % — fails | 0.96 | — |
| Conv + MatMul + Gemm, per-channel | 98.6 / 95.2 / 93.1 % — fails | 0.98 | — |
| same, each branch's first conv float (`--quant conv`) | 99.0 / 98.1 / 97.8 % | 0.94–0.96 | 892 192–898 488 kB |
| **fc5 MatMuls + Gemm heads only, per-channel S8 (default)** | **100 / 99.93 / 99.59 %** | 1.41–1.44 | 715 868–744 072 kB |

The default keeps convolutions and GRUs in float: it is as fast as fp32 (on the Mac the
convolutions take 1 099 of the 1 452 ms a window costs, the GRUs 306) and 19 % smaller.
`--quant conv` is 35 % faster (38.5–40.0 s per 3 min on the emulator) at 97.8–99.0 % agreement
and ~0.9 GB peak: the fallback if the tablet misses the time gate. The STFT and mel stay float
in both (8-bit DFT kernels would raise the log-mel's noise floor).

**Verification** (`verify_transcription.py`). Clips, all 16 kHz mono: the package's bundled
example `cut_liszt.mp3` (40 s; Lang Lang's commercial recording, so verification only, never a
fixture), a Bach clip (87.5 s: the figure and harmonies of the Prelude in C major BWV 846, bars
1–24 and a final chord, written note by note by `make_test_audio.py`, humanised and rendered with
TinySoundFont through FreePats' Upright Piano KW SoundFont, CC0), and Satie's Gymnopédie No. 1 (the
Wikimedia Commons recording by Teknopazzo, CC0, first 200 s). Every 10 s window (hop 5 s) runs in
PyTorch (`Note_pedal`) and in ORT; outputs are stitched with the package's `deframe` and
post-processed with its `RegressionPostProcessor`; "agreement" is the F1 of one-to-one matches
with equal pitch and onsets within 50 ms (`mir_eval.transcription.match_notes`, offsets ignored).

| Clip (windows, PyTorch notes) | fp32: max abs diff / agreement | INT8: max abs diff / agreement / velocity Δ (mean, max) |
|---|---|---|
| Liszt (7, 509) | 1.1e-05 / 100 % | 0.037 / 100 % (509/509) / 0.07, 4 |
| Bach (17, 675) | 7.7e-04 / 100 % | 0.227 / 99.93 % (P 100, R 99.85) / 0.08, 3 |
| Satie (39, 604) | 1.9e-04 / 100 % | 0.236 / 99.59 % (P 99.50, R 99.67) / 0.08, 3 |

Pedal events: 25/25, 56/56, 70/70 in both files. Sanity checks of the pipeline itself: PyTorch's
Liszt notes agree with the package's own `resources/cut_liszt.mid` at F1 99.7 % (the audio
decoders differ); on the Bach clip PyTorch finds all 389 written notes (recall 100 %, onsets 5 ms
late on average) but 286 more (precision 57.6 %): ghost re-attacks on sustained notes and
harmonics at true onsets. The model was trained on real pianos; SoundFont audio (and so,
probably, some AI-made audio) gets ghost notes. Real recordings did not show this.

**Emulator** (onnxruntime-android, 4 intra-op threads, NNAPI off, `bench_3min.wav` = the first 180 s
of the Satie recording, 35 windows, stitched in the bench; post-processing not included, a few ms):

| ORT | Settings | Inference / total with load | Peak (VmHWM) | dumpsys max TOTAL RSS / PSS |
|---|---|---|---|---|
| 1.28.0 | patterns off | 55.3 s / 58.0 s; 54.8 s / 57.4 s | 715 868; 734 816 kB | 718 520 / 606 501 kB |
| 1.28.0 | patterns **on** (ORT default) | 55.3 s / 58.0 s | **1 031 508 kB** | 1 033 680 / 921 711 kB |
| 1.27.0 | patterns off | 55.6 s / 58.3 s | 744 072 kB | — |
| 1.30.0 | patterns off | 56.1 / 59.0; 55.0 / 57.7; 54.8 / 57.6 s | 723 932; 722 792; 741 528 kB | 724 424 / 609 750 kB |
| 1.30.0 | patterns off, arena off | 58.0 s / 60.7 s | 705 124 kB | 700 348 / 585 671 kB |
| 1.30.0 | patterns on | 54.0 s / 56.6 s | 1 035 032 kB | 1 037 200 / 921 216 kB |
| 1.28.0 | 2 threads / 1 thread | 95.7 s / 99.2 s; 180.6 s / 186.3 s | 778 772; 743 840 kB | — |
| 1.28.0 | `--quant conv` file | 36.3 s / 38.5 s | 892 192 kB | 894 816 / 782 728 kB |
| 1.30.0 | fp32 file, patterns on | 55.6 s / 58.3 s | 1 069 244 kB | 1 071 412 / 955 498 kB |

Session creation 186–410 ms; median window 1.55–1.60 s; the app's own resident set when the job
starts 187 668–201 548 kB (debug build). ORT's memory-pattern planner reserves one block for the
whole graph and puts the peak at the 1 GB line (1 031 508 kB = 0.98 GiB = 1.06 GB); with patterns
off the arena allocates as it goes: about 300 MiB less, 0–4 % slower. Every run first checked the
fixture window: the emulator's outputs differ from the Mac's by at most 0.00087.

## Composer — Anticipatory Music Transformer `music-small-800k`

**Model.** `stanford-crfm/music-small-800k` at `fa800530…d4f5` (Apache-2.0): GPT-2, 12 layers ×
12 heads × 768, 1 024 positions, vocabulary 55 028, 128.1 M parameters, tied embeddings,
`scale_attn_by_inverse_layer_idx = true`.

**The export pitfall.** transformers ≥ 4.4x loads GPT-2 with SDPA attention by default, and its
SDPA path silently drops `scale_attn_by_inverse_layer_idx`. On the Bach tokens the eager model
scores 0.7159 nats/token (perplexity 2.05) and the default (SDPA) model 5.3326 (207); their logits
differ by up to 182 and agree on 3.7 % of argmaxes. A plain `optimum-cli export onnx --task
text-generation-with-past` loads the default, exports the broken model (682 MB, opset 18), and its
own check passes because it compares against the same SDPA model; measured against the eager
model that export is off by up to 96.7 in the logits. `export_composer.py` loads with
`attn_implementation="eager"` and hands the model to optimum's own exporter
(`onnx_export_from_model`, task `text-generation-with-past`: the graph the CLI builds). Also:
optimum 2.x moved the exporter out of `optimum[exporters]` into `optimum-onnx` 0.1.0, which pins
optimum 2.1.0 and transformers < 4.58 (4.57.6 here).

**Changes after optimum.** (1) The hidden state is sliced to the last position before `lm_head`,
so `logits` is `[1, 1, 55028]`: a 1 000-token prefill would otherwise produce a 220 MB logits
tensor nobody reads, and 42 GMACs of lm_head. (2) `quantize_dynamic`, per-channel S8, on every
MatMul/Gemm with a constant weight (the 48 transformer Gemms and lm_head; the attention's
activation × activation MatMuls stay float: `MatMulConstBOnly`) and on the token embedding's
Gather. The tied embedding is stored twice by the exporter (a Gather table and a transposed
lm_head copy), both quantised.

| File / INT8 variant | Bytes | Greedy 64/64 (5 seeds) | Top-1 (1 000 tokens) | Loss (PyTorch 0.6158) |
|---|---|---|---|---|
| `composer-fp32.onnx` | 682 976 879 | 5 of 5 | 100 % | 0.6158 |
| per-tensor, embedding float | 301 646 868 | 1 of 5 | 95.4 % | 0.6301 |
| per-channel, embedding float | 302 336 979 | 0 of 5 | 98.2 % | 0.6203 |
| per-tensor, embedding INT8 | 172 503 709 | 1 of 5 | 96.0 % | 0.6324 |
| **per-channel, embedding INT8 = `composer-v1.onnx`** | **173 193 820** | **2 of 5** | **98.2 %** | **0.6191** |

fp32 SHA-256 `74b1f848307b93efb94d8d3411674a04ca1a8ddfb86f5f1b08804cc1c6795d57`; ONNX IR 8, opset 18.

**Verification** (`verify_composer.py`). Seeds: 15 s of `fixtures/bach_bwv846.mid` starting at 0,
15, 30, 45 and 60 s, tokenised as the package's `generate()` builds a prompt. Greedy decoding with
the package's own rules (`safe_logits`, `future_logits`, `instr_logits`, then argmax), PyTorch the
package's way (the whole window every step) against ONNX the app's way (prefill, then one token
per call through the KV cache). fp32: all five seeds 64/64, teacher-forced top-1 100 % over the
first 1 000 Bach tokens, loss equal, max log-prob difference 0.0003. INT8: 64/64 on the 45 s and
60 s seeds; the 0 s (fixture), 15 s and 30 s seeds diverge at tokens 45, 21 and 7, each where
PyTorch's own top-two margin is 0.147–0.152 logits; teacher-forced top-1 98.2 %, mean KL 0.0019
nats (max 0.035), largest log-probability change among tokens above p = 0.001: 0.97. On the Android
emulator the fp32 file reproduced PyTorch's 64 tokens exactly and the INT8 file the Mac's INT8 64.
Mac (ORT 1.30, 4 threads): INT8 2.1–4.1 ms per step from an empty to a 1 000-long cache, 118 ms
for a 511-token prefill; fp32 5.1–7.2 ms, 260 ms.

**Emulator** (4 threads, NNAPI off; the fixture's seed, then 3 600 tokens sampled with
temperature 1 and top-p 0.98, piano only; when the next event would not fit in 1 024 positions the
window slides: the last 170 events are kept, their times made relative to the earliest, and
prefilled again after AUTOREGRESS: 6 slides per run):

| ORT | Settings | ms/token (total for 3 600) | Step median / p90 / max | Slide prefill | Peak (VmHWM) |
|---|---|---|---|---|---|
| 1.28.0 | patterns off | 5.87 (21.2 s); 5.76 (20.7 s) | 4.67–4.74 / 6.29–6.35 / 15.9–22.9 ms | 129–132 ms | 616 968; 627 496 kB |
| 1.27.0 | patterns off | 5.73 (20.6 s) | 4.67 / 6.04 / 16.5 ms | 129–136 ms | 626 556 kB |
| 1.30.0 | patterns on | 5.69; 5.80; 5.94 | 4.60–4.81 / 6.06–6.63 ms | 128–136 ms | 632 276–644 580 kB |
| 1.30.0 | patterns off | 6.08; 5.78 | 4.73–4.88 / 6.14–6.61 ms | 130–161 ms | 619 584; 632 488 kB |
| 1.28.0 | 2 threads / 1 thread | 5.92 / 8.17 | 4.94 / 6.94 ms | 221–226 / 412–413 ms | 625 876; 624 952 kB |
| 1.30.0 | fp32 file | 9.72 (35.0 s) | 8.25 ms | 301–321 ms | 1 125 948 kB |

Seed prefill (214 tokens) 66–113 ms (130 ms fp32); session creation 392–615 ms (1 098 ms fp32).
Single-token steps hardly use more than one thread; prefills do. The 3 600 sampled tokens covered
260 s of music here (4.6 notes a second); denser music spends them faster, so a 2-minute piece is
3 600 tokens at about 10 notes a second.

## ONNX Runtime on Android: the version, telemetry, settings, size

**Telemetry.** onnxruntime-android **1.29.0 and 1.30.0** add Microsoft's 1DS telemetry: the AAR
merges a `ContentProvider` (`ai.onnxruntime.TelemetryInitializer`, `initOrder` 100) and the
`INTERNET` and `ACCESS_NETWORK_STATE` permissions; at **every process start**, whether or not a
model is used, it loads `libonnxruntime.so` and creates `ai.onnxruntime.telemetry.HttpClient`, whose
native side (the 1DS C++ SDK, with an offline SQLite queue) collects an ID derived from
`android_id`, the locale, time zone, device class, battery and network state. Measured on the
emulator with 1.30.0: during one bench run the app's UID opened a TLS connection to
`20.42.73.31:443` = `onedscolprdeus21.eastus.cloudapp.azure.com` (the address
`v10.events.data.microsoft.com` resolves to) and sent ~5 KB; the 1DS SDK left an empty
`cache/mat-debug-<pid>.log` per process start. This breaks the app's network policy (BUILD_SPEC
v1.4: named hosts only) and has no place on a school kiosk. **1.27.0 and 1.28.0 contain none of it**
(no provider, no permissions, no 1DS strings in `libonnxruntime.so`, no telemetry classes); with
1.28.0 the same run opened no connection and sent 0 bytes. If a later version is ever needed:
remove the provider in the manifest (`<provider android:name="ai.onnxruntime.TelemetryInitializer"
android:authorities="${applicationId}.onnxruntime_telemetry_initializer" tools:node="remove" />`)
and call `OrtEnvironment.getEnvironment().setTelemetry(false)`; verified with 1.30.0: no
connection, 0 bytes, no 1DS log. (The library also carries an `ORT_DISABLE_TELEMETRY` switch, an
environment variable; not tested here.) Audit delta 2 should assert that the merged manifest has
no `TelemetryInitializer`. The spike's own 1.30.0 runs did send these events from the emulator:
about 18 process starts (one 1DS log each); the app's UID sent 96 KB over those runs
(NetworkStats), consistent with the ~5 KB per start seen on the socket.

**Session options for the app** (both models): CPU execution provider (NNAPI off),
`setIntraOpNumThreads(4)`, `setInterOpNumThreads(1)`, `ExecutionMode.SEQUENTIAL`,
`OptLevel.ALL_OPT`, **`setMemoryPatternOptimization(false)`**, arena on. Load from the file path.

**APK size, arm64-v8a only** (debug builds, clean; the release build needs the keystore, which
this run does not touch; R8 cannot shrink the native library, which is nearly all of it):
1.28.0 adds **28 741 188 bytes** (16 132 483 → 44 873 671): `libonnxruntime.so` 28 637 280 and
`libonnxruntime4j_jni.so` 111 648 bytes, stored uncompressed (AGP's default for minSdk ≥ 23), and
nothing measurable besides (the Java API's dex is within the noise). With
`packaging { jniLibs { useLegacyPackaging = true } }` the library is stored deflated, 10 573 095
bytes to download, and extracted at install. For comparison:
1.27.0's library 27 985 944 bytes (10 255 873 deflated); 1.30.0's 32 990 480 (12 409 002; measured
debug delta 33 103 442). Release 1.6.2 is 2 837 804 bytes, so about 31.6 MB with ORT 1.28.0 (about
13.5 MB with legacy packaging), inside the updater's 50 MB cap either way; README's "2 MB" line
and M23's "APK ≈ 20 MB" change. On x86_64 the library is absent (`abiFilters`), so Studio hides.

## The contract for M23 and M24

### Transcription (`transcription-v1.onnx`)

- **Input** `audio` float32 `[1, 160000]`: 10.000 s of mono 16 kHz audio, samples in [−1, 1)
  (int16 / 32768). The log-mel is inside the model: STFT n_fft 2048, hop 160, Hann window, centred
  with reflect padding, power 2; 229 mel bins, 30 Hz to 8 kHz (librosa's Slaney mel, `htk=False`);
  10·log10(max(x, 1e−10)), no top_db; then each branch's own batch norm. The app feeds samples only.
- **Windows:** zero-pad the audio to a whole number of 160 000-sample windows; a window starts
  every 80 000 samples, so N = 2·(padded / 160 000) − 1 windows. Run them one at a time.
- **Outputs**, float32 probabilities in [0, 1], frame f = f × 10 ms from the window's start, key
  k = MIDI note 21 + k: `reg_onset_output` `[1, 1001, 88]`, `reg_offset_output` `[1, 1001, 88]`,
  `frame_output` `[1, 1001, 88]`, `velocity_output` `[1, 1001, 88]`, `reg_pedal_onset_output`
  `[1, 1001, 1]`, `reg_pedal_offset_output` `[1, 1001, 1]`, `pedal_frame_output` `[1, 1001, 1]`.
- **Stitching** (the package's `deframe`): drop frame 1000 of every window; keep frames 0–749 of
  the first, 250–749 of the middle ones, 250–999 of the last; concatenate (one window: all 1001).
  That is 500·N + 500 frames = 100 frames per second of padded audio.
- **Post-processing** (`NotePostProcessor`, a port of `RegressionPostProcessor` + `piano_vad`):
  onset peaks where `reg_onset > 0.3` and the two frames each side fall away monotonically;
  offset peaks likewise at 0.3 with four frames; shift = (x[n+1] − x[n−1]) / (x[n] − x[n±1]) / 2
  (the larger neighbour's side); a note runs from an onset to the offset peak or to where
  `frame ≤ 0.1`, whichever the package's rule picks, is cut at 600 frames, and ends at the next
  onset of the same key (`piano_vad.note_detection_with_onset_offset_regress` has the exact
  rule); times = (frame + shift) / 100 s; velocity = `int(velocity_output[onset] × 128)`, which can
  reach 128, so clamp to 1–127. Pedal: it goes down where `pedal_frame ≥ 0.5` and rising (no shift),
  and comes up at the next offset peak (`reg_pedal_offset > 0.2`, four frames), or where
  `pedal_frame` fell to ≤ 0.5 if no offset peak comes within 10 frames of that;
  `reg_pedal_onset_output` is not used. The port must reproduce
  `fixtures/transcription_window.json`'s `expected_notes` and `expected_pedals` from its `outputs`
  (times within 1e−4 s; velocities exact, ±1 at most where float rounding meets `int()`).
- **Session:** the options above. Measured cost per job: ≤ 589 800 kB (576 MiB) above the app's
  own resident set.

### Composer (`composer-v1.onnx`)

- **Inputs:** `input_ids` int64 `[1, n]` (the new tokens: the whole seed first, then one);
  `attention_mask` int64 `[1, p + n]`, all ones; `position_ids` int64 `[1, n]` = p … p + n − 1;
  `past_key_values.{0..11}.key` and `.value` float32 `[1, 12, p, 64]` (p = 0 on the first call:
  empty tensors). 27 inputs in all; p + n ≤ 1 024.
- **Outputs:** `logits` float32 `[1, 1, 55028]` (the next token, for the last input position
  only); `present.{0..11}.key` / `.value` float32 `[1, 12, p + n, 64]`, fed back as the next call's
  past (in ORT Java the result's `OnnxTensor`s go straight back in; close the previous result
  after the next run).
- **Vocabulary** (the `anticipation` package at `af37397`; `fixtures/composer_seed.json`
  holds the constants): events are triples (time, duration, note). Time = TIME_OFFSET 0 + 10 ms
  ticks since the context's origin (0–9 999); duration = DUR_OFFSET 10 000 + 10 ms ticks (0–999,
  longer notes capped at 999); note = NOTE_OFFSET 11 000 + 128 × instrument + pitch (piano =
  instrument 0: 11 000–11 127; drums = 128); REST 27 512, the note of a padding event (time,
  10 000, REST) that `ops.pad` inserts wherever a second passes without an event. The control
  block (anticipated time 27 513–37 512, duration 37 513–38 512, note 38 513–55 024) and the
  specials (SEPARATOR 55 025, AUTOREGRESS 55 026, ANTICIPATE 55 027) are never generated; a
  sequence without controls starts with AUTOREGRESS. MIDI → events (`midi_to_compound`): onsets
  rounded to 10 ms, durations to 10 ms, channel 9 → instrument 128, program changes per channel,
  velocities dropped (the model has none; the app shapes them).
- **Seed:** `midi_to_events` → `clip(0, 1500 ticks, clip_duration=False)` → `pad(..., 1500)`, after
  AUTOREGRESS; the current time is the seed's last event time (1 499 ticks in the fixture).
- **Masks per position** (`sample.py`; the position after a complete triple is a time): never a
  control or special token; time slot: time tokens only, none before the current time (relative
  to the context's origin); duration slot: duration tokens; note slot: note tokens, and the app
  restricts it to 11 000–11 127 (piano); `safe_logits` leaves REST open in every slot. Then
  temperature and top-p (the bench: 1.0 and 0.98).
- **Context:** 1 024 positions. The package re-runs the whole window every token with times made
  relative to the window's first event (`add_token`: the last 1 017 tokens); with the KV cache the
  app instead slides when the next event would not fit: keep the last K events (the bench: 170 =
  510 tokens), subtract their earliest time, prefill AUTOREGRESS + them (p = 0), carry on. With
  K = 170 that is 6 slides per 3 600 tokens at 128–161 ms each on the emulator.
- **Fixture:** feeding `input_tokens` and decoding greedily with the package's masks gives
  `expected_continuation` in PyTorch and fp32 ONNX, and `onnx_int8_continuation` with this INT8
  file (on the Mac and on Android alike). Tests of the ONNX path should pin the INT8 file's own
  tokens; tests of the tokenizer and masks can pin PyTorch's.
- **Session:** the options above. Measured cost per job: ≤ 452 812 kB (442 MiB) above the app's
  own resident set.

## Memory gates for the app (from the measurements)

Measured job cost = VmHWM − the app's resident set when the job started, on the emulator:
transcription 515 460–546 596 kB with 4 threads (6 runs), 554 952 kB with 1 thread, 589 800 kB
with 2; composer 417 024–452 812 kB (8 runs); the fp32 composer would be 935 216 kB. The app itself
was at 187 668–201 548 kB (debug build, `App.onCreate` done).

- **Per job** (`ActivityManager.MemoryInfo` when the job starts): transcription only if
  `availMem − threshold ≥ 900 MiB` (943 718 400 bytes: the worst measured 576 MiB + 50 %),
  composing only if ≥ 700 MiB (734 003 200 bytes: 442 MiB + 50 %), and never when `lowMemory`.
  Otherwise the job waits with a line saying so.
- **Offering Studio at all:** item 13's "≥ 6 GB" placeholder is not needed. The whole process
  peaks at 0.68–0.74 GiB during a transcription and 0.59–0.61 GiB while composing, so
  `totalMem ≥ 2.5 GiB` with the per-job check above is enough (devices sold with 3 GB pass:
  `totalMem` leaves out what the kernel and firmware reserve); below that Studio hides. Two jobs
  never run at once (M23's one-at-a-time rule).
- Storage: 124.5 MB + 173.2 MB of models, plus the plan's download margin.

## Licences

| Asset | Licence | Where recorded |
|---|---|---|
| Transcription weights (Zenodo 10.5281/zenodo.4034264, Qiuqiang Kong et al., ByteDance) | **CC BY 4.0** (`CC-BY-4.0`); accepted by the coordinator 2026-09-28 | `models.json` (`licence`, `source`, `attribution`); the app's About and AUTHORS (M23); here |
| `bytedance/piano_transcription` code | Apache-2.0 (its README; the repo has no LICENSE file) | here |
| `piano_transcription_inference` 0.0.6 | MIT (setup.py classifier; no LICENSE file) | here |
| torchlibrosa 0.1.0 | MIT | here |
| `stanford-crfm/music-small-800k` | Apache-2.0 (model card) | `models.json`, here |
| `anticipation` (jthickstun) | Apache-2.0 | here |
| onnxruntime-android | MIT | here |
| Upright Piano KW SoundFont (FreePats, 2022-02-21) | CC0-1.0 | `make_test_audio.py` |
| Gymnopédie No. 1 recording (Wikimedia Commons, Teknopazzo) | CC0-1.0 | `make_test_audio.py` |
| `cut_liszt.mp3` (Lang Lang, Liebestraum) | commercial recording | verification only; never committed |

Training data, for the record: the transcription model was trained on MAESTRO (CC BY-NC-SA 4.0),
the composer on the Lakh MIDI Dataset (CC-BY 4.0); the weights' licences are as published. The
fixtures contain nothing from the Lang Lang recording.

**Attribution lines.** Each `models.json` entry's `attribution` is the one-line credit the app
shows in About, as it credits Wikipedia and MAESTRO:

- Piano transcription model — Kong et al., ByteDance, CC BY 4.0, Zenodo 4034264
- Anticipatory Music Transformer — Thickstun et al., Stanford CRFM, Apache 2.0, Hugging Face stanford-crfm/music-small-800k

CC BY 4.0 (§ 3(a)) also asks that the credit say the material was changed and link the licence,
and Apache 2.0 (§ 4) that changes be stated and the licence be given. So the AUTHORS entries, in
the form of its Wikipedia and Haze entries, would read:

> Piano transcription model (downloaded by the app on demand, never bundled): "High-resolution
> Piano Transcription with Pedals by Regressing Onsets and Offsets Times", trained model by
> Qiuqiang Kong, Bochen Li, Xuchen Song, Yuan Wan and Yuxuan Wang (ByteDance),
> https://doi.org/10.5281/zenodo.4034264. Licensed under the Creative Commons Attribution 4.0
> International licence (CC BY 4.0, https://creativecommons.org/licenses/by/4.0/). Converted to
> ONNX and quantised to INT8 for Steven Piano (tools/studio/). The model keeps its own licence;
> the MIT licence of this project does not apply to it.
>
> Composing model (downloaded by the app on demand, never bundled): the Anticipatory Music
> Transformer music-small-800k by John Thickstun, David Hall, Chris Donahue and Percy Liang
> (Stanford CRFM), https://huggingface.co/stanford-crfm/music-small-800k. Licensed under the
> Apache License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0). Exported to ONNX with
> a KV cache and quantised to INT8 for Steven Piano (tools/studio/). The model keeps its own
> licence; the MIT licence of this project does not apply to it.

`python tools/studio/publish_models.py --work DIR --upload` rebuilds `models.json` from the files
and replaces the release's assets (both models by default).

## Reproducing the Mac side

```
/opt/homebrew/bin/python3.13 -m venv ~/.venvs/studio && source ~/.venvs/studio/bin/activate
pip install -r tools/studio/requirements.txt
pip install --no-deps tinysoundfont==0.3.7 \
  "anticipation @ git+https://github.com/jthickstun/anticipation.git@af37397922665a0fb8d474d7988b0f3755a38d45"
export STUDIO_WORK=~/studio-work          # downloads, exports, audio, logs; never the repo
cd tools/studio
python make_test_audio.py                 # the three clips + bench_3min.wav; fixtures/bach_bwv846.mid
python export_transcription.py            # fp32 + transcription-v1.onnx  (--quant conv: the faster file)
python verify_transcription.py            # agreement tables; fixtures/transcription_window.{json,wav}
python export_composer.py                 # fp32 + composer-v1.onnx
python verify_composer.py                 # greedy, teacher-forced; fixtures/composer_seed.json
python publish_models.py [--models composer,transcription] [--upload]
```

`make_test_audio.py` downloads the SoundFont and the two recordings, `export_transcription.py` the
checkpoint (all sha256-checked); Hugging Face downloads go to `$STUDIO_WORK/hf`. About 15 minutes on
the M3 Max, plus about 1 GB of downloads.

## Rerunning the bench (the tablet, M23)

The bench is commit `482c804` (reverted in `e6af177`): onnxruntime-android 1.28.0 as a
`debugImplementation`, `StudioBench.kt` (what was timed here), and two ways in sharing one runner:
an activity (`adb shell am start -n dev.stevenjin.stevenpiano/.studio.StudioBenchActivity --es bench
transcription ...`) and the debug property `debug.stevenpiano.studiobench`, read when the app's
process starts (`adb shell setprop debug.stevenpiano.studiobench "transcription label=tablet-tr"`,
then open the app; clear it with `adb shell "setprop debug.stevenpiano.studiobench ''"`; a value
holds 91 bytes). Results: `filesDir/studio/bench-<label>.json` and logcat tag `StudioBench`.

1. `git cherry-pick 482c804`. Better, M23 points `StudioBenchRunner` at its real `Transcriber` and
   `Sampler` so the tablet times the shipping code path, and keeps it debug-only.
2. The school tablet runs the release-signed app as device owner, and a debug build cannot be
   installed over it without uninstalling it (losing the library and the device-owner role).
   Give the bench build `applicationIdSuffix ".bench"` so it installs beside the real app, or use
   another unit of the same model.
3. `tools/studio/run_bench.sh -s <serial> --push $STUDIO_WORK` (the models, the 3-minute WAV and
   the fixtures into `filesDir`), then `tools/studio/run_bench.sh -s <serial> tablet-tr
   bench=transcription` and `... tablet-comp bench=composer` (fresh process each; `dumpsys meminfo`
   polled every 2 s; `NOPOLL=1` for timing only).
4. Compare with the tables above: the gates are ≤ 300 s for `total_ms_including_load` and ≤ 60 for
   `ms_per_token`; `fixture_check` must show `max_abs_diff_vs_mac` ≤ 0.001 and `same_as_mac_int8`.

## Deviations from the brief, and why

- **The transcription file was held back at first** (CC BY 4.0 weights; the brief allowed
  Apache-2.0/MIT/CC0 only, and the plan's "Apache-2.0" was the code's licence). After the
  coordinator accepted CC BY 4.0 it was published beside the composer; `releases/models.json`
  lists both, each with `licence`, `source` and `attribution`.
- **Exported as `Note_pedal` with one shared front end**, not `Regress_onset_offset_frame_velocity_CRNN`
  alone: the pedal outputs the brief lists live in the pedal model. Bit-identical outputs.
- **INT8 on the transcription model's MatMul/Gemm only.** Convolutions in INT8 failed the 97 % gate
  (93.0–98.5 %) or, with each branch's first convolution kept float, passed narrowly (97.8 %) at
  ~0.9 GB peak. GRUs cannot be dynamically quantised. 124.5 MB instead of the plan's ~45 MB.
- **Test audio:** TinySoundFont (MIT, pip) instead of FluidSynth (a Homebrew install), with a CC0
  SoundFont; the second clip is a CC0 recording, not a second rendering.
- **The composer is not exported with the plain `optimum-cli` command**: it exports the SDPA model
  and passes its own check. The same exporter through its Python API with eager attention.
  `optimum[exporters]` no longer carries the exporter (optimum 2.x); `optimum-onnx` 0.1.0 does.
- **The composer's logits cover the last position only**, and its INT8 file quantises the
  embedding too (173 MB, not ~130 MB).
- **The INT8 composer does not reproduce PyTorch's 64 greedy tokens on 3 of 5 seeds** (fp32 does
  on all 5); reported with the distribution measures instead of retuned to pass one seed.
- **onnxruntime-android 1.28.0, not the newest 1.30.0** (telemetry; the coordinator kept the pin
  at 1.28.0); the plan's 1.27.0 is equally clean and measured the same. Memory patterns off in the session: ORT's default puts the
  transcription at 1.03 GB.
- **The emulator ran with 4 GB** (`-memory 4096`, a launch flag; the AVD still says 2 GB).
- **The bench adds a property trigger and fixture checks**, and debug builds carried
  `abiFilters "arm64-v8a"` while it existed. Its Mac-side driver stays: `tools/studio/run_bench.sh`.
- **The venv** is `~/.venvs/studio` on Homebrew's Python 3.13 (the brief's path); the ORT and
  `anticipation` pins are in `requirements.txt`.

## Not done here

- Timings on the school tablet (the emulator is not its CPU): M23 (§ Rerunning the bench).
- A release APK's size: the release build requires the keystore, which this run does not touch;
  the native library is the difference and is measured exactly.
- `NotePostProcessor`, `AmtTokenizer`, `Sampler` and the model downloads are M23/M24's; the
  fixtures above are their tests.
- Provenance signing of the new files (the integrator's).
