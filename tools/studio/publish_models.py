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
"source", "attribution", "inputs", "outputs"}]}: sizes and hashes computed here from the files in
<work>/exports, the inputs/outputs read from the ONNX graphs themselves. It is written to
<work>/exports/models.json and to releases/models.json in this repository (the app reads it from
raw.githubusercontent.com like releases/latest.json), and uploaded beside the model files.

The release is tagged `models` (never a version tag, so the updater's APK rules are untouched) and
created with --latest=false, so "Latest" stays on the newest app release. Assets are served at
https://github.com/<repo>/releases/download/models/<file>, the updater's allowed shape.

  python publish_models.py --work DIR                       # both models, no upload
  python publish_models.py --work DIR --upload              # ... and publish (assets replaced)
  python publish_models.py --work DIR --models composer     # one model only
The transcription model's weights are CC BY 4.0 (accepted 2026-09-28 with attribution in the app's
About and AUTHORS; see docs/STUDIO_SPIKE.md); the composer's are Apache-2.0. `attribution` is the
one-line credit the app shows.
"""

import argparse
import json
import os
import subprocess
import sys

import onnx

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
}


def io(model_path):
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
    entries, files = [], []
    for name in [m.strip() for m in args.models.split(",") if m.strip()]:
        c = CATALOGUE[name]
        path = os.path.join(exports, c["file"])
        inputs, outputs = io(path)
        entries.append({"name": name, "version": c["version"], "file": c["file"],
                        "url": f"https://github.com/{REPO}/releases/download/{TAG}/{c['file']}",
                        "sizeBytes": os.path.getsize(path), "sha256": sc.sha256_file(path),
                        "licence": c["licence"], "source": c["source"], "attribution": c["attribution"],
                        "inputs": inputs, "outputs": outputs})
        files.append(path)
        if os.path.getsize(path) >= 2 * 1024 ** 3:
            raise SystemExit(f"{c['file']} is over GitHub's 2 GB asset limit: split it")
    manifest = {"models": entries}
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
