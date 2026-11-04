# Building OneVoice Link

## Toolchain

| Tool | Version | Where it is pinned |
|---|---|---|
| Android Gradle Plugin | 9.2.1 | `gradle/libs.versions.toml` |
| Gradle | 9.4.1 | wrapper; configuration cache and build cache on (`gradle.properties`) |
| Kotlin | 2.2.10 | Compose compiler plugin; compiled in-process |
| Android SDK | `compileSdk 36` (minor 1), `targetSdk 36`, `minSdk 28` | `app/build.gradle.kts` |
| NDK | 27.2.12479018 | `ndkVersion`; also the default in `build-sea-g2p-android.sh` |
| CMake | 3.22.1 | matches `cmake_minimum_required` in `native-core/` |
| JDK | 21, pinned via `JAVA_HOME` | Kotlin/Java bytecode target is Java 11 |

Target ABI is **`arm64-v8a` only** (Snapdragon 8 Gen 2, Hexagon v73). No other
ABI is built.

## Out-of-repo build inputs

None of these are committed (large or license-restricted); all are git-ignored.

| Input | Location | How to get it | Without it |
|---|---|---|---|
| QAIRT/QNN SDK | `native-core/third_party/qnn_sdk/` with `include/QNN/` and `lib/aarch64-android/` directly under it | extract the SDK; no extra version folder | native core compiles QNN calls as logged stubs (`#if __has_include(<QnnInterface.h>)`), but `copyQnnRuntimeLibs` (a `preBuild` dependency) fails the Gradle build |
| Unsigned v73 skeleton | `app/src/main/assets/dsp/libQnnHtpV73Skel.so` | copy `qnn_sdk/lib/hexagon-v73/unsigned/libQnnHtpV73Skel.so` | runtime init fails: `NpuEngine` cannot extract it to `filesDir/dsp/unsigned/` |
| sherpa-onnx AAR 1.13.4 (static-link ORT) | `app/libs/sherpa-onnx-static-link-onnxruntime-1.13.4.aar` | `tools/models/fetch-sherpa-assets.ps1` | dependency resolution fails |
| `libsea_g2p_rs.so` (VieNeu phonemizer) | `app/src/main/jniLibs/arm64-v8a/` | `tools/models/build-sea-g2p-android.sh` (Rust `aarch64-linux-android` target + NDK) | with the default `viTtsEngine=VIENEU`, `merge*JniLibFolders` fails with a clear message; unit tests and `-PviTtsEngine=KOKORO`/`SUPERTONIC` builds do not need it |

Model binaries are never in the APK. Stage them with
`tools/models/push-npu-models.sh` (debuggable build required for `run-as`); see
[NPU_DEPLOY.md](NPU_DEPLOY.md).

## Build

```bash
cd android-app
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
  ./gradlew --no-daemon --no-parallel --max-workers=1 :app:assembleDebug
./gradlew :app:installDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:test --tests '*ExampleUnitTest*'
```

The debug APK lands in `app/build/outputs/apk/debug/app-debug.apk`. It is large
mainly because of the packaged QNN HTP runtime (`libQnnHtpPrepare.so`).

## Runtime-behavior Gradle properties

Defaults live in `android-app/gradle.properties` and are embedded in
`BuildConfig`, so every APK records its routing/TTS policy. Override per build
with `-P<name>=<value>`. Do not hide threshold defaults in decoder code.

```bash
-ProutingMode=DUAL_ASR                 # DUAL_ASR (default) or LID (SpeechFlow A/B)
-PdualAsrMarginThreshold=0.15          # provisional natural-log-prob/token margin
-PdualAsrFloorThreshold=-5.0           # provisional per-branch acceptance floor
-PdualAsrEnOffset=-0.08                # EN (CTC) score calibration offset
-PdualAsrPriorWeight=0.15              # soft turn prior, nats/token (>= margin = hard prior)
-PkokoroThreads=4                      # ORT threads per Kokoro session
-PvadMode=SILERO                       # SILERO or legacy ENERGY RMS gate
-PviTtsEngine=VIENEU                   # Vietnamese speech: VIENEU (default), KOKORO or SUPERTONIC
-PvieneuVoice=minh_quan -PvieneuSteps=16 -PvieneuCfg=3.0 -PvieneuSeed=1234
-PvieneuSpeed=1.0 -PvieneuThreads=4
-PsupertonicVoice=F1 -PsupertonicSteps=5 -PsupertonicSpeed=1.15 -PsupertonicThreads=4
-PqnnSignedSkel=/secure/path/libQnnHtpV73Skel.so
-PqnnPdMode=auto                       # auto (default), signed or unsigned
```

`build.gradle.kts` has its own fallbacks for some of these (for example
`kokoroThreads` 2, `supertonicSpeed` 1.05, `dualAsrEnOffset` 0.0), but the
values above from `gradle.properties` are what a normal build uses. Signed
skeleton handling is described in [QNN_SIGNED_DSP.md](QNN_SIGNED_DSP.md).

## How Gradle drives the native build

`app/build.gradle.kts` binds `../../native-core/CMakeLists.txt`
(`externalNativeBuild`, CMake 3.22.1) with `-std=c++20` and
`-DANDROID_STL=c++_shared`. Debug native code uses `-O0 -g`; release uses
`-O3 -flto`. `CMakeLists.txt` adds 16 KB ELF page alignment
(`-z max-page-size=16384`, required on Android 15+ 16 KB-page devices),
`--gc-sections`, hidden symbol visibility and `-march=armv8.2-a+dotprod+crc+crypto`.

CMake builds two shared libraries:

- `libonevoice_core.so`: JNI (`main_jni.cpp`), `QnnRuntime`, ASR/LID frontends,
  thermal probe. It has **no** QNN `DT_NEEDED` entries: QNN is `dlopen`'d only
  after `ADSP_LIBRARY_PATH` is set (see [QNN_INTEGRATION.md](QNN_INTEGRATION.md)).
  The Oboe subtree and `audio_engine.cpp` still compile into it, but no JNI
  entry reaches them; Hybrid audio is Kotlin `AudioRecord`/`AudioTrack`.
- `libsea_g2p_jni.so`: a small shim that `dlopen`s `libsea_g2p_rs.so`, kept
  separate so the core never relinks when the Rust artifact changes and so
  Kokoro/Supertonic builds load without it.

Gradle tasks around the native build:

- `copyQnnRuntimeLibs` stages `libQnnSystem.so`, `libQnnHtp.so`,
  `libQnnHtpPrepare.so` and `libQnnHtpV*Stub.so` from the SDK into
  `build/qnnJniLibs/arm64-v8a/`, an extra `jniLibs` source dir. It fails the
  build if the SDK directory is missing.
- `prepareQnnSignedDspAssets` stages an optional `-PqnnSignedSkel` file as
  asset `dsp/signed/libQnnHtpV73Skel.so`, and empties itself otherwise.
- `packaging.jniLibs.useLegacyPackaging = false` keeps `.so` files uncompressed
  and page-aligned in the APK; `.so` assets are also stored uncompressed.

## Build environment gotchas

- **Pin the JDK.** Gradle may otherwise pick a VS Code extension JRE without
  `jlink`. Use `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`.
- VS Code Gradle/Java extensions hold Gradle locks and auto-restart when
  killed. Disable them, build in Android Studio, or isolate with
  `GRADLE_USER_HOME=/tmp/gradle-isolated`.
- `gradle.properties` caps Gradle at a 1 GiB heap, one worker, no parallel
  execution and in-process Kotlin. Do not raise these casually: a 4 GiB Gradle
  plus 2 GiB Kotlin daemon got the development laptop OOM-killed.
- Do not delete `.cxx` or run `clean` routinely. Prefer incremental builds; a
  clean LTO link may need 3–4 GB free RAM.
- Open the Android Studio project at `android-app/`, not the repo root.
- Configuration cache: task action closures must capture plain locals (`File`,
  `String`), not script-level `val`s, or serialization fails. AGP 9 also
  rejects a lazy `Provider` in `jniLibs.srcDir`, hence the eager `File` roots.

## Verifying a build

```bash
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep -E 'lib/arm64-v8a/.*\.so|assets/dsp'
```

Expect `libonevoice_core.so`, `libsea_g2p_jni.so`, `libsea_g2p_rs.so` (VIENEU
builds), `libc++_shared.so`, `libQnnSystem.so`, `libQnnHtp.so`,
`libQnnHtpPrepare.so`, the `libQnnHtpV*Stub.so` family, and
`assets/dsp/libQnnHtpV73Skel.so`.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `QNN SDK android libs not found` | SDK not vendored | Extract it under `native-core/third_party/qnn_sdk/`. |
| `viTtsEngine=VIENEU needs .../libsea_g2p_rs.so` | Rust artifact not built | Run `tools/models/build-sea-g2p-android.sh`, or build with `-PviTtsEngine=KOKORO`. |
| `Failed to extract dsp/libQnnHtpV73Skel.so` at runtime | Unsigned skeleton asset missing | Copy it from `qnn_sdk/lib/hexagon-v73/unsigned/`. |
| QNN `0x36b1` / 14001 at context load | Usually FastRPC transport, not signing | See [QNN_INTEGRATION.md](QNN_INTEGRATION.md#failure-diagnosis). |
| Gradle picks a JRE without `jlink` | IDE-bundled JRE | Pin `JAVA_HOME` to JDK 21. |
| Laptop freezes or OOM-kills during build | Raised Gradle/Kotlin heaps, or clean LTO link | Restore `gradle.properties` caps; build incrementally. |
| `command not found` running `./gradlew` | Wrong working directory | Run from `android-app/`. |
