# `jniLibs/arm64-v8a/` — native libraries packaged into the APK

The APK's `lib/arm64-v8a/` directory is assembled from **two** sources:

1. **`libonevoice_core.so`** — built from `../../native-core/CMakeLists.txt` by the
   `externalNativeBuild` step. It links against the Qualcomm QNN backend, so its
   ELF `NEEDED` list references `libQnnSystem.so`, `libQnnHtp.so`,
   `libQnnHtpPrepare.so` and the HTP FastRPC stubs.

2. **Qualcomm QNN runtime `.so`** — *not* committed here. They are staged
   automatically by the `copyQnnRuntimeLibs` Gradle task (see
   [`../../../build.gradle.kts`](../../../build.gradle.kts)) from
   `native-core/third_party/qnn_sdk/lib/aarch64-android/` into
   `build/qnnJniLibs/arm64-v8a/`, which is registered as an extra `jniLibs`
   source dir. This keeps ~96 MB of vendored binaries out of git while still
   guaranteeing they land in the APK so `System.loadLibrary("onevoice_core")`
   resolves at runtime.

**This directory is still a live `jniLibs` source root.** Drop any additional
prebuilt `arm64-v8a` `.so` here (e.g. a signed Hexagon skel) and AGP will merge
it into the APK alongside the staged QNN libs.
