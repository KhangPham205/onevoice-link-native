#!/usr/bin/env bash
# Package the exact, default Hybrid model payload into portable archives.
#
# This intentionally omits experimental contexts, SpeechFlow LID (an A/B
# option) and Supertonic (an optional Vietnamese voice).  It includes each
# direction's CPU recovery path because the app uses it when a QNN stage fails.
#
# Usage:
#   tools/models/package-main-models.sh [output-directory]
#
# Output archives unpack directly below an Android app's filesDir:
#   tar --zstd -xf onevoice-main-models-vi-en.tar.zst -C <filesDir>
#   tar --zstd -xf onevoice-main-models-en-vi.tar.zst -C <filesDir>
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUTPUT_DIR="${1:-$REPO/dist/model-packs}"
STAGING_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/onevoice-model-pack.XXXXXX")"

cleanup() {
  rm -rf "$STAGING_ROOT"
}
trap cleanup EXIT

for command_name in tar zstd sha256sum find sort cp; do
  command -v "$command_name" >/dev/null 2>&1 || {
    echo "!! required host command is missing: $command_name" >&2
    exit 1
  }
done

if [ -e "$OUTPUT_DIR" ] && [ ! -d "$OUTPUT_DIR" ]; then
  echo "!! output path is not a directory: $OUTPUT_DIR" >&2
  exit 1
fi
mkdir -p "$OUTPUT_DIR"

install_file() {
  local source="$1"
  local destination="$2"
  [ -s "$source" ] || {
    echo "!! missing required model artifact: $source" >&2
    exit 1
  }
  mkdir -p "$(dirname "$destination")"
  cp -p "$source" "$destination"
}

install_espeak() {
  local destination="$1"
  local source="$REPO/models-hf/espeak-ng-data"
  local required
  for required in phontab phondata phonindex en_dict vi_dict; do
    [ -s "$source/$required" ] || {
      echo "!! incomplete eSpeak data: $source/$required" >&2
      exit 1
    }
  done
  if find "$source" -type l -print -quit | grep -q .; then
    echo "!! refusing to package symlinks from $source" >&2
    exit 1
  fi
  cp -a "$source" "$destination"
}

write_manifest() {
  local pack_root="$1"
  (
    cd "$pack_root"
    find models -type f -print0 | LC_ALL=C sort -z | xargs -0 sha256sum
  ) > "$pack_root/SHA256SUMS"
}

verify_manifest() {
  local pack_root="$1"
  (
    cd "$pack_root"
    sha256sum -c SHA256SUMS >/dev/null
  )
}

create_archive() {
  local name="$1"
  local pack_root="$2"
  local archive="$OUTPUT_DIR/$name.tar.zst"
  local checksum="$archive.sha256"
  local temporary="$archive.part"

  write_manifest "$pack_root"
  verify_manifest "$pack_root"
  rm -f "$temporary"
  tar --zstd --create --file "$temporary" \
    --directory "$pack_root" \
    --sort=name --mtime='@0' --owner=0 --group=0 --numeric-owner \
    SHA256SUMS models
  mv -f "$temporary" "$archive"
  (
    cd "$(dirname "$archive")"
    sha256sum "$(basename "$archive")" > "$(basename "$checksum")"
  )
  tar --zstd --list --file "$archive" >/dev/null
  printf '  created %s (%s)\n' "$archive" "$(du -h "$archive" | awk '{print $1}')"
}

# The files below mirror push-npu-models.sh's production paths, except for the
# explicitly non-default LID and Supertonic payloads noted above.
VI_EN="$STAGING_ROOT/vi-en"
install_file "$REPO/onevoice-npu-bins/asr/gipformer_65m_rnnt-bundle/bin/encoder.bin" "$VI_EN/models/npu/vi-en/asr_gipformer_encoder.bin"
install_file "$REPO/onevoice-npu-bins/asr/gipformer_65m_rnnt-bundle/decode/decoder-epoch-35-avg-6.onnx" "$VI_EN/models/npu/vi-en/asr_gipformer_decoder.onnx"
install_file "$REPO/onevoice-npu-bins/asr/gipformer_65m_rnnt-bundle/decode/joiner-epoch-35-avg-6.onnx" "$VI_EN/models/npu/vi-en/asr_gipformer_joiner.onnx"
install_file "$REPO/onevoice-npu-bins/asr/gipformer_65m_rnnt-bundle/decode/tokens.txt" "$VI_EN/models/npu/vi-en/asr_gipformer_tokens.txt"
install_file "$REPO/onevoice-npu-bins/asr/gipformer_65m_rnnt-bundle/decode/bpe.model" "$VI_EN/models/npu/vi-en/asr_gipformer_bpe.model"
install_file "$REPO/onevoice-npu-bins/asr/gipformer_65m_rnnt-bundle/decode/config.json" "$VI_EN/models/npu/vi-en/asr_gipformer_config.json"
install_file "$REPO/onevoice-npu-bins/mt/opus_mt_vi_en__float_8gen2/encoder_float.bin" "$VI_EN/models/npu/vi-en/mt_encoder.bin"
install_file "$REPO/onevoice-npu-bins/mt/opus_mt_vi_en__float_8gen2/decoder_float.bin" "$VI_EN/models/npu/vi-en/mt_decoder.bin"
install_file "$REPO/models-hf/mt-vi-en/source.spm" "$VI_EN/models/npu/vi-en/mt_source.spm"
install_file "$REPO/models-hf/mt-vi-en/target.spm" "$VI_EN/models/npu/vi-en/mt_target.spm"
install_file "$REPO/models-hf/mt-vi-en/vocab.json" "$VI_EN/models/npu/vi-en/mt_vocab.json"
install_file "$REPO/models-hf/mt-vi-en/mt_config.json" "$VI_EN/models/npu/vi-en/mt_config.json"
install_file "$REPO/models-hf/tts-en/tokens.txt" "$VI_EN/models/npu/vi-en/tts_tokens.txt"
install_file "$REPO/models-hf/tts-en/voices.bin" "$VI_EN/models/npu/vi-en/tts_voices.bin"
install_file "$REPO/models-hf/tts-en/model.onnx" "$VI_EN/models/npu/vi-en/tts_cpu.onnx"
install_file "$REPO/models-hf/asr-vi/model.int8.onnx" "$VI_EN/models/npu/vi-en/fallback/asr/model.onnx"
install_file "$REPO/models-hf/asr-vi/tokens.txt" "$VI_EN/models/npu/vi-en/fallback/asr/tokens.txt"
install_file "$REPO/models-hf/mt-vi-en/encoder_model.onnx" "$VI_EN/models/npu/vi-en/fallback/mt/encoder_model.onnx"
install_file "$REPO/models-hf/mt-vi-en/decoder_model.onnx" "$VI_EN/models/npu/vi-en/fallback/mt/decoder_model.onnx"
install_file "$REPO/models-hf/mt-vi-en/source.spm" "$VI_EN/models/npu/vi-en/fallback/mt/source.spm"
install_file "$REPO/models-hf/mt-vi-en/vocab.json" "$VI_EN/models/npu/vi-en/fallback/mt/vocab.json"
install_file "$REPO/models-hf/mt-vi-en/mt_config.json" "$VI_EN/models/npu/vi-en/fallback/mt/mt_config.json"
install_espeak "$VI_EN/models/npu/vi-en/espeak-ng-data"
create_archive onevoice-main-models-vi-en "$VI_EN"

EN_VI="$STAGING_ROOT/en-vi"
install_file "$REPO/onevoice-npu-bins/asr/parakeet_tdt_ctc_110m_en__sherpa_SM8550_10s/model.bin" "$EN_VI/models/npu/en-vi/asr_parakeet_ctc.bin"
install_file "$REPO/onevoice-npu-bins/asr/parakeet_tdt_ctc_110m_en__sherpa_SM8550_10s/tokens.txt" "$EN_VI/models/npu/en-vi/asr_parakeet_tokens.txt"
install_file "$REPO/onevoice-npu-bins/mt/opus_mt_en_vi__float_8gen2/encoder_float.bin" "$EN_VI/models/npu/en-vi/mt_encoder.bin"
install_file "$REPO/onevoice-npu-bins/mt/opus_mt_en_vi__float_8gen2/decoder_float.bin" "$EN_VI/models/npu/en-vi/mt_decoder.bin"
install_file "$REPO/models-hf/mt-en-vi/source.spm" "$EN_VI/models/npu/en-vi/mt_source.spm"
install_file "$REPO/models-hf/mt-en-vi/target.spm" "$EN_VI/models/npu/en-vi/mt_target.spm"
install_file "$REPO/models-hf/mt-en-vi/vocab.json" "$EN_VI/models/npu/en-vi/mt_vocab.json"
install_file "$REPO/models-hf/mt-en-vi/mt_config.json" "$EN_VI/models/npu/en-vi/mt_config.json"
install_file "$REPO/tools/models/kokoro-vi-espeak-tokens.txt" "$EN_VI/models/npu/en-vi/tts_tokens.txt"
install_file "$REPO/models-hf/tts-vi/voices.bin" "$EN_VI/models/npu/en-vi/tts_voices.bin"
install_file "$REPO/models-hf/tts-vi/model.onnx" "$EN_VI/models/npu/en-vi/tts_cpu.onnx"
install_file "$REPO/models-vieneu/tts_vieneu/text_encoder.onnx" "$EN_VI/models/npu/en-vi/tts_vieneu/text_encoder.onnx"
install_file "$REPO/models-vieneu/tts_vieneu/duration_predictor.onnx" "$EN_VI/models/npu/en-vi/tts_vieneu/duration_predictor.onnx"
install_file "$REPO/models-vieneu/tts_vieneu/vector_estimator.onnx" "$EN_VI/models/npu/en-vi/tts_vieneu/vector_estimator.onnx"
install_file "$REPO/models-vieneu/tts_vieneu/codec_decoder.onnx" "$EN_VI/models/npu/en-vi/tts_vieneu/codec_decoder.onnx"
install_file "$REPO/models-vieneu/tts_vieneu/nano.json" "$EN_VI/models/npu/en-vi/tts_vieneu/nano.json"
install_file "$REPO/models-vieneu/tts_vieneu/null_spk.bin" "$EN_VI/models/npu/en-vi/tts_vieneu/null_spk.bin"
install_file "$REPO/models-vieneu/tts_vieneu/null_style.bin" "$EN_VI/models/npu/en-vi/tts_vieneu/null_style.bin"
install_file "$REPO/models-vieneu/tts_vieneu/voices.bin" "$EN_VI/models/npu/en-vi/tts_vieneu/voices.bin"
install_file "$REPO/models-vieneu/tts_vieneu/sea_g2p.bin" "$EN_VI/models/npu/en-vi/tts_vieneu/sea_g2p.bin"
install_file "$REPO/models-hf/asr-en/model.int8.onnx" "$EN_VI/models/npu/en-vi/fallback/asr/model.onnx"
install_file "$REPO/models-hf/asr-en/tokens.txt" "$EN_VI/models/npu/en-vi/fallback/asr/tokens.txt"
install_file "$REPO/models-hf/mt-en-vi/encoder_model.onnx" "$EN_VI/models/npu/en-vi/fallback/mt/encoder_model.onnx"
install_file "$REPO/models-hf/mt-en-vi/decoder_model.onnx" "$EN_VI/models/npu/en-vi/fallback/mt/decoder_model.onnx"
install_file "$REPO/models-hf/mt-en-vi/source.spm" "$EN_VI/models/npu/en-vi/fallback/mt/source.spm"
install_file "$REPO/models-hf/mt-en-vi/vocab.json" "$EN_VI/models/npu/en-vi/fallback/mt/vocab.json"
install_file "$REPO/models-hf/mt-en-vi/mt_config.json" "$EN_VI/models/npu/en-vi/fallback/mt/mt_config.json"
install_espeak "$EN_VI/models/npu/en-vi/espeak-ng-data"
create_archive onevoice-main-models-en-vi "$EN_VI"

echo "Model packages are ready in: $OUTPUT_DIR"
