# ============================================================================
#  Steven Piano - Android player for the self-playing acoustic piano
#  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
#  Original author & creator: Steven Jin.
#  Licensed under the MIT License (see LICENSE). This copyright and attribution
#  notice MUST be preserved in all copies or substantial portions of the work.
#  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
# ============================================================================

"""Shared helpers for the Studio spike scripts (M22).

Everything large (downloads, exports, rendered audio, logs) lives in a work folder outside the
repository: `--work DIR`, or the STUDIO_WORK environment variable, or ./studio-work. Only the small
fixtures under tools/studio/fixtures/ are committed.
"""

import hashlib
import json
import os
import subprocess
import sys
import urllib.request

# ---- transcription (ByteDance high-resolution piano transcription, Note_pedal) -----------------

SAMPLE_RATE = 16000            # the model's input rate (Hz), mono
SEGMENT_SAMPLES = 160000       # one window = 10 s
HOP_SAMPLES = 80000            # the app's hop between windows = 5 s (the package's enframe hop)
FRAMES_PER_SECOND = 100        # 10 ms frames; 1001 frames per window (centre=True adds one)
FRAMES_PER_SEGMENT = 1001
CLASSES = 88                   # MIDI 21 (A0) .. 108 (C8)
BEGIN_NOTE = 21
VELOCITY_SCALE = 128
OUTPUT_NAMES = [
    "reg_onset_output",
    "reg_offset_output",
    "frame_output",
    "velocity_output",
    "reg_pedal_onset_output",
    "reg_pedal_offset_output",
    "pedal_frame_output",
]
# The package's own post-processing thresholds (PianoTranscription.__init__).
ONSET_THRESHOLD = 0.3
OFFSET_THRESHOLD = 0.3
FRAME_THRESHOLD = 0.1
PEDAL_OFFSET_THRESHOLD = 0.2

CHECKPOINT_NAME = "CRNN_note_F1=0.9677_pedal_F1=0.9186.pth"
CHECKPOINT_URL = ("https://zenodo.org/record/4034264/files/"
                  "CRNN_note_F1%3D0.9677_pedal_F1%3D0.9186.pth?download=1")
CHECKPOINT_SHA256 = "c3fa9730725bf4a762f1c14bc80cd5986eacda01b026f5a4a2525cd607876141"

# ---- composer (Anticipatory Music Transformer) ---------------------------------------------------

COMPOSER_REPO = "stanford-crfm/music-small-800k"
COMPOSER_REVISION = "fa800530aa1126dd6b58b38f3bdb8fcec9c9d4f5"
ANTICIPATION_COMMIT = "af37397922665a0fb8d474d7988b0f3755a38d45"

HERE = os.path.dirname(os.path.abspath(__file__))
FIXTURES = os.path.join(HERE, "fixtures")


def work_dir(arg=None):
    path = arg or os.environ.get("STUDIO_WORK") or os.path.join(os.getcwd(), "studio-work")
    path = os.path.abspath(os.path.expanduser(path))
    for sub in ("downloads", "exports", "audio", "logs", "hf"):
        os.makedirs(os.path.join(path, sub), exist_ok=True)
    # keep Hugging Face downloads inside the work folder too
    os.environ.setdefault("HF_HOME", os.path.join(path, "hf"))
    return path


def sha256_file(path, chunk=1 << 20):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while True:
            b = f.read(chunk)
            if not b:
                break
            h.update(b)
    return h.hexdigest()


def download(url, dest, sha256=None, user_agent="StevenPianoStudioSpike/1.0"):
    if not os.path.exists(dest):
        print(f"downloading {url}\n  -> {dest}", file=sys.stderr)
        req = urllib.request.Request(url, headers={"User-Agent": user_agent})
        tmp = dest + ".part"
        with urllib.request.urlopen(req) as r, open(tmp, "wb") as f:
            while True:
                b = r.read(1 << 20)
                if not b:
                    break
                f.write(b)
        os.replace(tmp, dest)
    if sha256:
        got = sha256_file(dest)
        if got != sha256:
            raise SystemExit(f"sha256 mismatch for {dest}: {got} != {sha256}")
    return dest


def checkpoint_path(work):
    return download(CHECKPOINT_URL, os.path.join(work, "downloads", CHECKPOINT_NAME), CHECKPOINT_SHA256)


def load_wav_16k(path):
    """A 16 kHz mono float32 array from any file soundfile reads (resampled with soxr if needed)."""
    import numpy as np
    import soundfile as sf
    y, sr = sf.read(path, dtype="float32", always_2d=True)
    y = y.mean(axis=1)
    if sr != SAMPLE_RATE:
        import librosa
        y = librosa.resample(y, orig_sr=sr, target_sr=SAMPLE_RATE)
    return np.ascontiguousarray(y, dtype=np.float32)


def enframe(audio):
    """The app's windows: pad to whole 10 s windows, then windows every 5 s (package's enframe)."""
    import numpy as np
    n = len(audio)
    padded_len = int(np.ceil(n / SEGMENT_SAMPLES)) * SEGMENT_SAMPLES
    x = np.zeros(padded_len, dtype=np.float32)
    x[:n] = audio
    windows = []
    p = 0
    while p + SEGMENT_SAMPLES <= padded_len:
        windows.append(x[p:p + SEGMENT_SAMPLES])
        p += HOP_SAMPLES
    return np.stack(windows), n


def deframe(x):
    """The package's deframe: drop the extra frame, keep the centre halves (first/last keep ends)."""
    import numpy as np
    if x.shape[0] == 1:
        return x[0]
    x = x[:, 0:-1, :]
    n, seg, _ = x.shape
    y = [x[0, 0:int(seg * 0.75)]]
    for i in range(1, n - 1):
        y.append(x[i, int(seg * 0.25):int(seg * 0.75)])
    y.append(x[-1, int(seg * 0.25):])
    return np.concatenate(y, axis=0)


def git_info():
    try:
        return subprocess.check_output(["git", "-C", HERE, "rev-parse", "--short", "HEAD"], text=True).strip()
    except Exception:
        return None


def write_json(path, obj, indent=None):
    with open(path, "w") as f:
        json.dump(obj, f, indent=indent, separators=(",", ":") if indent is None else None)
        f.write("\n")
