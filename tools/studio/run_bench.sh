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
# Drives the M22 Studio bench (a debug build carrying the bench: restore it with
# `git cherry-pick <bench commit>`, see docs/STUDIO_SPIKE.md) on one device from the Mac.
#
#   tools/studio/run_bench.sh -s <serial> --push <work dir>        # models + inputs -> filesDir
#   tools/studio/run_bench.sh -s <serial> <label> bench=transcription model=transcription-v1.onnx
#   tools/studio/run_bench.sh -s <serial> <label> bench=composer tokens=3600
#
# Each run starts a fresh app process (force-stop), polls `dumpsys meminfo` every 2 s (NOPOLL=1
# turns that off), waits for filesDir/studio/bench-<label>.json, copies it and the StudioBench
# log lines to <out> (default ./bench-results) and prints the largest TOTAL PSS/RSS seen.
# The JSON's vm_hwm_kb is the process's peak resident set (/proc/self/status VmHWM).
set -euo pipefail
PKG=dev.stevenjin.stevenpiano
SERIAL=""
OUT=${OUT:-bench-results}
[ "${1:-}" = "-s" ] && { SERIAL=$2; shift 2; }
[ -n "$SERIAL" ] || { echo "usage: $0 -s <serial> (--push <work> | <label> key=value...)" >&2; exit 2; }
ADB=(adb -s "$SERIAL")
echo "device: $("${ADB[@]}" shell getprop ro.product.model | tr -d '\r') ($("${ADB[@]}" shell getprop ro.product.cpu.abi | tr -d '\r'))" >&2

copy_in() {  # <local file> <files/ subdir>
  local name; name=$(basename "$1")
  "${ADB[@]}" push "$1" "/data/local/tmp/m22_$name" >/dev/null
  "${ADB[@]}" shell "cat /data/local/tmp/m22_$name | run-as $PKG sh -c 'mkdir -p files/$2 && cat > files/$2/$name'"
  "${ADB[@]}" shell rm "/data/local/tmp/m22_$name"
}

if [ "${1:-}" = "--push" ]; then
  WORK=$2
  HERE=$(cd "$(dirname "$0")" && pwd)
  for m in transcription-v1.onnx composer-v1.onnx; do
    [ -f "$WORK/exports/$m" ] && copy_in "$WORK/exports/$m" models
  done
  copy_in "$WORK/audio/bench_3min.wav" studio
  for f in transcription_window.wav transcription_window.json composer_seed.json; do copy_in "$HERE/fixtures/$f" studio; done
  "${ADB[@]}" shell run-as $PKG ls -l files/models files/studio
  exit 0
fi

LABEL=$1; shift
EXTRAS=(--es label "$LABEL")
for kv in "$@"; do EXTRAS+=(--es "${kv%%=*}" "${kv#*=}"); done
mkdir -p "$OUT"
"${ADB[@]}" shell am force-stop $PKG
sleep 1
"${ADB[@]}" shell run-as $PKG rm -f "files/studio/bench-$LABEL.json"
"${ADB[@]}" logcat -c
"${ADB[@]}" shell am start -W -n $PKG/.studio.StudioBenchActivity "${EXTRAS[@]}" >/dev/null
MEM="$OUT/$LABEL.meminfo.tsv"; : > "$MEM"
start=$(date +%s)
until "${ADB[@]}" shell run-as $PKG ls "files/studio/bench-$LABEL.json" >/dev/null 2>&1; do
  if [ -z "${NOPOLL:-}" ]; then
    echo -e "$(( $(date +%s) - start ))\t$("${ADB[@]}" shell dumpsys meminfo $PKG | grep -E 'TOTAL PSS:' | head -1 | tr -d '\r')" >> "$MEM"
  fi
  [ $(( $(date +%s) - start )) -gt 3600 ] && { echo "timed out" >&2; exit 1; }
  sleep 2
done
"${ADB[@]}" shell run-as $PKG cat "files/studio/bench-$LABEL.json" > "$OUT/$LABEL.json"
"${ADB[@]}" logcat -d -s StudioBench:I > "$OUT/$LABEL.log"
python3 - "$MEM" <<'PY'
import re, sys
pss = rss = 0
for line in open(sys.argv[1]):
    m = re.search(r"TOTAL PSS:\s+(\d+)\s+TOTAL RSS:\s+(\d+)", line)
    if m:
        pss, rss = max(pss, int(m.group(1))), max(rss, int(m.group(2)))
print(f"dumpsys meminfo max: TOTAL PSS {pss} kB, TOTAL RSS {rss} kB")
PY
echo "wall $(( $(date +%s) - start )) s; result $OUT/$LABEL.json"
