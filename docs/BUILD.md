# Building OneVoice Link

## Toolchain

| Tool | Version (verified) | Notes |
|---|---|---|
| Android Gradle Plugin | 9.2.1 | `libs.versions.toml` |
| Gradle | 9.4.1 | via wrapper; **configuration cache is enabled** (`gradle.properties`) |
| Kotlin | 2.2.10 | Compose compiler plugin |
| Android SDK Platform | 36 (`compileSdk 36.1`, `targetSdk 36`, `minSdk 28`) | |
| NDK | 26.x – 29.x | any installed side-by-side NDK works |
| CMake | 3.22.1 | must match `cmake_minimum_required` in the core |
| JDK | 17+ (tested on 23) | |

Target ABI is **`arm64-v8a` only** — this is a Snapdragon/Hexagon app; no other ABIs are built.

## One-time setup: vendor the QNN SDK

The Qualcomm AI Runtime (QNN) SDK is **not** committed — it is large and license-restricted (`native-core/third_party/qnn_sdk/` is git-ignored). Google Oboe, by contrast, **is** committed (git subtree) and builds from source.

Extract the QNN SDK so the following exist directly (no extra nested version folder):

```
native-core/third_party/qnn_sdk/include/QNN/          # QnnInterface.h, QnnGraph.h, …
native-core/third_party/qnn_sdk/lib/aarch64-android/  # libQnnHtp.so, libQnnSystem.so, stubs, …
```

> If your extraction produced a nested parent like `qnn-v2.x/…`, move its contents up so `include/QNN` and `lib/aarch64-android` sit directly under `qnn_sdk/`, or adjust `QNN_SDK_DIR` in `native-core/CMakeLists.txt`.

**Stub mode:** if the SDK is absent, `qnn_inference.cpp` compiles every QNN call as a logged stub (guarded by `#if __has_include(<QnnInterface.h>)`), so `libonevoice_core.so` still builds and the JNI/audio wiring can be exercised on any desktop or CI toolchain.

## Build

```bash
cd android-app
./gradlew :app:assembleDebug      # debug APK  → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug       # build + install on a connected device
```

The produced APK is large (~130 MB debug) because it bundles the QNN HTP runtime — most of it is `libQnnHtpPrepare.so` (~86 MB). See [QNN_INTEGRATION.md](QNN_INTEGRATION.md) for what gets packaged and why.

## How Gradle drives the native build

`android-app/app/build.gradle.kts` binds the pre-existing core and pins the toolchain:

```kotlin
android {
  defaultConfig {
    ndk { abiFilters.add("arm64-v8a") }        // single ABI
    externalNativeBuild {
      cmake {
        cppFlags("-std=c++20", "-O3", "-flto")   // compile-time optimization
        arguments("-DANDROID_STL=c++_shared")    // one shared libc++ across .so boundaries
      }
    }
  }
  externalNativeBuild {
    cmake {
      path = file("../../native-core/CMakeLists.txt")   // relative bind to the core
      version = "3.22.1"
    }
  }
}
```

The matching **link-time** `-flto` and the 16 KB page alignment live in `native-core/CMakeLists.txt` (`CMAKE_SHARED_LINKER_FLAGS`), so LTO is end-to-end and the core loads on 16 KB-page devices (Android 15+). The core is also tuned with `-march=armv8.2-a+dotprod+crc+crypto` and dead-code-stripped (`--gc-sections`, `strip -g` on Release).

## Verifying a build

```bash
# Confirm the core and the QNN runtime are packaged for arm64-v8a:
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep 'lib/arm64-v8a/.*\.so'
```

You should see `libonevoice_core.so`, `libc++_shared.so`, `libQnnSystem.so`, `libQnnHtp.so`, `libQnnHtpPrepare.so`, and the `libQnnHtpV*Stub.so` family.

## Troubleshooting

These are the concrete issues encountered during first bring-up and their fixes — keep them handy.

| Symptom | Cause | Fix |
|---|---|---|
| `You cannot add Provider instances to the Android SourceSet API` | AGP 9 rejects a lazy `Provider<Directory>` in `jniLibs.srcDir(...)` | Resolve to a plain `File` and wire the staging task's ordering explicitly (done in `build.gradle.kts`). |
| `cannot serialize Gradle script object references` (configuration cache) | A task action closure captured a script-level `val` | Copy the value into a local `File`/variable *before* the `doFirst {}` so the closure captures only serializable state. |
| `member reference type 'AudioStreamBuilder *' is a pointer; did you mean to use '->'?` | Oboe's fluent setters return `AudioStreamBuilder*`, not a reference | Chain with `->` after the first setter (done in `audio_engine.cpp`). |
| `no matching member function for call to 'openStream'` | `oboe::ManagedStream` is a `unique_ptr`; `openStream` takes `AudioStream**` or `shared_ptr&` | Hold streams as `std::shared_ptr<oboe::AudioStream>` (done in `audio_engine.h`). |
| `no member named 'backendCreate' in 'QnnInterface_t'` | QNN function pointers live under the versioned union accessor | Call through `api->QNN_INTERFACE_VER_NAME.<fn>(…)` (done in `qnn_inference.cpp`). |
| `UnsatisfiedLinkError` at runtime for a `libQnn*.so` | QNN runtime `.so` missing from the APK | Ensure the QNN SDK is vendored so `copyQnnRuntimeLibs` can stage the libs; see [QNN_INTEGRATION.md](QNN_INTEGRATION.md). |
| `command not found` running `./gradlew` | Wrong working directory | Run from `android-app/` (where the wrapper lives). |

## Clean rebuild

```bash
cd android-app
./gradlew clean
rm -rf app/.cxx app/build   # nuke cached CMake/ninja artifacts if the native build misbehaves
./gradlew :app:assembleDebug
```
