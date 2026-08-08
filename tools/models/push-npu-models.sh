#!/usr/bin/env bash
# ============================================================================
# push-npu-models.sh — stage the QNN context binaries onto a connected device
# ----------------------------------------------------------------------------
# Copies the deploy subset of onevoice-npu-bins/ into the layout the app expects:
#
#   <filesDir>/models/npu/vi-en/{asr_parakeet_vi,mt_encoder,mt_decoder,
#                                tts_encoder,tts_decoder}.bin
#   <filesDir>/models/npu/en-vi/{mt_encoder,mt_decoder,tts_encoder,tts_decoder}.bin
#
# WHY THE TWO-STEP COPY (and not a plain `adb push` to external storage):
# on Android 11+ the app process cannot see files under its own
# Android/data/<pkg>/files/ that another uid created. Verified on this device
# (Android 16 / One UI): after `adb push`, `adb shell` lists the .bin fine, the
# path is byte-identical to what the app computes, and the app still gets
# exists() == false — even after chowning the tree to the app's uid. So we push
# to /data/local/tmp (fast, ~45 MB/s) and then `run-as` copies into the app's
# internal filesDir, which the app can always read.
#
# `run-as` requires a debuggable build — that is what this script is for.
#
# The file names and the graph inside each one are pinned by
# android-app/app/src/main/assets/npu_manifest.json — regenerate that asset with
# tools/models/gen_npu_manifest.py whenever the model team ships new binaries,
# or graphRetrieve will fail.
#
# Usage:  tools/models/push-npu-models.sh [vi-en|en-vi|all]     (default: all)
# ============================================================================
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BINS="$REPO/onevoice-npu-bins"
PKG="com.onevoice.link"
TMP="/data/local/tmp/onevoice_stage.bin"
WHICH="${1:-all}"
ADB="${ADB:-adb}"

# dest-relative-path <TAB> source-relative-path
read -r -d '' VI_EN <<'EOF' || true
vi-en/asr_parakeet_vi.bin	asr/parakeet_ctc_vi__DEPLOY/logits.bin
vi-en/mt_encoder.bin	mt/opus_mt_vi_en__float_8gen2/encoder_float.bin
vi-en/mt_decoder.bin	mt/opus_mt_vi_en__float_8gen2/decoder_float.bin
vi-en/tts_encoder.bin	tts/kokoro_en__encoder_8gen2/encoder.bin
vi-en/tts_decoder.bin	tts/kokoro_en__decoder_npu_8gen2/decoder_npu.bin
EOF

# NOTE: en-vi has no ASR entry on purpose — the package ships Vietnamese ASR
# only, so this direction cannot run end-to-end yet (HANDOFF.md §4). The MT/TTS
# halves are staged anyway so the app can load and verify them.
read -r -d '' EN_VI <<'EOF' || true
en-vi/mt_encoder.bin	mt/opus_mt_en_vi__float_8gen2/encoder_float.bin
en-vi/mt_decoder.bin	mt/opus_mt_en_vi__float_8gen2/decoder_float.bin
en-vi/tts_encoder.bin	tts/kokoro_vi__encoder_8gen2/encoder.bin
en-vi/tts_decoder.bin	tts/kokoro_vi__decoder_npu_8gen2/decoder_npu.bin
EOF

case "$WHICH" in
  vi-en) PLAN="$VI_EN" ;;
  en-vi) PLAN="$EN_VI" ;;
  all)   PLAN="$VI_EN"$'\n'"$EN_VI" ;;
  *) echo "usage: $0 [vi-en|en-vi|all]" >&2; exit 2 ;;
esac

# ---- Preflight: one device, the right chip, a debuggable install -----------
if [ "$($ADB devices | grep -c '\sdevice$')" -ne 1 ]; then
  echo "!! need exactly one device in 'device' state:" >&2
  $ADB devices >&2
  exit 1
fi

SOC="$($ADB shell getprop ro.soc.model </dev/null | tr -d '\r')"
BOARD="$($ADB shell getprop ro.board.platform </dev/null | tr -d '\r')"
echo "device soc=$SOC board=$BOARD"
if [ "$SOC" != "SM8550" ]; then
  # These binaries are AOT-compiled for Hexagon v73. On any other HTP
  # generation contextCreateFromBinary fails — pushing 2 GB first is a waste.
  echo "!! expected SM8550 (Snapdragon 8 Gen 2 / Hexagon v73), got '$SOC'." >&2
  echo "   The .bin files will NOT load on this chip; re-export is required." >&2
  echo "   Set FORCE=1 to push anyway." >&2
  [ "${FORCE:-0}" = "1" ] || exit 1
fi

if ! $ADB shell run-as "$PKG" true </dev/null 2>/dev/null; then
  echo "!! run-as $PKG failed — install the debuggable build first." >&2
  exit 1
fi

# run-as starts in the app's data dir (/data/user/0/<pkg>), NOT filesDir, so the
# relative path has to include files/.
ROOT="files/models/npu"
$ADB shell run-as "$PKG" mkdir -p "$ROOT/vi-en" "$ROOT/en-vi" </dev/null

pushed=0
skipped=0
while IFS=$'\t' read -r dest src; do
  [ -n "${dest:-}" ] || continue
  local_file="$BINS/$src"
  if [ ! -f "$local_file" ]; then
    echo "  MISSING SOURCE  $src" >&2
    continue
  fi
  local_size=$(stat -c%s "$local_file")

  # </dev/null on every adb call is load-bearing: adb reads stdin, and without
  # it the first push swallows the rest of $PLAN from this loop's heredoc.
  # `|| echo 0` must run ON THE DEVICE: a missing file makes stat exit non-zero,
  # which under `set -e` would kill the script at the first not-yet-pushed file.
  remote_size=$($ADB shell run-as "$PKG" sh -c "\"stat -c%s '$ROOT/$dest' 2>/dev/null || echo 0\"" </dev/null | tr -d '\r')
  remote_size=${remote_size:-0}
  if [ "$remote_size" = "$local_size" ]; then
    printf '  = %-28s already present (%s bytes)\n' "$dest" "$local_size"
    skipped=$((skipped + 1))
    continue
  fi

  printf '  → %-28s %s MB\n' "$dest" "$((local_size / 1048576))"
  $ADB push "$local_file" "$TMP" >/dev/null </dev/null
  $ADB shell run-as "$PKG" cp "$TMP" "$ROOT/$dest" </dev/null
  # Free the staging copy immediately: the ASR blob alone is 1.15 GB.
  $ADB shell rm -f "$TMP" </dev/null
  pushed=$((pushed + 1))
done <<< "$PLAN"

echo
echo "pushed=$pushed skipped=$skipped"
$ADB shell run-as "$PKG" du -sh "$ROOT/vi-en" "$ROOT/en-vi" </dev/null 2>/dev/null || true
echo
echo "Now open OneVoice Link and tap 'Load Model Cascade'."
