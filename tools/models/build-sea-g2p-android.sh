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
NDK="${ANDROID_NDK_HOME:-$HOME/Android/Sdk/ndk/27.2.12479018}"
API=28
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT_LIB="$ROOT/android-app/app/src/main/jniLibs/arm64-v8a"
OUT_DATA="$ROOT/models-vieneu"
WORK="${WORK:-$(mktemp -d)}"

# The PyPI sdist carries both the crate source and sea_g2p.bin; the git tag
# does not ship the dictionary (it is attached to releases instead).
SDIST_URL="$(python3 -c "
import json, urllib.request
d = json.load(urllib.request.urlopen('https://pypi.org/pypi/sea-g2p/$VERSION/json'))
print([u['url'] for u in d['urls'] if u['filename'].endswith('.tar.gz')][0])")"
echo "sea-g2p $VERSION sdist: $SDIST_URL"
curl -sSL -o "$WORK/sea.tar.gz" "$SDIST_URL"
sha256sum "$WORK/sea.tar.gz"
tar xzf "$WORK/sea.tar.gz" -C "$WORK"
SRC="$WORK/sea_g2p-$VERSION"

export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android${API}-clang"
# Android 15+ loads native libraries on 16 KB-page devices only when their
# segments are 16 KB aligned, same rule as native-core's CMake flags.
export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384"
(cd "$SRC" && cargo build --release --no-default-features --features capi \
    --target aarch64-linux-android)

mkdir -p "$OUT_LIB" "$OUT_DATA"
cp "$SRC/target/aarch64-linux-android/release/libsea_g2p_rs.so" "$OUT_LIB/"
"$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" "$OUT_LIB/libsea_g2p_rs.so"
cp "$SRC/python/sea_g2p/sea_g2p.bin" "$OUT_DATA/"
ls -la "$OUT_LIB/libsea_g2p_rs.so" "$OUT_DATA/sea_g2p.bin"
