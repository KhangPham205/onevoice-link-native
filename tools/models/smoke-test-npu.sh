#!/usr/bin/env bash
# ============================================================================
# smoke-test-npu.sh — HANDOFF.md §7 step A1, plus the uid comparison
# ----------------------------------------------------------------------------
# Loads ONE context binary with Qualcomm's own qnn-net-run, first as the `shell`
# user and then as the app's uid. Running it both ways is the point: it splits
# "our JNI is wrong" from "this device will not open the DSP for an app".
#
# Measured on a retail SM-F731B (SM8550 / Hexagon v73, One UI, Android 16):
#   shell   -> Finished Executing Graphs, three output tensors, correct sizes
#   app uid -> Device Creation failure
# Same binary, same libs, same unsigned skel. See docs/NPU_DEPLOY.md.
#
# Usage: tools/models/smoke-test-npu.sh
# ============================================================================
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SDK="$REPO/native-core/third_party/qnn_sdk"
ADB="${ADB:-adb}"
PKG="com.onevoice.link"
D=/data/local/tmp/qnn
WORK="${TMPDIR:-/tmp}/onevoice-smoke"

# Smallest deploy binary with a simple, stateless contract:
#   input_ids int32[1,512], ref_s float32[1,256]
MODEL="$REPO/onevoice-npu-bins/tts/kokoro_en__encoder_8gen2/encoder.bin"

command -v python3 >/dev/null || { echo "!! python3 required" >&2; exit 1; }
[ -f "$MODEL" ] || { echo "!! missing $MODEL" >&2; exit 1; }

echo "=== staging QAIRT runtime to $D ==="
$ADB shell mkdir -p $D </dev/null
for f in \
  bin/aarch64-android/qnn-net-run \
  lib/aarch64-android/libQnnHtp.so \
  lib/aarch64-android/libQnnHtpV73Stub.so \
  lib/aarch64-android/libQnnHtpPrepare.so \
  lib/aarch64-android/libQnnSystem.so \
  lib/hexagon-v73/unsigned/libQnnHtpV73Skel.so ; do
  $ADB push "$SDK/$f" "$D/" >/dev/null </dev/null || exit 1
done
$ADB shell chmod 755 $D/qnn-net-run </dev/null

echo "=== generating inputs ==="
mkdir -p "$WORK"
python3 - "$WORK" <<'PY'
import struct, sys, os
w = sys.argv[1]
open(os.path.join(w, "input_ids.raw"), "wb").write(b"\x00" * (512 * 4))
open(os.path.join(w, "ref_s.raw"), "wb").write(struct.pack("<256f", *([0.1] * 256)))
open(os.path.join(w, "input_list.txt"), "w").write("input_ids.raw ref_s.raw\n")
PY
$ADB push "$WORK/input_ids.raw" "$WORK/ref_s.raw" "$WORK/input_list.txt" "$D/" >/dev/null </dev/null
$ADB push "$MODEL" "$D/model.bin" >/dev/null </dev/null

run_line='LD_LIBRARY_PATH=$PWD ADSP_LIBRARY_PATH=$PWD ./qnn-net-run --backend libQnnHtp.so --retrieve_context model.bin --input_list input_list.txt --output_dir out'

echo
echo "=== A) as shell ==="
$ADB shell "cd $D && rm -rf out && $run_line 2>&1 | tail -8" </dev/null
$ADB shell "ls $D/out/Result_0/ 2>/dev/null || echo '(no output)'" </dev/null

echo
echo "=== B) as the app uid ($PKG) ==="
if ! $ADB shell run-as "$PKG" true </dev/null 2>/dev/null; then
  echo "  (skipped: install the debuggable build to compare uids)"
  exit 0
fi
$ADB shell run-as "$PKG" mkdir -p files/qnntest </dev/null
for f in qnn-net-run libQnnHtp.so libQnnHtpV73Stub.so libQnnHtpPrepare.so \
         libQnnSystem.so libQnnHtpV73Skel.so model.bin \
         input_ids.raw ref_s.raw input_list.txt ; do
  $ADB shell run-as "$PKG" cp "$D/$f" files/qnntest/ </dev/null
done
$ADB shell run-as "$PKG" chmod 755 files/qnntest/qnn-net-run </dev/null
$ADB shell run-as "$PKG" sh -c "'cd files/qnntest && rm -rf out && $run_line 2>&1 | tail -8'" </dev/null
$ADB shell run-as "$PKG" sh -c "'ls files/qnntest/out/Result_0/ 2>/dev/null || echo \"(no output)\"'" </dev/null

echo
echo "Cleanup:  adb shell rm -rf $D ; adb shell run-as $PKG rm -rf files/qnntest"
