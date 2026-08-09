#!/usr/bin/env bash
# Stream one direction of the offline CPU/HF model bundle into the app's
# internal filesDir. A debuggable APK is required because ownership is obtained
# with run-as; no world-writable external-storage staging directory is used.
set -euo pipefail

usage() {
  cat >&2 <<'EOF'
usage: tools/models/push-hf-models.sh <vi-en|en-vi> <adb-serial>

examples:
  tools/models/push-hf-models.sh vi-en R5CW72TP4EP
  tools/models/push-hf-models.sh en-vi R5CW72TP4EP

The direction and ADB serial are both mandatory. The script adds or replaces
the selected model files under the app's internal filesDir; it never deletes
other directions or unrelated app data.
EOF
}

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

if [ "$#" -ne 2 ]; then
  usage
  exit 2
fi

DIRECTION="$1"
SERIAL="$2"

case "$DIRECTION" in
  vi-en)
    ASR_DIR="asr-vi"
    MT_DIR="mt-vi-en"
    TTS_DIR="tts-en"
    ESPEAK_DICT="en_dict"
    ;;
  en-vi)
    ASR_DIR="asr-en"
    MT_DIR="mt-en-vi"
    TTS_DIR="tts-vi"
    ESPEAK_DICT="vi_dict"
    ;;
  *)
    usage
    exit 2
    ;;
esac

case "$SERIAL" in
  ""|*[!A-Za-z0-9._:-]*)
    die "invalid ADB serial '$SERIAL'"
    ;;
esac

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MODEL_ROOT_INPUT="${HF_MODELS_DIR:-$REPO/models-hf}"
PACKAGE="com.onevoice.link"
REMOTE_ROOT="files/models/hf"
ADB_BIN="${ADB:-adb}"
PARTS=("$ASR_DIR" "$MT_DIR" "$TTS_DIR" "espeak-ng-data")

if [[ "$ADB_BIN" == */* ]]; then
  [ -x "$ADB_BIN" ] || die "ADB executable not found: $ADB_BIN"
else
  command -v "$ADB_BIN" >/dev/null 2>&1 || die "adb is not on PATH"
fi
for command_name in tar sha256sum find stat; do
  command -v "$command_name" >/dev/null 2>&1 ||
    die "required host command is missing: $command_name"
done

[ -d "$MODEL_ROOT_INPUT" ] ||
  die "model root not found: $MODEL_ROOT_INPUT (run the conversion/fetch scripts first)"
MODEL_ROOT="$(cd "$MODEL_ROOT_INPUT" && pwd)"

for part in "${PARTS[@]}"; do
  [ -d "$MODEL_ROOT/$part" ] || die "missing model directory: $MODEL_ROOT/$part"
done

choose_model() {
  local candidate
  for candidate in "$@"; do
    if [ -s "$MODEL_ROOT/$candidate" ]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  die "none of the model alternatives exists and is non-empty: $*"
}

ASR_MODEL="$(choose_model "$ASR_DIR/model.int8.onnx" "$ASR_DIR/model.onnx")"
TTS_MODEL="$(choose_model "$TTS_DIR/model.int8.onnx" "$TTS_DIR/model.onnx")"
REQUIRED=(
  "$ASR_MODEL"
  "$ASR_DIR/tokens.txt"
  "$MT_DIR/encoder_model.onnx"
  "$MT_DIR/decoder_model.onnx"
  "$MT_DIR/source.spm"
  "$MT_DIR/target.spm"
  "$MT_DIR/vocab.json"
  "$MT_DIR/mt_config.json"
  "$TTS_MODEL"
  "$TTS_DIR/tokens.txt"
  "$TTS_DIR/voices.bin"
  "espeak-ng-data/phontab"
  "espeak-ng-data/phondata"
  "espeak-ng-data/phonindex"
  "espeak-ng-data/$ESPEAK_DICT"
)

for relative_path in "${REQUIRED[@]}"; do
  [ -s "$MODEL_ROOT/$relative_path" ] ||
    die "required model file is missing or empty: $MODEL_ROOT/$relative_path"
done

for part in "${PARTS[@]}"; do
  empty_file="$(find "$MODEL_ROOT/$part" -type f -empty -print -quit)"
  [ -z "$empty_file" ] || die "refusing to push empty file: $empty_file"
  symlink="$(find "$MODEL_ROOT/$part" -type l -print -quit)"
  [ -z "$symlink" ] || die "refusing to archive symlink: $symlink"
done

# Build the verification set before touching the device. Paths with newlines or
# backslashes make the portable sha256sum manifest ambiguous, so reject them.
RELATIVE_FILES=()
TOTAL_BYTES=0
for part in "${PARTS[@]}"; do
  while IFS= read -r -d '' local_file; do
    relative_path="${local_file#"$MODEL_ROOT/"}"
    case "$relative_path" in
      *$'\n'*|*\\*) die "unsupported model path: $relative_path" ;;
    esac
    RELATIVE_FILES+=("$relative_path")
    file_size="$(stat -c '%s' "$local_file")"
    TOTAL_BYTES=$((TOTAL_BYTES + file_size))
  done < <(find "$MODEL_ROOT/$part" -type f -print0)
done
[ "${#RELATIVE_FILES[@]}" -gt 0 ] || die "no files selected"

DEVICE_STATE="$("$ADB_BIN" -s "$SERIAL" get-state 2>/dev/null || true)"
[ "$DEVICE_STATE" = "device" ] ||
  die "ADB device '$SERIAL' is not in device state (reported: ${DEVICE_STATE:-unavailable})"

PACKAGE_PATH="$(
  "$ADB_BIN" -s "$SERIAL" shell pm path "$PACKAGE" </dev/null 2>/dev/null |
    tr -d '\r' || true
)"
case "$PACKAGE_PATH" in
  package:*) ;;
  *) die "package $PACKAGE is not installed on device $SERIAL" ;;
esac

if ! "$ADB_BIN" -s "$SERIAL" shell run-as "$PACKAGE" true </dev/null >/dev/null 2>&1; then
  die "run-as $PACKAGE failed; install a debuggable APK before staging models"
fi
for remote_tool in tar sha256sum; do
  if ! "$ADB_BIN" -s "$SERIAL" shell run-as "$PACKAGE" \
    /system/bin/test -x "/system/bin/$remote_tool" </dev/null >/dev/null 2>&1; then
    die "device is missing executable /system/bin/$remote_tool"
  fi
done

printf 'direction:   %s\n' "$DIRECTION"
printf 'device:      %s\n' "$SERIAL"
printf 'package:     %s\n' "$PACKAGE"
printf 'source:      %s\n' "$MODEL_ROOT"
printf 'destination: /data/user/0/%s/%s\n' "$PACKAGE" "$REMOTE_ROOT"
printf 'payload:     %s files, %s bytes (~%s MiB)\n' \
  "${#RELATIVE_FILES[@]}" "$TOTAL_BYTES" "$((TOTAL_BYTES / 1048576))"

"$ADB_BIN" -s "$SERIAL" shell run-as "$PACKAGE" mkdir -p "$REMOTE_ROOT" </dev/null

echo "Streaming tar payload (no temporary archive and no remote delete)..."
tar -C "$MODEL_ROOT" -cf - -- "${PARTS[@]}" |
  "$ADB_BIN" -s "$SERIAL" shell -T run-as "$PACKAGE" \
    /system/bin/tar -xf - -C "$REMOTE_ROOT"

emit_checksum_manifest() {
  local relative_path digest_line digest
  for relative_path in "${RELATIVE_FILES[@]}"; do
    digest_line="$(sha256sum "$MODEL_ROOT/$relative_path")"
    digest="${digest_line%% *}"
    printf '%s  %s/%s\n' "$digest" "$REMOTE_ROOT" "$relative_path"
  done
}

echo "Verifying every staged file with SHA-256..."
if ! emit_checksum_manifest |
  "$ADB_BIN" -s "$SERIAL" shell -T run-as "$PACKAGE" \
    /system/bin/sha256sum -c -; then
  die "post-push checksum verification failed; do not initialize the HF backend"
fi

echo "Verified ${#RELATIVE_FILES[@]} files for $DIRECTION on $SERIAL."
echo "Existing files outside this direction were left untouched."
"$ADB_BIN" -s "$SERIAL" shell run-as "$PACKAGE" \
  /system/bin/du -sh "${PARTS[@]/#/$REMOTE_ROOT/}" </dev/null 2>/dev/null || true
