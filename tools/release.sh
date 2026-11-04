#!/usr/bin/env bash
# ============================================================================
#  Steven Piano - Android player for the self-playing acoustic piano
#  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
#  Original author & creator: Steven Jin.
#  Licensed under the MIT License (see LICENSE). This copyright and attribution
#  notice MUST be preserved in all copies or substantial portions of the work.
#  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
# ============================================================================
#
# Takes a release from a clean main to ready to publish, in one command (README > Publishing a release):
#
#   tools/release.sh <versionName> "<notes>"             prepares it: nothing leaves this Mac
#   tools/release.sh <versionName> "<notes>" --publish   prepares it, then runs tools/publish-release.sh
#
# What it does, in order, stopping at the first problem:
#   1. checks: the version name and the notes, a clean tree on main, no tag of that name yet;
#   2. sets the version everywhere it is written: versionCode (one higher) and versionName in
#      app/build.gradle.kts, Provenance.text, the README's two lines, a "The release" section at the end of
#      BUILD_SPEC.md, and the drafted entry at the end of releases/history.json;
#   3. runs the gate (the unit tests, lint, the debug build);
#   4. commits, re-signs provenance, checks the signature and commits provenance/.
# When the gate fails the edits stay uncommitted, to fix; `git checkout .` starts over.

set -euo pipefail

CO_AUTHOR="${CO_AUTHOR:-Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>}"
SIGNING_PYTHON="$HOME/.platformio/penv/bin/python3"   # sign.py needs its cryptography package

die() {
    echo "release: $*" >&2
    exit 1
}

[ $# -ge 2 ] && [ $# -le 3 ] || { echo "usage: tools/release.sh <versionName> \"<notes>\" [--publish]" >&2; exit 64; }
VERSION="$1"
NOTES="$2"
PUBLISH="${3:-}"
[ -z "$PUBLISH" ] || [ "$PUBLISH" = "--publish" ] || die "\"$PUBLISH\" is not an option; only --publish is."
# The app's own rule for a version name, as tools/publish-release.sh checks it.
[[ "$VERSION" =~ ^[0-9A-Za-z][0-9A-Za-z._-]{0,31}$ ]] || die "\"$VERSION\" is not a version name."
[ -n "$NOTES" ] && [ "${#NOTES}" -le 1000 ] || die "The notes must be 1 to 1,000 characters; these are ${#NOTES}."

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"

# --- 1. The checks ------------------------------------------------------------------------------
[ "$(git rev-parse --abbrev-ref HEAD)" = "main" ] || die "Not on main."
[ -z "$(git status --porcelain)" ] || die "The tree isn't clean: commit or put away what's there first."
if git rev-parse -q --verify "refs/tags/v$VERSION" >/dev/null; then die "v$VERSION is tagged already."; fi
[ -x "$SIGNING_PYTHON" ] || die "$SIGNING_PYTHON isn't there; provenance can't be signed without it."

# --- 2. The version, everywhere it is written ---------------------------------------------------
VERSION="$VERSION" NOTES="$NOTES" python3 -I - <<'PY'
import json
import os
import re
from pathlib import Path


def swap(path, pattern, make, count=1):
    """The match of [pattern] in [path] replaced by what [make] returns, exactly [count] times or stop."""
    file = Path(path)
    text, n = re.subn(pattern, make, file.read_text(encoding="utf-8"), flags=re.M)
    if n != count:
        raise SystemExit(f"release: {path}: expected {count} of /{pattern}/, found {n}. Nothing was committed.")
    file.write_text(text, encoding="utf-8")


version, notes = os.environ["VERSION"], os.environ["NOTES"]
gradle = Path("app/build.gradle.kts").read_text(encoding="utf-8")
old_code = int(re.search(r'versionCodeOverride"\)\.orNull\?\.toIntOrNull\(\) \?: (\d+)', gradle)[1])
old_name = re.search(r'versionName = "([^"]+)"', gradle)[1]
if version == old_name:
    raise SystemExit(f"release: the version is {version} already.")
code = old_code + 1

swap("app/build.gradle.kts", r'(versionCodeOverride"\)\.orNull\?\.toIntOrNull\(\) \?: )\d+', lambda m: f"{m[1]}{code}")
swap("app/build.gradle.kts", r'versionName = "[^"]+"', lambda m: f'versionName = "{version}"')
swap("app/src/main/java/dev/stevenjin/stevenpiano/Provenance.kt", r"· v[^ ]+ ·", lambda m: f"· v{version} ·")
swap("README.md", rf"Made by Steven Jin\. Version {re.escape(old_name)}\.", lambda m: f"Made by Steven Jin. Version {version}.")
swap("README.md", rf"steven-piano-{re.escape(old_name)}\.apk", lambda m: f"steven-piano-{version}.apk")

spec = Path("BUILD_SPEC.md")
spec.write_text(spec.read_text(encoding="utf-8").rstrip("\n") + f"\n\n## The release: {version} (versionCode {code})\n\n{notes}\n", encoding="utf-8")

history_file = Path("releases/history.json")
raw = history_file.read_text(encoding="utf-8")
history = json.loads(raw)
if any(entry.get("draft") for entry in history):
    raise SystemExit("release: releases/history.json has a drafted release already.")
history.append({"versionCode": code, "versionName": version, "notes": notes, "tag": f"v{version}", "draft": True})
history_file.write_text(json.dumps(history, indent=2, ensure_ascii=False) + ("\n" if raw.endswith("\n") else ""), encoding="utf-8")
print(f"{old_name} (code {old_code}) -> {version} (code {code})")
PY

# --- 3. The gate --------------------------------------------------------------------------------
echo "Running the gate: the unit tests, lint, the debug build."
./gradlew -q testDebugUnitTest --rerun lintDebug assembleDebug \
    || die "The gate failed. The version edits are uncommitted: fix what failed and run the gate again, or \`git checkout .\` to start over."

# --- 4. Commit and sign -------------------------------------------------------------------------
git add -A
git commit -q -m "$VERSION: ${NOTES%%$'\n'*}" -m "$CO_AUTHOR"
"$SIGNING_PYTHON" provenance/sign.py
"$SIGNING_PYTHON" provenance/verify.py
git add provenance
git commit -q -m "$VERSION provenance: the manifest re-signed" -m "$CO_AUTHOR"

if [ "$PUBLISH" = "--publish" ]; then
    exec tools/publish-release.sh "$VERSION" "$NOTES"
fi
echo
echo "$VERSION is ready, and nothing has left this Mac. To publish it:"
echo "  tools/publish-release.sh $VERSION \"$NOTES\""
