#!/usr/bin/env python3
# ============================================================================
#  Steven Piano - Android player for the self-playing acoustic piano
#  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
#  Original author & creator: Steven Jin.
#  Licensed under the MIT License (see LICENSE). This copyright and attribution
#  notice MUST be preserved in all copies or substantial portions of the work.
#  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
# ============================================================================

"""Build Steven's library pack, library-v<n>.zip, and (with --upload) publish it on the GitHub release `library`.

The pack (v1.10 — M27) is what a new tablet loads with "Load Steven's library": the MIDI files INDEX.csv lists in
the library folder (--midi, by default ../midi beside this repository), nothing else (pop-shopping-list/, ALL SONGS/,
ALL-SONGS.zip and any other file the index does not name stay out), each file once (a byte-for-byte copy of a file
listed earlier is left out and named on the way), and beside them:

  INDEX.csv                    the index's rows for the files in the pack, with a new last column `sha256`
                               (each file's SHA-256, lower-case hex), so the app knows a pack's pieces before
                               it reads them and an update brings only pieces it never offered before;
  README.md                    the library's own README (its collections and their required credit lines);
  _maestro-metadata/LICENSE    MAESTRO's licence (CC BY-NC-SA 4.0), as Google Magenta ships it.

The zip is deterministic (sorted by the index's order, fixed timestamps, deflate level 9): the same folder builds
the same bytes. It is checked against the app's caps before anything is written: at most 200 MiB (the app's
UpdateSource.MAX_LIBRARY_BYTES), 20,000 entries (ImportLimits.ZIP_ENTRIES), an INDEX.csv of at most 2 MiB
(ImportLimits.INDEX_BYTES), no MIDI file over 8 MiB (the importer's cap), plain relative paths only.

The manifest, `releases/library.json` in this repository (--manifest), is what the app reads from
raw.githubusercontent.com (main), at most 4 KB:

  {"version": 1, "file": "library-v1.zip",
   "url": "https://github.com/stevenjin20090101-rgb/steven-piano-android/releases/download/library/library-v1.zip",
   "sizeBytes": ..., "sha256": "<64 hex>", "pieces": 1726, "notes": "...",
   "licences": ["MAESTRO CC BY-NC-SA 4.0", "piano-midi.de CC BY-SA 3.0 DE", "Mutopia public domain"]}

With --upload, the zip and a copy of the manifest are uploaded to the release tagged `library` (created with
--latest=false, so "Latest" stays on the newest app release; assets replaced with --clobber when it exists), then
read back through GitHub and compared, and the public URL is asked for (it must answer 200 with the pack's bytes).
Commit releases/library.json after the upload: the app offers a pack only once the manifest naming it is on main,
and by then its file is there.

  python3 tools/publish_library.py                         # build the next version into build/library/, no upload
  python3 tools/publish_library.py --version 1 --upload    # build version 1 and publish it
  python3 tools/publish_library.py --midi DIR --version 2 --work DIR --manifest DIR/library.json \\
      --url-base http://10.0.2.2:8767/                     # a test pack for the emulator, served from the Mac

A pack's version only rises: a tablet offers "Update the library" when the manifest's version is above the one it
loaded, and brings in only the pieces no earlier pack offered it (it never deletes one).
"""

import argparse
import csv
import hashlib
import io
import json
import os
import re
import subprocess
import sys
import unicodedata
import urllib.request
import zipfile

REPO = "stevenjin20090101-rgb/steven-piano-android"
TAG = "library"
URL_BASE = f"https://github.com/{REPO}/releases/download/{TAG}/"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# The app's caps (keep in step with UpdateSource.MAX_LIBRARY_BYTES, ImportLimits, Importer and LibraryManifest).
MAX_PACK_BYTES = 200 * 1024 * 1024
MAX_ENTRIES = 20_000
MAX_INDEX_BYTES = 2 * 1024 * 1024
MAX_MIDI_BYTES = 8 * 1024 * 1024
MAX_MANIFEST_BYTES = 4 * 1024
MAX_VERSION = 10_000
MAX_NOTES = 1_000

LICENCES = ["MAESTRO CC BY-NC-SA 4.0", "piano-midi.de CC BY-SA 3.0 DE", "Mutopia public domain"]
EXTRAS = ["README.md", "_maestro-metadata/LICENSE"]
COLUMNS = ["collection", "composer", "title", "size_kb", "path"]
FIXED_TIME = (1980, 1, 1, 0, 0, 0)


def sha256_bytes(data):
    return hashlib.sha256(data).hexdigest()


def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def plain_path(path):
    """A relative path of plain segments: no leading slash, no backslash, no `.` or `..`, no hidden segment."""
    if not path or path.startswith("/") or "\\" in path or "\0" in path:
        return False
    return all(seg and seg not in (".", "..") and not seg.startswith(".") for seg in path.split("/"))


def read_index(midi):
    """The index's header and rows (dicts), checked: the five columns, plain relative paths to MIDI files."""
    with open(os.path.join(midi, "INDEX.csv"), encoding="utf-8-sig", newline="") as f:
        reader = csv.reader(f)
        columns = [h.strip().lower() for h in next(reader)]
        missing = [c for c in COLUMNS if c not in columns]
        if missing:
            raise SystemExit(f"INDEX.csv has no column {', '.join(missing)}")
        header = [h for h in columns if h != "sha256"]   # a sha256 column already there is recomputed below
        rows = []
        for n, cells in enumerate(reader, start=2):
            if not any(c.strip() for c in cells):
                continue
            row = {name: (cells[i].strip() if i < len(cells) else "") for i, name in enumerate(columns)}
            path = unicodedata.normalize("NFC", row["path"])
            if not plain_path(path) or not path.lower().endswith((".mid", ".midi")):
                raise SystemExit(f"INDEX.csv line {n}: {row['path']!r} is not a plain relative path to a MIDI file")
            row["path"] = path
            rows.append(row)
    return header, rows


def build(midi, version, work, url_base, notes):
    header, rows = read_index(midi)
    kept, seen, dropped = [], {}, []
    for row in rows:
        path = os.path.join(midi, row["path"])
        if not os.path.isfile(path):
            raise SystemExit(f"INDEX.csv names {row['path']!r}, which is not in {midi}")
        size = os.path.getsize(path)
        if size > MAX_MIDI_BYTES:
            raise SystemExit(f"{row['path']} is {size} bytes; the app reads a MIDI file of at most {MAX_MIDI_BYTES}")
        with open(path, "rb") as f:
            data = f.read()
        sha = sha256_bytes(data)
        if sha in seen:
            dropped.append((row["path"], seen[sha]))
            continue
        seen[sha] = row["path"]
        kept.append((row, data, sha))
    if len({r["path"].lower() for r, _, _ in kept}) != len(kept):
        raise SystemExit("INDEX.csv names the same path twice")
    for extra in EXTRAS:
        if not os.path.isfile(os.path.join(midi, extra)):
            raise SystemExit(f"{extra} is missing from {midi}")

    out = io.StringIO()
    writer = csv.writer(out, lineterminator="\n")
    writer.writerow(header + ["sha256"])
    for row, _, sha in kept:
        writer.writerow([row.get(name, "") for name in header] + [sha])
    index_bytes = out.getvalue().encode("utf-8")
    if len(index_bytes) > MAX_INDEX_BYTES:
        raise SystemExit(f"INDEX.csv would be {len(index_bytes)} bytes; the app reads at most {MAX_INDEX_BYTES}")
    entries = 1 + len(EXTRAS) + len(kept)
    if entries > MAX_ENTRIES:
        raise SystemExit(f"The pack would hold {entries} entries; the app reads at most {MAX_ENTRIES}")

    os.makedirs(work, exist_ok=True)
    name = f"library-v{version}.zip"
    zip_path = os.path.join(work, name)
    part = zip_path + ".part"

    def add(z, arcname, data):
        info = zipfile.ZipInfo(arcname, date_time=FIXED_TIME)
        info.compress_type = zipfile.ZIP_DEFLATED
        info.external_attr = 0o644 << 16
        info.create_system = 3   # Unix: the attributes above are read as such
        z.writestr(info, data, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)

    with zipfile.ZipFile(part, "w") as z:
        add(z, "INDEX.csv", index_bytes)
        for extra in EXTRAS:
            with open(os.path.join(midi, extra), "rb") as f:
                add(z, extra, f.read())
        for row, data, _ in kept:
            add(z, row["path"], data)
    os.replace(part, zip_path)
    size = os.path.getsize(zip_path)
    if size > MAX_PACK_BYTES:
        raise SystemExit(f"{name} is {size} bytes; the app downloads at most {MAX_PACK_BYTES}")
    sha = sha256_file(zip_path)
    verify(zip_path, kept)

    manifest = {
        "version": version,
        "file": name,
        "url": url_base + name,
        "sizeBytes": size,
        "sha256": sha,
        "pieces": len(kept),
        "notes": notes.replace("{pieces}", f"{len(kept):,}"),
        "licences": LICENCES,
    }
    text = json.dumps(manifest, indent=2, ensure_ascii=False) + "\n"
    if len(text.encode("utf-8")) > MAX_MANIFEST_BYTES:
        raise SystemExit(f"The manifest is {len(text.encode('utf-8'))} bytes; the app reads at most {MAX_MANIFEST_BYTES}")
    unpacked = sum(len(d) for _, d, _ in kept)
    print(f"{name}: {size:,} bytes ({size / 1e6:.1f} MB), sha256 {sha}")
    print(f"  {len(kept):,} pieces ({unpacked:,} bytes of MIDI), {entries:,} entries, INDEX.csv {len(index_bytes):,} bytes")
    for path, first in dropped:
        print(f"  left out {path}: the same bytes as {first}")
    return zip_path, manifest, text


def verify(zip_path, kept):
    """The zip as the app reads it: every entry a plain path, the index's rows naming the files, each file's hash."""
    with zipfile.ZipFile(zip_path) as z:
        names = z.namelist()
        if len(names) != len(set(n.lower() for n in names)):
            raise SystemExit("the zip names an entry twice")
        if not all(plain_path(n) for n in names):
            raise SystemExit("the zip holds a path that is not plain")
        index = list(csv.DictReader(io.StringIO(z.read("INDEX.csv").decode("utf-8"))))
        if len(index) != len(kept):
            raise SystemExit("the zip's INDEX.csv does not list every piece")
        for row in index:
            if not re.fullmatch(r"[0-9a-f]{64}", row["sha256"]) or sha256_bytes(z.read(row["path"])) != row["sha256"]:
                raise SystemExit(f"{row['path']}: its sha256 in INDEX.csv is not its own")


def upload(zip_path, manifest_copy, notes):
    assets = [zip_path, manifest_copy]
    exists = subprocess.run(["gh", "release", "view", TAG, "--repo", REPO], capture_output=True).returncode == 0
    if exists:
        subprocess.check_call(["gh", "release", "upload", TAG, "--repo", REPO, "--clobber"] + assets)
    else:
        body = (notes + "\n\nDownloaded by Steven Piano on demand (Library › Load Steven's library), never bundled "
                "in the APK. library.json (also releases/library.json on main) names the pack with its size and "
                "SHA-256, which the app checks before it reads a byte of it. Built by tools/publish_library.py.")
        subprocess.check_call(["gh", "release", "create", TAG, "--repo", REPO, "--title", "Steven's library",
                               "--notes", body, "--target", "main", "--latest=false"] + assets)
    check = os.path.join(os.path.dirname(zip_path), "release-check")
    os.makedirs(check, exist_ok=True)
    for asset in assets:
        dst = os.path.join(check, os.path.basename(asset))
        if os.path.exists(dst):
            os.remove(dst)
        subprocess.check_call(["gh", "release", "download", TAG, "--repo", REPO, "--pattern", os.path.basename(asset),
                               "--dir", check])
        ok = sha256_file(dst) == sha256_file(asset)
        print(os.path.basename(asset), "round trip", "OK" if ok else "MISMATCH")
        if not ok:
            sys.exit(1)


def public_check(url, sha):
    """The public address, as a tablet asks for it: 200, the pack's bytes, and where GitHub sent it."""
    request = urllib.request.Request(url, headers={"User-Agent": "steven-piano-publish-library"})
    digest, count = hashlib.sha256(), 0
    with urllib.request.urlopen(request, timeout=60) as answer:
        final = answer.geturl()
        for block in iter(lambda: answer.read(1 << 20), b""):
            digest.update(block)
            count += len(block)
    ok = digest.hexdigest() == sha
    print(f"public {url}: {count:,} bytes via {final.split('?')[0].split('/')[2]}, {'OK' if ok else 'MISMATCH'}")
    if not ok:
        sys.exit(1)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--midi", default=os.path.join(os.path.dirname(ROOT), "midi"), help="the library folder (INDEX.csv inside)")
    ap.add_argument("--version", type=int, help="the pack's version (default: the manifest's next)")
    ap.add_argument("--work", default=os.path.join(ROOT, "build", "library"), help="where the zip is built (never committed)")
    ap.add_argument("--manifest", default=os.path.join(ROOT, "releases", "library.json"), help="the manifest to write")
    ap.add_argument("--url-base", default=URL_BASE, help="where the zip is served (default: the release `library`)")
    ap.add_argument("--notes", default=("Steven's library: {pieces} piano pieces from MAESTRO v3.0.0 (Google Magenta), "
                                        "piano-midi.de (Bernd Krüger) and the Mutopia Project. For non-commercial use."))
    ap.add_argument("--upload", action="store_true", help="publish on the release `library` (needs gh signed in)")
    args = ap.parse_args()

    current = None
    if os.path.exists(args.manifest):
        with open(args.manifest, encoding="utf-8") as f:
            current = json.load(f).get("version")
    version = args.version if args.version is not None else (current or 0) + 1
    if not 1 <= version <= MAX_VERSION:
        raise SystemExit(f"--version must be 1..{MAX_VERSION}")
    if current is not None and version < current:
        raise SystemExit(f"{args.manifest} is at version {current}: a pack's version only rises")
    if len(args.notes) > MAX_NOTES:
        raise SystemExit(f"--notes is {len(args.notes)} characters; the app keeps at most {MAX_NOTES}")
    if args.upload and args.url_base != URL_BASE:
        raise SystemExit("--upload publishes on the release `library`: leave --url-base as it is")

    zip_path, manifest, text = build(os.path.abspath(args.midi), version, os.path.abspath(args.work), args.url_base, args.notes)
    manifest_copy = os.path.join(os.path.abspath(args.work), "library.json")
    for dest in {os.path.abspath(args.manifest), manifest_copy}:
        with open(dest, "w", encoding="utf-8") as f:
            f.write(text)
        print("wrote", dest)
    if args.upload:
        upload(zip_path, manifest_copy, manifest["notes"])
        public_check(manifest["url"], manifest["sha256"])


if __name__ == "__main__":
    main()
