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
# Publishes a release of Steven Piano, so every installed copy offers it on the Piano tab
# (README > Publishing a release):
#
#   tools/publish-release.sh <versionName> "<notes>"
#   tools/publish-release.sh 1.5 "Fixes for the school tablet."
#
# Before running it: set versionCode (one higher) and versionName in app/build.gradle.kts and
# Provenance.text, update the docs, commit, and re-sign provenance. The tree must be clean and
# on main. It needs ~/steven-piano-keystore.properties (the release key, never in the repo),
# JDK 17, the Android SDK, and the GitHub CLI signed in (`gh auth login`).
#
# What it does, in order, stopping at the first problem:
#   1. checks: the version name, a clean tree on main that is not behind GitHub, no release or
#      tag of that name yet, the release key's properties, the authorship signature;
#   2. builds the release APK (./gradlew assembleRelease) and checks it: its versionName is the
#      one given, it is signed with the release key (CN=Steven Piano), it is under 50 MB;
#   3. copies it to ../apk/steven-piano-<versionName>.apk and takes its SHA-256 and size;
#   4. pushes main, then creates the GitHub release v<versionName> at that commit with the APK
#      attached (gh release create);
#   5. writes releases/latest.json (versionCode read from the APK itself, versionName, notes, the
#      release asset's URL, SHA-256, size, minSdk), appends it to releases/history.json, commits
#      them ("Co-Authored-By: Claude Fable 5.1") and pushes.
# The release exists before the manifest that names it is pushed, so no phone ever reads a
# manifest whose file is not there yet. Phones read the manifest from raw.githubusercontent.com,
# so the repository must be public.

set -euo pipefail

REPO="stevenjin20090101-rgb/steven-piano-android"
MAX_APK_BYTES=$((50 * 1024 * 1024))
CO_AUTHOR="Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"

die() {
    echo "publish-release: $*" >&2
    exit 1
}

[ $# -eq 2 ] || { echo "usage: tools/publish-release.sh <versionName> \"<notes>\"" >&2; exit 64; }
VERSION="$1"
NOTES="$2"
# The app's own rule for a version name (it names the downloaded file): letters, digits, . _ -
[[ "$VERSION" =~ ^[0-9A-Za-z][0-9A-Za-z._-]{0,31}$ ]] || die "\"$VERSION\" is not a version name (letters, digits, dots, dashes)."
[ "${#NOTES}" -le 1000 ] || die "The notes are ${#NOTES} characters; the app shows at most 1,000."

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
TAG="v$VERSION"
APK_NAME="steven-piano-$VERSION.apk"
APK_DIR="$(cd "$ROOT/.." && pwd)/apk"
APK_URL="https://github.com/$REPO/releases/download/$TAG/$APK_NAME"
BUILT="app/build/outputs/apk/release/app-release.apk"

# --- 1. Checks -------------------------------------------------------------------------------
command -v gh >/dev/null || die "The GitHub CLI (gh) is not installed: brew install gh"
gh auth status >/dev/null 2>&1 || die "gh is not signed in: gh auth login"
command -v python3 >/dev/null || die "python3 is needed to write the manifest."
BUILD_TOOLS="$(ls -d "$ANDROID_HOME"/build-tools/* 2>/dev/null | sort -V | tail -1)"
[ -x "$BUILD_TOOLS/aapt2" ] && [ -x "$BUILD_TOOLS/apksigner" ] || die "No build-tools with aapt2 and apksigner under $ANDROID_HOME."
[ -f "$HOME/steven-piano-keystore.properties" ] || die "~/steven-piano-keystore.properties is missing: the release key signs every release."

[ "$(git rev-parse --abbrev-ref HEAD)" = "main" ] || die "Not on main."
[ -z "$(git status --porcelain)" ] || die "The tree has uncommitted changes; commit them (and re-sign provenance) first."
git fetch --quiet origin main
git merge-base --is-ancestor origin/main HEAD || die "main is behind GitHub's; pull first."
if git rev-parse -q --verify "refs/tags/$TAG" >/dev/null || [ -n "$(git ls-remote --tags origin "refs/tags/$TAG")" ]; then
    die "The tag $TAG exists already: every release needs a new versionName."
fi
if gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1; then
    die "The GitHub release $TAG exists already."
fi

PYTHON_SIGN="$HOME/.platformio/penv/bin/python3"
[ -x "$PYTHON_SIGN" ] || PYTHON_SIGN="python3"
# Output is captured before it is searched: with pipefail, grep -q closing the pipe early could fail the check.
PROVENANCE_OUT="$("$PYTHON_SIGN" provenance/verify.py 2>&1)" || die "provenance/verify.py does not verify: re-sign provenance and commit it first."
grep -q "AUTHORSHIP VERIFIED" <<<"$PROVENANCE_OUT" || die "provenance/verify.py does not verify: re-sign provenance and commit it first."

# --- 2. Build and check ----------------------------------------------------------------------
./gradlew --console=plain assembleRelease
[ -f "$BUILT" ] || die "The build left no $BUILT."
BADGING="$("$BUILD_TOOLS/aapt2" dump badging "$BUILT")"
BUILT_NAME="$(sed -nE "s/^package: .*versionName='([^']*)'.*/\1/p" <<<"$BADGING")"
VERSION_CODE="$(sed -nE "s/^package: .*versionCode='([0-9]+)'.*/\1/p" <<<"$BADGING")"
MIN_SDK="$(sed -nE "s/^minSdkVersion:'([0-9]+)'.*/\1/p" <<<"$BADGING")"
[ "$BUILT_NAME" = "$VERSION" ] || die "The build's versionName is \"$BUILT_NAME\", not \"$VERSION\": set it in app/build.gradle.kts."
[ -n "$VERSION_CODE" ] && [ -n "$MIN_SDK" ] || die "Could not read versionCode and minSdk from the APK."
SIGNER="$("$BUILD_TOOLS/apksigner" verify --print-certs "$BUILT")" || die "apksigner does not verify the APK."
grep -q "CN=Steven Piano" <<<"$SIGNER" || die "The APK is not signed with Steven Piano's release key."

# --- 3. Copy, hash, size ---------------------------------------------------------------------
mkdir -p "$APK_DIR"
cp "$BUILT" "$APK_DIR/$APK_NAME"
SHA256="$(shasum -a 256 "$APK_DIR/$APK_NAME" | cut -d' ' -f1)"
SIZE="$(wc -c <"$APK_DIR/$APK_NAME" | tr -d ' ')"
[ "$SIZE" -le "$MAX_APK_BYTES" ] || die "The APK is $SIZE bytes; the app downloads at most $MAX_APK_BYTES."
echo "Built $APK_NAME: versionCode $VERSION_CODE, $SIZE bytes, SHA-256 $SHA256"

# --- 4. The release, at the commit that built it --------------------------------------------
SOURCE="$(git rev-parse HEAD)"
git push origin HEAD:main
gh release create "$TAG" "$APK_DIR/$APK_NAME" --repo "$REPO" --target "$SOURCE" --title "Steven Piano $VERSION" --notes "$NOTES"

# --- 5. The manifest phones read, then the history --------------------------------------------
mkdir -p releases
python3 - "$VERSION_CODE" "$VERSION" "$NOTES" "$APK_URL" "$SHA256" "$SIZE" "$MIN_SDK" "$TAG" <<'PY'
import datetime, json, os, sys
code, name, notes, url, sha, size, min_sdk, tag = sys.argv[1:]
latest = {
    "versionCode": int(code), "versionName": name, "notes": notes, "apkUrl": url,
    "sha256": sha, "sizeBytes": int(size), "minSdk": int(min_sdk),
}
with open("releases/latest.json", "w", encoding="utf-8") as f:
    json.dump(latest, f, indent=2, ensure_ascii=False)
    f.write("\n")
history = []
if os.path.exists("releases/history.json"):
    with open("releases/history.json", encoding="utf-8") as f:
        history = json.load(f)
history.append(dict(latest, tag=tag, publishedAt=datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d")))
with open("releases/history.json", "w", encoding="utf-8") as f:
    json.dump(history, f, indent=2, ensure_ascii=False)
    f.write("\n")
PY
git add releases/latest.json releases/history.json
git commit --quiet -F - <<EOF
Release $VERSION: releases/latest.json offers $TAG

$NOTES

$CO_AUTHOR
EOF
if ! git push origin main; then
    die "The release $TAG exists, but the manifest commit did not reach GitHub: run \`git push origin main\`."
fi
git fetch --quiet --tags origin

echo "Published Steven Piano $VERSION ($TAG): $APK_URL"
echo "Installed copies offer it within a day, or at once with Piano > Check now."
