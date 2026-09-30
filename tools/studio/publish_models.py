#!/usr/bin/env python3
# ============================================================================
#  Steven Piano - Android player for the self-playing acoustic piano
#  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
#  Original author & creator: Steven Jin.
#  Licensed under the MIT License (see LICENSE). This copyright and attribution
#  notice MUST be preserved in all copies or substantial portions of the work.
#  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
# ============================================================================

"""Write models.json for the Studio models and (with --upload) publish the GitHub release `models`.

models.json = {"models": [{"name", "version", "file", "url", "sizeBytes", "sha256", "licence",
"source", "attribution", "inputs", "outputs"}], "sounds": [...]}: sizes and hashes computed here from
the files in <work>/exports, the inputs/outputs read from the ONNX graphs themselves. It is written to
<work>/exports/models.json and to releases/models.json in this repository (the app reads it from
raw.githubusercontent.com like releases/latest.json), and uploaded beside the model files.

Sounds (v1.8 — M25) are SoundFonts the app plays with: `upright-piano-kw` is FreePats' Upright Piano KW
(2022-02-21, CC0 1.0), the .sf2 exactly as FreePats publishes it, fetched here from FreePats and checked
against the archive's and the file's SHA-256 (--models upright-piano-kw fetches it into <work>/exports as
upright-piano-kw-v1.sf2). They go in the list's "sounds" array, which 1.7 never reads, so a sound never
makes 1.7 refuse the list; their entries carry the SoundFont's name and sample rate instead of inputs and
outputs. Entries of the list not named with --models are kept as releases/models.json has them, so one
file can be published without the others at hand.

The release is tagged `models` (never a version tag, so the updater's APK rules are untouched) and
created with --latest=false, so "Latest" stays on the newest app release. Assets are served at
https://github.com/<repo>/releases/download/models/<file>, the updater's allowed shape.

  python publish_models.py --work DIR                       # both models, no upload
  python publish_models.py --work DIR --upload              # ... and publish (assets replaced)
  python publish_models.py --work DIR --models composer     # one model only (the others kept as listed)
  python publish_models.py --work DIR --models upright-piano-kw --upload   # the tablet's piano sound
The transcription model's weights are CC BY 4.0 (accepted 2026-09-28 with attribution in the app's
About and AUTHORS; see docs/STUDIO_SPIKE.md); the composer's are Apache-2.0. `attribution` is the
one-line credit the app shows.
"""

import argparse
import json
import os
import subprocess
import sys

import struct

import studio_common as sc

REPO = "stevenjin20090101-rgb/steven-piano-android"
TAG = "models"
ROOT = os.path.dirname(os.path.dirname(sc.HERE))

CATALOGUE = {
    "transcription": {
        "version": 1, "file": "transcription-v1.onnx", "licence": "CC-BY-4.0",
        "source": "https://zenodo.org/record/4034264 (" + sc.CHECKPOINT_NAME + ")",
        "attribution": "Piano transcription model — Kong et al., ByteDance, CC BY 4.0, Zenodo 4034264",
    },
    "composer": {
        "version": 1, "file": "composer-v1.onnx", "licence": "Apache-2.0",
        "source": "https://huggingface.co/" + sc.COMPOSER_REPO + " (revision " + sc.COMPOSER_REVISION + ")",
        "attribution": ("Anticipatory Music Transformer — Thickstun et al., Stanford CRFM, Apache 2.0, "
                        "Hugging Face stanford-crfm/music-small-800k"),
    },
    "upright-piano-kw": {
        "kind": "sound", "version": 1, "file": "upright-piano-kw-v1.sf2", "licence": "CC0-1.0",
        "source": ("https://freepats.zenvoid.org/Piano/UprightPianoKW/UprightPianoKW-SF2-20220221.7z "
                   "(UprightPianoKW-SF2-20220221/UprightPianoKW-20220221.sf2, unmodified; "
                   "https://freepats.zenvoid.org/Piano/acoustic-grand-piano.html#UprightKW)"),
        "attribution": "Upright Piano KW — FreePats (Gonzalo and Roberto, 2022-02-21), CC0 1.0, freepats.zenvoid.org",
    },
}

# FreePats' archive of the Upright Piano KW SoundFont, and the .sf2 inside it (both SHA-256).
SOUND_ARCHIVE_URL = "https://freepats.zenvoid.org/Piano/UprightPianoKW/UprightPianoKW-SF2-20220221.7z"
SOUND_ARCHIVE_SHA256 = "17c084c6e4205233dc49b34e4bc44a9b2d7c7a2c02b04729ecda77079b07c826"
SOUND_MEMBER = "UprightPianoKW-SF2-20220221/UprightPianoKW-20220221.sf2"
SOUND_SHA256 = "d9f5157720963671906727ca2e12b3293fd822c831bb0de477dd1c5f3ad37108"


def list_key(name):
    return "sounds" if CATALOGUE[name].get("kind") == "sound" else "models"


def fetch_sound(work, dest):
    """FreePats' archive, checked, then its .sf2, checked, copied to dest unmodified."""
    archive = sc.download(SOUND_ARCHIVE_URL, os.path.join(work, "downloads", os.path.basename(SOUND_ARCHIVE_URL)),
                          SOUND_ARCHIVE_SHA256)
    out = os.path.join(work, "downloads", "sf2")
    member = os.path.join(out, SOUND_MEMBER)
    if not os.path.exists(member):
        os.makedirs(out, exist_ok=True)
        subprocess.check_call(["tar", "-xf", archive, "-C", out, SOUND_MEMBER])  # bsdtar reads 7z
    if sc.sha256_file(member) != SOUND_SHA256:
        raise SystemExit(f"{member} is not the SoundFont FreePats published")
    with open(member, "rb") as src, open(dest, "wb") as dst:
        dst.write(src.read())
    return dest


def sf2_info(path):
    """The SoundFont's name (INAM) and its samples' rates, read from its RIFF chunks."""
    data = open(path, "rb").read()
    if data[:4] != b"RIFF" or data[8:12] != b"sfbk":
        raise SystemExit(f"{path} is not a SoundFont")
    name, rates, at = None, set(), 12
    while at + 8 <= len(data):
        cid, size = data[at:at + 4], struct.unpack("<I", data[at + 4:at + 8])[0]
        if cid == b"LIST":
            sub = at + 12
            while sub + 8 <= at + 8 + size:
                sid, ssize = data[sub:sub + 4], struct.unpack("<I", data[sub + 4:sub + 8])[0]
                body = data[sub + 8:sub + 8 + ssize]
                if sid == b"INAM":
                    name = body.split(b"\0")[0].decode("ascii", "replace")
                if sid == b"shdr":
                    for i in range(ssize // 46 - 1):
                        rates.add(struct.unpack("<I", body[i * 46 + 36:i * 46 + 40])[0])
                sub += 8 + ssize + (ssize & 1)
        at += 8 + size + (size & 1)
    return {"format": "SoundFont 2", "soundfont": name, "sampleRates": sorted(rates)}


def io(model_path):
    import onnx
    m = onnx.load(model_path, load_external_data=False)
    t = lambda v: {"name": v.name,
                   "type": onnx.helper.tensor_dtype_to_np_dtype(v.type.tensor_type.elem_type).name,
                   "shape": [d.dim_param or d.dim_value for d in v.type.tensor_type.shape.dim]}
    return [t(i) for i in m.graph.input], [t(o) for o in m.graph.output]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--work")
    ap.add_argument("--models", default="composer,transcription", help="comma list from: " + ", ".join(CATALOGUE))
    ap.add_argument("--upload", action="store_true")
    args = ap.parse_args()
    work = sc.work_dir(args.work)
    exports = os.path.join(work, "exports")
    # What releases/models.json lists now: kept for every file not named here.
    listed = os.path.join(ROOT, "releases", "models.json")
    lists = {"models": [], "sounds": []}
    if os.path.exists(listed):
        with open(listed, encoding="utf-8") as f:
            old = json.load(f)
        for key in lists:
            lists[key] = list(old.get(key, []))
    files = []
    for name in [m.strip() for m in args.models.split(",") if m.strip()]:
        c = CATALOGUE[name]
        path = os.path.join(exports, c["file"])
        entry = {"name": name, "version": c["version"], "file": c["file"],
                 "url": f"https://github.com/{REPO}/releases/download/{TAG}/{c['file']}"}
        if c.get("kind") == "sound":
            if not os.path.exists(path):
                fetch_sound(work, path)
            if sc.sha256_file(path) != SOUND_SHA256:
                raise SystemExit(f"{path} is not the SoundFont FreePats published")
            extra = sf2_info(path)
        else:
            inputs, outputs = io(path)
            extra = {"inputs": inputs, "outputs": outputs}
        entry.update({"sizeBytes": os.path.getsize(path), "sha256": sc.sha256_file(path),
                      "licence": c["licence"], "source": c["source"], "attribution": c["attribution"]})
        entry.update(extra)
        key = list_key(name)
        at = next((i for i, e in enumerate(lists[key]) if e.get("name") == name), None)
        if at is None:
            lists[key].append(entry)
        else:
            lists[key][at] = entry
        files.append(path)
        if os.path.getsize(path) >= 2 * 1024 ** 3:
            raise SystemExit(f"{c['file']} is over GitHub's 2 GB asset limit: split it")
    manifest = {"models": lists["models"]}
    if lists["sounds"]:
        manifest["sounds"] = lists["sounds"]
    text = json.dumps(manifest, indent=2, ensure_ascii=False) + "\n"
    for dest in (os.path.join(exports, "models.json"), os.path.join(ROOT, "releases", "models.json")):
        with open(dest, "w", encoding="utf-8") as f:
            f.write(text)
        print("wrote", dest)
    if not args.upload:
        return
    assets = files + [os.path.join(exports, "models.json")]
    exists = subprocess.run(["gh", "release", "view", TAG, "--repo", REPO], capture_output=True).returncode == 0
    if exists:
        subprocess.check_call(["gh", "release", "upload", TAG, "--repo", REPO, "--clobber"] + assets)
    else:
        notes = ("Studio models for Steven Piano, downloaded by the app on demand (never bundled in the APK). "
                 "models.json lists each file with its size, SHA-256, licence and ONNX inputs and outputs; "
                 "the same manifest is releases/models.json in the repository. Built by tools/studio/ "
                 "(M22 spike).")
        subprocess.check_call(["gh", "release", "create", TAG, "--repo", REPO, "--title", "Studio models",
                               "--notes", notes, "--target", "main", "--latest=false"] + assets)
    # read the assets back through GitHub and compare
    check = os.path.join(work, "downloads", "release-check")
    os.makedirs(check, exist_ok=True)
    for a in assets:
        dst = os.path.join(check, os.path.basename(a))
        if os.path.exists(dst):
            os.remove(dst)
        subprocess.check_call(["gh", "release", "download", TAG, "--repo", REPO, "--pattern", os.path.basename(a),
                               "--dir", check])
        ok = sc.sha256_file(dst) == sc.sha256_file(a)
        print(os.path.basename(a), "round trip", "OK" if ok else "MISMATCH")
        if not ok:
            sys.exit(1)


if __name__ == "__main__":
    main()
