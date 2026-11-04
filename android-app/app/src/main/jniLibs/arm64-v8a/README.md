# `jniLibs/arm64-v8a/`: native libraries packaged into the APK

The APK's `lib/arm64-v8a/` is assembled from three sources:

1. **CMake outputs** from `../../native-core/CMakeLists.txt`:
   `libonevoice_core.so` (JNI, `QnnRuntime`, ASR frontends) and
   `libsea_g2p_jni.so` (shim for sea-g2p), plus `libc++_shared.so`.
   The core has **no** QNN `DT_NEEDED` entries: QNN is `dlopen`'d only after
   `NpuEngine` sets `ADSP_LIBRARY_PATH`, which must happen before
   `libQnnHtp.so` first loads.

2. **QNN host runtime**, staged by the `copyQnnRuntimeLibs` Gradle task (see
   [`../../../build.gradle.kts`](../../../../build.gradle.kts)) from
   `native-core/third_party/qnn_sdk/lib/aarch64-android/` into
   `build/qnnJniLibs/arm64-v8a/`: `libQnnSystem.so`, `libQnnHtp.so`,
   `libQnnHtpPrepare.so` and `libQnnHtpV*Stub.so`. Nothing is committed.

3. **This directory**, a normal `jniLibs` source root. Its only expected
   binary is `libsea_g2p_rs.so` (git-ignored, like every `*.so` here), built by
   `tools/models/build-sea-g2p-android.sh` (Rust `aarch64-linux-android`
   target + NDK 27.2, 16 KB page alignment). The shim `dlopen`s it for
   VieNeu-TTS phonemization. With the default `-PviTtsEngine=VIENEU`, the
   `merge*JniLibFolders` task fails with a clear message if it is missing;
   unit tests and `KOKORO`/`SUPERTONIC` builds do not need it.

Do **not** put the Hexagon skeleton (`libQnnHtpV73Skel.so`) here: it is a DSP6
ELF, not an ARM64 library. It ships as an asset (`assets/dsp/` for the
unsigned skeleton, or `-PqnnSignedSkel` for a signed one) and is extracted to
`<filesDir>/dsp/{unsigned,signed}/` at runtime.
