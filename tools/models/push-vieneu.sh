#!/usr/bin/env bash
# Stage VieNeu-TTS v3 Nano (models-vieneu/tts_vieneu) into the app's
# files/models/npu/en-vi/tts_vieneu/ on a debuggable install.
#
# Same route as push-npu-models.sh: adb push to /data/local/tmp, then run-as
# copies into internal storage, because files under external Android/data
# written by another uid are invisible to the app on Android 16.
#
#   tools/models/prepare-vieneu-nano.py && tools/models/push-vieneu.sh R5CW72TP4EP
set -euo pipefail

SERIAL="${1:?usage: push-vieneu.sh <adb-serial>}"
PKG="com.onevoice.link"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SRC="$ROOT/models-vieneu/tts_vieneu"
DEST="files/models/npu/en-vi/tts_vieneu"
TMP="/data/local/tmp/onevoice_vieneu.part"
adb_cmd() { adb -s "$SERIAL" "$@"; }

[ -f "$SRC/sea_g2p.bin" ] || { echo "!! $SRC/sea_g2p.bin missing: run build-sea-g2p-android.sh, then prepare-vieneu-nano.py" >&2; exit 1; }
adb_cmd shell run-as "$PKG" true </dev/null || { echo "!! run-as failed: install the debuggable build first" >&2; exit 1; }
adb_cmd shell run-as "$PKG" mkdir -p "$DEST" </dev/null

for file in "$SRC"/*; do
  name="$(basename "$file")"
  local_sum="$(sha256sum "$file" | cut -d' ' -f1)"
  remote_sum="$(adb_cmd shell run-as "$PKG" sha256sum "$DEST/$name" </dev/null 2>/dev/null | cut -d' ' -f1 || true)"
  if [ "$local_sum" = "$remote_sum" ]; then
    echo "  = $name"
    continue
  fi
  adb_cmd push "$file" "$TMP" >/dev/null
  # Copy to .part and rename, so an interrupted push never leaves a truncated
  # model under its real name.
  adb_cmd shell run-as "$PKG" cp "$TMP" "$DEST/$name.part" </dev/null
  adb_cmd shell run-as "$PKG" mv "$DEST/$name.part" "$DEST/$name" </dev/null
  echo "  + $name ($(stat -c %s "$file") bytes)"
done
adb_cmd shell rm -f "$TMP" </dev/null
echo "VieNeu staged under $DEST"
