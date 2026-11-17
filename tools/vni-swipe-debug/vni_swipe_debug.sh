#!/usr/bin/env bash
# Pulls the last VNI swipe from logcat, keeps only the lines that matter, redraws it as SVG
# and prints a short summary to paste into the chat.
# Usage: vni_swipe_debug.sh [saved-logcat-file]   (no file: reads the device via adb)
#        wl-paste | vni_swipe_debug.sh /dev/stdin  (log copied from Android Studio Logcat)
# Output goes to $OUT_DIR (default: /tmp/vni-swipe-debug) as vni_last.*
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"
OUT_DIR="${OUT_DIR:-/tmp/vni-swipe-debug}"
mkdir -p "$OUT_DIR"
OUT="$OUT_DIR/vni_last"

if [ $# -ge 1 ]; then
    cat "$1" > "$OUT.raw.log"
else
    "$ADB" logcat -d -s SwipeDecoderDictionary:D > "$OUT.raw.log"
fi

if ! grep -q "VNIDUMP keys" "$OUT.raw.log"; then
    echo "No VNIDUMP lines: the app lacks the debug build, or the 'VNI (tone row)' layout is not selected." >&2
    exit 1
fi

# Everything from the last gesture dump on; of the per-frame summaries keep only the final one
awk '/VNIDUMP keys/{buf=""} {buf=buf $0 "\n"} END{printf "%s", buf}' "$OUT.raw.log" > "$OUT.tail.log"
{
    grep "VNIDUMP" "$OUT.tail.log"
    grep "VNI toneKeys" "$OUT.tail.log" | tail -n 1 || true
} > "$OUT.log"

{
    python3 -I "$DIR/vni_swipe_plot.py" "$OUT.log" "$OUT.svg"
    grep "VNI toneKeys" "$OUT.log" | sed 's/.*\(VNI toneKeys\)/\1/' || true
    grep -o "outputs = .*" "$OUT.tail.log" | tail -n 1 || true
} > "$OUT.summary.txt"

cat "$OUT.summary.txt"
echo
echo "SVG: $OUT.svg   (filtered log: $OUT.log)"
