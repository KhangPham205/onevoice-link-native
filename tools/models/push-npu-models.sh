#!/usr/bin/env bash
# ============================================================================
# push-npu-models.sh — stage the automatic Hybrid bundle onto a device
# ----------------------------------------------------------------------------
# Copies QNN contexts plus the required host-side models into the layout the app
# expects. Hybrid defaults to dual-ASR confidence routing; SpeechFlow remains
# staged so `routingMode=LID` can be built for A/B comparison:
#
#   <filesDir>/models/npu/shared/speechflow_langid.tflite
#   <filesDir>/models/npu/shared/speechflow_{labels,contract}.json
#   <filesDir>/models/npu/vi-en/{asr_gipformer_encoder,mt_encoder,mt_decoder}.bin
#   <filesDir>/models/npu/vi-en/asr_gipformer_{decoder,joiner}.onnx
#   <filesDir>/models/npu/vi-en/asr_gipformer_{tokens,bpe,config}.*
#   <filesDir>/models/npu/en-vi/{asr_parakeet_ctc,mt_encoder,mt_decoder}.bin
#   <filesDir>/models/npu/en-vi/asr_parakeet_tokens.txt
#   <filesDir>/models/npu/en-vi/tts_vieneu/*       (VieNeu-TTS v3 Nano, default VI voice)
#   <filesDir>/models/npu/en-vi/tts_supertonic/*   (Supertonic-3, optional engine)
#
# Each direction also gets Marian host tokenizers, a whole-utterance CPU Kokoro
# model, voice/token files and exact eSpeak data. A complete CPU ASR+MT branch
# lives under `fallback/` and is loaded only after that direction's HTP path
# throws; recovery always restarts from the original bounded PCM utterance.
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
# The manifest describes only QNN context binaries. SpeechFlow LID, transducer
# decoder/joiner pieces and CPU Kokoro/ASR files must not be presented as HTP
# stages merely because this script deploys them beside the contexts.
#
# Usage: tools/models/push-npu-models.sh [auto|vi-en|en-vi|all] [adb-serial]
# ============================================================================
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BINS="$REPO/onevoice-npu-bins"
PKG="com.onevoice.link"
TMP="/data/local/tmp/onevoice_stage.part"
WHICH="${1:-auto}"
SERIAL="${2:-}"
ADB="${ADB:-adb}"

for command_name in tar sha256sum find stat awk grep; do
  command -v "$command_name" >/dev/null 2>&1 || {
    echo "!! required host command is missing: $command_name" >&2
    exit 1
  }
done

adb_cmd() {
  if [ -n "$SERIAL" ]; then
    "$ADB" -s "$SERIAL" "$@"
  else
    "$ADB" "$@"
  fi
}

# dest-relative-path <TAB> source-relative-path (relative to onevoice-npu-bins)
read -r -d '' SHARED <<'EOF' || true
shared/speechflow_langid.tflite	../models-npu-generated/lid/speechflow_langid.tflite
shared/speechflow_labels.json	../models-npu-generated/lid/speechflow_labels.json
shared/speechflow_contract.json	../models-npu-generated/lid/speechflow_contract.json
EOF

# en-vi/tts_tokens.txt is NOT the upstream Kokoro-Vietnamese token table.
# sherpa-onnx phonemizes with espeak-ng `vi`, which writes tones as digits
# (1/7 ngang, 2 huyền, 3->ɜ sắc, 4 hỏi, 5 ngã, 6 nặng) and đ as ɗ. None exist in
# the model's vocab, so sherpa silently dropped every tone. The tracked table
# aliases them to the vig2p tone arrows the model was trained on (ngã/nặng lose
# their glottal ʔ). Measured: TTS->GIPFormer round-trip CER 9.8% -> 0.0%.
read -r -d '' VI_EN <<'EOF' || true
vi-en/asr_gipformer_encoder.bin	asr/gipformer_65m_rnnt-bundle/bin/encoder.bin
vi-en/asr_gipformer_decoder.onnx	asr/gipformer_65m_rnnt-bundle/decode/decoder-epoch-35-avg-6.onnx
vi-en/asr_gipformer_joiner.onnx	asr/gipformer_65m_rnnt-bundle/decode/joiner-epoch-35-avg-6.onnx
vi-en/asr_gipformer_tokens.txt	asr/gipformer_65m_rnnt-bundle/decode/tokens.txt
vi-en/asr_gipformer_bpe.model	asr/gipformer_65m_rnnt-bundle/decode/bpe.model
vi-en/asr_gipformer_config.json	asr/gipformer_65m_rnnt-bundle/decode/config.json
vi-en/mt_encoder.bin	mt/opus_mt_vi_en__float_8gen2/encoder_float.bin
vi-en/mt_decoder.bin	mt/opus_mt_vi_en__float_8gen2/decoder_float.bin
vi-en/mt_source.spm	../models-hf/mt-vi-en/source.spm
vi-en/mt_target.spm	../models-hf/mt-vi-en/target.spm
vi-en/mt_vocab.json	../models-hf/mt-vi-en/vocab.json
vi-en/mt_config.json	../models-hf/mt-vi-en/mt_config.json
vi-en/tts_tokens.txt	../models-hf/tts-en/tokens.txt
vi-en/tts_voices.bin	../models-hf/tts-en/voices.bin
vi-en/tts_cpu.onnx	../models-hf/tts-en/model.onnx
vi-en/fallback/asr/model.onnx	../models-hf/asr-vi/model.int8.onnx
vi-en/fallback/asr/tokens.txt	../models-hf/asr-vi/tokens.txt
vi-en/fallback/mt/encoder_model.onnx	../models-hf/mt-vi-en/encoder_model.onnx
vi-en/fallback/mt/decoder_model.onnx	../models-hf/mt-vi-en/decoder_model.onnx
vi-en/fallback/mt/source.spm	../models-hf/mt-vi-en/source.spm
vi-en/fallback/mt/vocab.json	../models-hf/mt-vi-en/vocab.json
vi-en/fallback/mt/mt_config.json	../models-hf/mt-vi-en/mt_config.json
EOF

# en-vi/tts_vieneu/ is the default Vietnamese voice (VieNeu-TTS v3 Nano,
# Apache-2.0), produced by build-sea-g2p-android.sh + prepare-vieneu-nano.py.
# en-vi/tts_supertonic/ is the -PviTtsEngine=SUPERTONIC alternative:
# sherpa-onnx-supertonic-3-tts-int8-2026-05-11 from the k2-fsa/sherpa-onnx
# tts-models release (code MIT, weights OpenRAIL-M). Kokoro-VI, Supertonic and
# VieNeu are all staged so an A/B needs only an APK rebuild.
read -r -d '' EN_VI <<'EOF' || true
en-vi/asr_parakeet_ctc.bin	asr/parakeet_tdt_ctc_110m_en__sherpa_SM8550_10s/model.bin
en-vi/asr_parakeet_tokens.txt	asr/parakeet_tdt_ctc_110m_en__sherpa_SM8550_10s/tokens.txt
en-vi/mt_encoder.bin	mt/opus_mt_en_vi__float_8gen2/encoder_float.bin
en-vi/mt_decoder.bin	mt/opus_mt_en_vi__float_8gen2/decoder_float.bin
en-vi/mt_source.spm	../models-hf/mt-en-vi/source.spm
en-vi/mt_target.spm	../models-hf/mt-en-vi/target.spm
en-vi/mt_vocab.json	../models-hf/mt-en-vi/vocab.json
en-vi/mt_config.json	../models-hf/mt-en-vi/mt_config.json
en-vi/tts_tokens.txt	../tools/models/kokoro-vi-espeak-tokens.txt
en-vi/tts_voices.bin	../models-hf/tts-vi/voices.bin
en-vi/tts_cpu.onnx	../models-hf/tts-vi/model.onnx
en-vi/tts_supertonic/duration_predictor.int8.onnx	../models-hf/tts-vi-supertonic/duration_predictor.int8.onnx
en-vi/tts_supertonic/text_encoder.int8.onnx	../models-hf/tts-vi-supertonic/text_encoder.int8.onnx
en-vi/tts_supertonic/vector_estimator.int8.onnx	../models-hf/tts-vi-supertonic/vector_estimator.int8.onnx
en-vi/tts_supertonic/vocoder.int8.onnx	../models-hf/tts-vi-supertonic/vocoder.int8.onnx
en-vi/tts_supertonic/tts.json	../models-hf/tts-vi-supertonic/tts.json
en-vi/tts_supertonic/unicode_indexer.bin	../models-hf/tts-vi-supertonic/unicode_indexer.bin
en-vi/tts_supertonic/voice.bin	../models-hf/tts-vi-supertonic/voice.bin
en-vi/tts_vieneu/text_encoder.onnx	../models-vieneu/tts_vieneu/text_encoder.onnx
en-vi/tts_vieneu/duration_predictor.onnx	../models-vieneu/tts_vieneu/duration_predictor.onnx
en-vi/tts_vieneu/vector_estimator.onnx	../models-vieneu/tts_vieneu/vector_estimator.onnx
en-vi/tts_vieneu/codec_decoder.onnx	../models-vieneu/tts_vieneu/codec_decoder.onnx
en-vi/tts_vieneu/nano.json	../models-vieneu/tts_vieneu/nano.json
en-vi/tts_vieneu/null_spk.bin	../models-vieneu/tts_vieneu/null_spk.bin
en-vi/tts_vieneu/null_style.bin	../models-vieneu/tts_vieneu/null_style.bin
en-vi/tts_vieneu/voices.bin	../models-vieneu/tts_vieneu/voices.bin
en-vi/tts_vieneu/sea_g2p.bin	../models-vieneu/tts_vieneu/sea_g2p.bin
en-vi/fallback/asr/model.onnx	../models-hf/asr-en/model.int8.onnx
en-vi/fallback/asr/tokens.txt	../models-hf/asr-en/tokens.txt
en-vi/fallback/mt/encoder_model.onnx	../models-hf/mt-en-vi/encoder_model.onnx
en-vi/fallback/mt/decoder_model.onnx	../models-hf/mt-en-vi/decoder_model.onnx
en-vi/fallback/mt/source.spm	../models-hf/mt-en-vi/source.spm
en-vi/fallback/mt/vocab.json	../models-hf/mt-en-vi/vocab.json
en-vi/fallback/mt/mt_config.json	../models-hf/mt-en-vi/mt_config.json
EOF

STAGE_VI=0
STAGE_EN=0
case "$WHICH" in
  vi-en) PLAN="$SHARED"$'\n'"$VI_EN"; STAGE_VI=1 ;;
  en-vi) PLAN="$SHARED"$'\n'"$EN_VI"; STAGE_EN=1 ;;
  auto|all)
    PLAN="$SHARED"$'\n'"$VI_EN"$'\n'"$EN_VI"
    STAGE_VI=1
    STAGE_EN=1
    ;;
  *) echo "usage: $0 [auto|vi-en|en-vi|all] [adb-serial]" >&2; exit 2 ;;
esac

# Refuse a partial deployment before touching the phone. The converter writes
# the three shared files as one reviewed contract; a model without its exact
# labels/frontend metadata is not safe to use for LID A/B.
missing_sources=0
while IFS=$'\t' read -r dest src; do
  [ -n "${dest:-}" ] || continue
  local_file="$BINS/$src"
  if [ ! -s "$local_file" ]; then
    echo "  MISSING SOURCE  $src" >&2
    missing_sources=$((missing_sources + 1))
  fi
done <<< "$PLAN"
if [ "$missing_sources" -ne 0 ]; then
  echo "!! $missing_sources required Hybrid artifact(s) are absent." >&2
  echo "   VieNeu: tools/models/build-sea-g2p-android.sh, then prepare-vieneu-nano.py." >&2
  echo "   Generate the LID trio with tools/models/convert_speechflow_lid.py and prepare" >&2
  echo "   both direction bundles before retrying; the device was not modified." >&2
  exit 1
fi

ESPEAK_SRC="$REPO/models-hf/espeak-ng-data"
for required in phontab phondata phonindex en_dict vi_dict; do
  if [ ! -s "$ESPEAK_SRC/$required" ]; then
    echo "!! incomplete eSpeak data: $ESPEAK_SRC/$required" >&2
    exit 1
  fi
done
if find "$ESPEAK_SRC" -type f -empty -print -quit | grep -q .; then
  echo "!! eSpeak data contains an empty file" >&2
  exit 1
fi
if find "$ESPEAK_SRC" -type l -print -quit | grep -q .; then
  echo "!! refusing to archive symlinks from $ESPEAK_SRC" >&2
  exit 1
fi

# ---- Preflight: one device, the right chip, a debuggable install -----------
if [ "$(adb_cmd devices | grep -c '\sdevice$')" -ne 1 ]; then
  echo "!! need exactly one device in 'device' state:" >&2
  adb_cmd devices >&2
  exit 1
fi

SOC="$(adb_cmd shell getprop ro.soc.model </dev/null | tr -d '\r')"
BOARD="$(adb_cmd shell getprop ro.board.platform </dev/null | tr -d '\r')"
echo "device soc=$SOC board=$BOARD"
if [ "$SOC" != "SM8550" ]; then
  # These binaries are AOT-compiled for Hexagon v73. On any other HTP
  # generation contextCreateFromBinary fails — pushing 2 GB first is a waste.
  echo "!! expected SM8550 (Snapdragon 8 Gen 2 / Hexagon v73), got '$SOC'." >&2
  echo "   The .bin files will NOT load on this chip; re-export is required." >&2
  echo "   Set FORCE=1 to push anyway." >&2
  [ "${FORCE:-0}" = "1" ] || exit 1
fi

if ! adb_cmd shell run-as "$PKG" true </dev/null 2>/dev/null; then
  echo "!! run-as $PKG failed — install the debuggable build first." >&2
  exit 1
fi
for remote_tool in tar sha256sum; do
  if ! adb_cmd shell run-as "$PKG" \
      /system/bin/test -x "/system/bin/$remote_tool" </dev/null \
      >/dev/null 2>&1; then
    echo "!! device is missing /system/bin/$remote_tool" >&2
    exit 1
  fi
done

# run-as starts in the app's data dir (/data/user/0/<pkg>), NOT filesDir, so the
# relative path has to include files/.
ROOT="files/models/npu"
adb_cmd shell run-as "$PKG" mkdir -p \
  "$ROOT/shared" "$ROOT/vi-en" "$ROOT/en-vi" </dev/null

pushed=0
skipped=0
while IFS=$'\t' read -r dest src; do
  [ -n "${dest:-}" ] || continue
  local_file="$BINS/$src"
  local_size=$(stat -c%s "$local_file")
  local_sha=$(sha256sum "$local_file" | awk '{print $1}')

  # </dev/null on every adb call is load-bearing: adb reads stdin, and without
  # it the first push swallows the rest of $PLAN from this loop's heredoc.
  # Size alone is not enough for resumable deployment: a previous interrupted
  # copy can have the expected length but corrupt contents. Hash the published
  # file and skip it only when SHA-256 matches the source exactly.
  remote_sha=$(
    adb_cmd shell run-as "$PKG" sha256sum "$ROOT/$dest" </dev/null 2>/dev/null |
      awk '{print $1}' | tr -d '\r' || true
  )
  if [ "$remote_sha" = "$local_sha" ]; then
    printf '  = %-36s verified sha256=%s\n' "$dest" "${local_sha:0:12}"
    skipped=$((skipped + 1))
    continue
  fi

  printf '  → %-36s %s MB sha256=%s\n' \
    "$dest" "$((local_size / 1048576))" "${local_sha:0:12}"
  adb_cmd push "$local_file" "$TMP" >/dev/null </dev/null
  remote_part="$ROOT/$dest.part"
  remote_parent="${remote_part%/*}"
  adb_cmd shell run-as "$PKG" mkdir -p "$remote_parent" </dev/null
  adb_cmd shell run-as "$PKG" cp "$TMP" "$remote_part" </dev/null

  # Never expose a partial model under its final name. Verify the app-owned
  # .part copy, then rename it atomically within filesDir. Both source and
  # destination are on /data, so mv cannot degrade into copy+delete.
  part_sha=$(
    adb_cmd shell run-as "$PKG" sha256sum "$remote_part" </dev/null |
      awk '{print $1}' | tr -d '\r'
  )
  if [ "$part_sha" != "$local_sha" ]; then
    echo "!! SHA-256 mismatch for $dest: local=$local_sha device=$part_sha" >&2
    adb_cmd shell run-as "$PKG" rm -f "$remote_part" </dev/null || true
    adb_cmd shell rm -f "$TMP" </dev/null || true
    exit 1
  fi
  adb_cmd shell run-as "$PKG" mv "$remote_part" "$ROOT/$dest" </dev/null
  published_sha=$(
    adb_cmd shell run-as "$PKG" sha256sum "$ROOT/$dest" </dev/null |
      awk '{print $1}' | tr -d '\r'
  )
  if [ "$published_sha" != "$local_sha" ]; then
    echo "!! post-publish SHA-256 mismatch for $dest" >&2
    adb_cmd shell rm -f "$TMP" </dev/null || true
    exit 1
  fi
  # Free the shell-owned staging copy immediately so every large model needs at
  # most one temporary duplicate regardless of the complete bundle size.
  adb_cmd shell rm -f "$TMP" </dev/null
  pushed=$((pushed + 1))
done <<< "$PLAN"

emit_tree_checksums() {
  local source_root="$1"
  local remote_root="$2"
  local local_file relative digest_line digest
  while IFS= read -r -d '' local_file; do
    relative="${local_file#"$source_root/"}"
    case "$relative" in
      *$'\n'*|*\\*)
        echo "unsupported eSpeak path: $relative" >&2
        return 1
        ;;
    esac
    digest_line=$(sha256sum "$local_file")
    digest="${digest_line%% *}"
    printf '%s  %s/%s\n' "$digest" "$remote_root" "$relative"
  done < <(find "$source_root" -type f -print0)
}

stage_espeak() {
  local direction="$1"
  local final="$ROOT/$direction/espeak-ng-data"
  local part="$final.part"
  local previous="$final.previous"

  if emit_tree_checksums "$ESPEAK_SRC" "$final" |
      adb_cmd shell -T run-as "$PKG" sha256sum -c - \
        >/dev/null 2>&1; then
    printf '  = %-36s verified tree sha256\n' "$direction/espeak-ng-data"
    return
  fi

  printf '  → %-36s streaming + full SHA-256 verification\n' \
    "$direction/espeak-ng-data"
  adb_cmd shell run-as "$PKG" rm -rf "$part" "$previous" </dev/null
  adb_cmd shell run-as "$PKG" mkdir -p "$part" </dev/null
  tar -C "$ESPEAK_SRC" -cf - . |
    adb_cmd shell -T run-as "$PKG" tar -C "$part" -xf -
  emit_tree_checksums "$ESPEAK_SRC" "$part" |
    adb_cmd shell -T run-as "$PKG" sha256sum -c - >/dev/null

  # Publish only a fully verified tree. Renames remain inside filesDir's /data
  # filesystem; the final part->destination operation is atomic.
  if adb_cmd shell run-as "$PKG" test -d "$final" </dev/null; then
    adb_cmd shell run-as "$PKG" mv "$final" "$previous" </dev/null
  fi
  if ! adb_cmd shell run-as "$PKG" mv "$part" "$final" </dev/null; then
    if adb_cmd shell run-as "$PKG" test -d "$previous" </dev/null; then
      adb_cmd shell run-as "$PKG" mv "$previous" "$final" </dev/null
    fi
    echo "!! failed to publish $direction/espeak-ng-data" >&2
    exit 1
  fi
  adb_cmd shell run-as "$PKG" rm -rf "$previous" </dev/null
  emit_tree_checksums "$ESPEAK_SRC" "$final" |
    adb_cmd shell -T run-as "$PKG" sha256sum -c - >/dev/null
}

[ "$STAGE_VI" -eq 0 ] || stage_espeak vi-en
[ "$STAGE_EN" -eq 0 ] || stage_espeak en-vi

echo
echo "pushed=$pushed skipped=$skipped"
adb_cmd shell run-as "$PKG" du -sh \
  "$ROOT/shared" "$ROOT/vi-en" "$ROOT/en-vi" </dev/null 2>/dev/null || true
echo
echo "Now open OneVoice Link, load the Hybrid pipeline, then start automatic translation."
