#!/usr/bin/env python3
# ============================================================================
#  Steven Piano - Android player for the self-playing acoustic piano
#  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
#  Original author & creator: Steven Jin.
#  Licensed under the MIT License (see LICENSE). This copyright and attribution
#  notice MUST be preserved in all copies or substantial portions of the work.
#  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
# ============================================================================

"""Re-sign this repository's authorship manifest.

Run this after ANY source change, then commit provenance/. Otherwise
verify.py correctly reports the tree as modified.

    python3 provenance/sign.py

Requires the private key (NOT in this repo — see PROVENANCE.md):
    ~/piano-authorship-PRIVATE-DO-NOT-SHARE.pem
"""
import os, sys, hashlib, base64

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
PRIV = os.path.expanduser("~/piano-authorship-PRIVATE-DO-NOT-SHARE.pem")

EXCLUDE_DIRS = {".git", ".gradle", ".kotlin", ".idea", "build", ".claude",
                "node_modules", ".wrangler"}   # the cloud's installed packages and dev state (R1)
EXCLUDE_FILES = {"MANIFEST.txt", "MANIFEST.sig", ".DS_Store"}
INCLUDE_EXT = {".kt", ".kts", ".toml", ".xml", ".md", ".py", ".pro", ".pem", ".sh",
               ".html", ".js", ".css",   # the web panel's pages (M18)
               ".ts", ".mjs", ".jsonc", ".sql"}   # the cloud's sources (R1)
INCLUDE_NAMES = {"LICENSE", "AUTHORS", ".gitignore", "gradlew", "gradle.properties",
                 "gradle-wrapper.properties"}


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def main():
    if not os.path.exists(PRIV):
        print("ERROR: private key not found at", PRIV)
        print("Only Steven Jin can re-sign this tree.")
        return 2
    try:
        from cryptography.hazmat.primitives.serialization import load_pem_private_key
    except ImportError:
        print("ERROR: pip install cryptography")
        return 2

    rows = []
    for root, dirs, files in os.walk(REPO):
        dirs[:] = [d for d in dirs if d not in EXCLUDE_DIRS]
        for name in files:
            if name in EXCLUDE_FILES:
                continue
            ext = os.path.splitext(name)[1].lower()
            if ext not in INCLUDE_EXT and name not in INCLUDE_NAMES:
                continue
            full = os.path.join(root, name)
            rows.append((os.path.relpath(full, REPO), sha256(full)))
    rows.sort()

    lines = [
        "# Steven Piano - authorship manifest",
        "# Authored by Steven Jin <stevenjin20090101@gmail.com>",
        "# Ed25519 public-key fingerprint: eab16a502f679465",
        "# Each line: <sha256>  <path>. Signed in MANIFEST.sig (verify.py checks it).",
        "",
    ]
    lines += [f"{digest}  {rel}" for rel, digest in rows]
    manifest = "\n".join(lines) + "\n"

    with open(os.path.join(HERE, "MANIFEST.txt"), "w", encoding="utf-8") as f:
        f.write(manifest)

    priv = load_pem_private_key(open(PRIV, "rb").read(), password=None)
    with open(os.path.join(HERE, "MANIFEST.sig"), "wb") as f:
        f.write(base64.b64encode(priv.sign(manifest.encode("utf-8"))))

    print(f"signed {len(rows)} files — now run provenance/verify.py and commit provenance/")
    return 0


if __name__ == "__main__":
    sys.exit(main())
