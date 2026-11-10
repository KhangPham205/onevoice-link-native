#!/usr/bin/env bash
# Cross-compile sea-g2p (VieNeu-TTS's Vietnamese normaliser + G2P, Rust) for
# arm64-v8a and stage its dictionary next to the VieNeu model files.
#
# Output:
#   android-app/app/src/main/jniLibs/arm64-v8a/libsea_g2p_rs.so   (git-ignored)
#   models-vieneu/sea_g2p.bin                                       (63 MB dictionary)
#
# The C ABI feature drops PyO3, so the library has no Python runtime to find.
# The app dlopen()s it through a small JNI shim (native-core/src/sea_g2p_jni.cpp)
# and memory-maps sea_g2p.bin from the model directory.
#
# Requires: rustup target aarch64-linux-android, Android NDK, network access.
set -euo pipefail

VERSION="${SEA_G2P_VERSION:-0.10.0}"
NDK_VERSION=27.2.12479018
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  ANDROID_NDK_HOME="$HOME/Android/Sdk/ndk/$NDK_VERSION"
  # Android Studio's default SDK location on Windows.
  if [ ! -d "$ANDROID_NDK_HOME" ] && [ -n "${LOCALAPPDATA:-}" ]; then
    ANDROID_NDK_HOME="$LOCALAPPDATA/Android/Sdk/ndk/$NDK_VERSION"
  fi
fi
NDK="$ANDROID_NDK_HOME"
API=28
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT_LIB="$ROOT/android-app/app/src/main/jniLibs/arm64-v8a"
OUT_DATA="$ROOT/models-vieneu"
WORK="${WORK:-$(mktemp -d)}"

# Git Bash on Windows: the NDK ships windows-x86_64 tools (the clang wrapper is
# a .cmd), and cargo/rustc are native programs that need C:/ style paths.
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*)
    HOST_TAG=windows-x86_64; CLANG_SUFFIX=.cmd; EXE=.exe
    native_path() { cygpath -m "$1"; }
    ;;
  *)
    HOST_TAG=linux-x86_64; CLANG_SUFFIX=; EXE=
    native_path() { printf '%s' "$1"; }
    ;;
esac
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"
[ -d "$TOOLCHAIN" ] || { echo "!! NDK toolchain not found: $TOOLCHAIN" >&2; exit 1; }

# On Windows `python3` is often the Microsoft Store stub, which exists but
# cannot run anything; take the first interpreter that actually works.
PYTHON=
for candidate in python3 python py; do
  if "$candidate" -c 'import sys' >/dev/null 2>&1; then PYTHON="$candidate"; break; fi
done
[ -n "$PYTHON" ] || { echo "!! no working Python interpreter found" >&2; exit 1; }

# The PyPI sdist carries both the crate source and sea_g2p.bin; the git tag
# does not ship the dictionary (it is attached to releases instead).
SDIST_URL="$("$PYTHON" -c "
import json, urllib.request
d = json.load(urllib.request.urlopen('https://pypi.org/pypi/sea-g2p/$VERSION/json'))
print([u['url'] for u in d['urls'] if u['filename'].endswith('.tar.gz')][0])")"
echo "sea-g2p $VERSION sdist: $SDIST_URL"
curl -sSL -o "$WORK/sea.tar.gz" "$SDIST_URL"
sha256sum "$WORK/sea.tar.gz"
tar xzf "$WORK/sea.tar.gz" -C "$WORK"
SRC="$WORK/sea_g2p-$VERSION"

export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$(native_path "$TOOLCHAIN/aarch64-linux-android${API}-clang${CLANG_SUFFIX}")"
# Any C sources built by a crate's build script use the same NDK compiler.
export CC_aarch64_linux_android="$CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER"
export AR_aarch64_linux_android="$(native_path "$TOOLCHAIN/llvm-ar${EXE}")"
# Android 15+ loads native libraries on 16 KB-page devices only when their
# segments are 16 KB aligned, same rule as native-core's CMake flags.
export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384"
(cd "$SRC" && cargo build --release --no-default-features --features capi \
    --target aarch64-linux-android)

mkdir -p "$OUT_LIB" "$OUT_DATA"
cp "$SRC/target/aarch64-linux-android/release/libsea_g2p_rs.so" "$OUT_LIB/"
"$TOOLCHAIN/llvm-strip${EXE}" "$OUT_LIB/libsea_g2p_rs.so"
cp "$SRC/python/sea_g2p/sea_g2p.bin" "$OUT_DATA/"
ls -la "$OUT_LIB/libsea_g2p_rs.so" "$OUT_DATA/sea_g2p.bin"
