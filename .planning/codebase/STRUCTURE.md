# Codebase Structure

**Analysis Date:** 2026-11-02

## Directory Layout

```text
onevoice-link-native/
├── android-app/                       # Android Gradle project
│   └── app/src/main/
│       ├── java/com/onevoice/link/     # Kotlin production source
│       │   ├── core/                   # engine, routing, JNI facade, audio
│       │   ├── headset/                # device inventory/preferences/routing
│       │   ├── hf/                     # retained CPU reference/fallback pieces
│       │   └── ui/                     # Compose app, screens, components, theme
│       ├── assets/                     # generated NPU manifest and packaged DSP assets
│       ├── res/                        # Android strings, drawables, themes
│       └── AndroidManifest.xml         # Android declaration/permissions
├── native-core/                       # C++ shared library built by app CMake
│   ├── include/                        # SDK-agnostic native interfaces
│   ├── src/                            # JNI, QNN runtime, signal transforms
│   └── third_party/oboe/               # vendored Google Oboe source
├── tools/models/                       # artifact conversion, validation and adb deployment
├── docs/                               # build, architecture, QNN and experiment notes
├── models-*/                           # large model inputs/converted artifacts (not source logic)
├── onevoice-npu-bins/                  # QNN model deliverables (large/non-committed)
├── README.md                           # project overview and developer quick start
└── CLAUDE.md                           # current repository constraints and operating guidance
```

## Directory Purposes

**`android-app/`:**

- Purpose: the Android application and Gradle integration point for the native
  `libonevoice_core.so` library.
- Contains: Kotlin, Compose resources, assets, manifests and build scripts.
- Key files: `android-app/app/build.gradle.kts`,
  `android-app/app/src/main/AndroidManifest.xml`,
  `android-app/app/src/main/assets/npu_manifest.json`.

**`android-app/app/src/main/java/com/onevoice/link/core/`:**

- Purpose: core application behavior outside presentation: model context load,
  live turn orchestration, VAD, ASR routing, TTS, pipeline and JNI facade.
- Contains: one primary file per responsibility, e.g. `NpuEngine.kt`,
  `AutoHybridPipeline.kt`, `NpuPipeline.kt`, `OneVoiceNative.kt` and
  `HalfDuplexCoordinator.kt`.
- Key files: `EngineBackend.kt` is the UI/engine contract;
  `NpuManifest.kt` is the staged-model contract.

**`android-app/app/src/main/java/com/onevoice/link/headset/`:**

- Purpose: inspect connected Bluetooth/LE devices, persist preferences and
  describe output route capabilities without selecting source language.
- Contains: repository, models, resolver/planner and Auracast capability probe.
- Key files: `ConnectedHeadsetRepository.kt`, `HeadsetLanguagePreferences.kt`,
  `BluetoothSpeakerRouteResolver.kt`.

**`android-app/app/src/main/java/com/onevoice/link/hf/`:**

- Purpose: CPU/reference speech and translation components, including tokenizer
  and Marian translator.
- Contains: `HfEngineBackend.kt`, `MarianTranslator.kt`,
  `SentencePieceUnigram.kt`.
- Key files: do not expose `HfEngineBackend` as a production UI backend; CPU
  fallback is reached internally through `core/CpuFallbackBranch.kt`.

**`android-app/app/src/main/java/com/onevoice/link/ui/`:**

- Purpose: Compose presentation, navigation and UI-specific state.
- Contains: `OneVoiceApp.kt`, screens, reusable components and theme tokens.
- Key files: `ui/screens/DashboardState.kt` owns `EngineController`; add UI
  state transitions there instead of making composables call the engine.

**`native-core/include/`:**

- Purpose: C++ interfaces shared among first-party native source files.
- Contains: QNN runtime pimpl boundary, signal-transform declarations, thermal
  probe and legacy realtime-audio/ring-buffer types.
- Key files: `qnn_runtime.h`, `asr_frontend.h`, `kokoro_audio.h`.

**`native-core/src/`:**

- Purpose: C++ implementation, with a single JNI-owning translation unit.
- Contains: `main_jni.cpp`, `qnn_runtime.cpp`, `asr_frontend.cpp`,
  `kokoro_audio.cpp`, `thermal_probe.cpp` and legacy `audio_engine.cpp`.
- Key files: add Java/Kotlin ABI methods only to `main_jni.cpp` plus their exact
  declaration in `core/OneVoiceNative.kt`.

**`native-core/third_party/`:**

- Purpose: third-party native dependencies.
- Contains: committed Oboe source; locally vendored QNN SDK is expected under
  `native-core/third_party/qnn_sdk/` when building hardware support.
- Generated: no for Oboe; QNN SDK is external/vendor supplied.
- Committed: Oboe yes; QNN SDK no.

**`tools/models/`:**

- Purpose: conversion and deployment tools around external model artifacts.
- Contains: shell/PowerShell deployment scripts and Python model/manifest
  generators such as `gen_npu_manifest.py`.
- Key files: `push-npu-models.sh` stages complete device artifacts; regenerate
  `npu_manifest.json` from contexts rather than hand-editing graph metadata.

**`docs/`:**

- Purpose: build/deploy/QNN architecture context, incident records and proposal
  material. Treat first-party source and `CLAUDE.md` as the current runtime
  authority when document details disagree.
- Contains: `ARCHITECTURE.md`, `BUILD.md`, `NPU_DEPLOY.md`,
  `QNN_INTEGRATION.md`, `incidents/` and proposal evidence.

## Key File Locations

**Entry Points:**

- `android-app/app/src/main/java/com/onevoice/link/MainActivity.kt`: Android
  activity and composition root.
- `android-app/app/src/main/java/com/onevoice/link/ui/OneVoiceApp.kt`: app-level
  Compose navigation and Settings inventory subscription.
- `native-core/src/main_jni.cpp`: native shared-library/JNI entry points.
- `android-app/app/src/main/java/com/onevoice/link/core/BenchControlReceiver.kt`:
  optional benchmark broadcast entry point.

**Configuration:**

- `android-app/app/build.gradle.kts`: Android packaging, native CMake binding,
  BuildConfig options and dependencies.
- `android-app/gradle.properties`: constrained Gradle execution and default
  build-time routing/TTS/QNN tuning values.
- `android-app/app/src/main/assets/npu_manifest.json`: model graph and tensor
  contract generated from QNN context binaries.
- `native-core/CMakeLists.txt`: arm64 native build and conditional QNN/Oboe
  integration.
- `CLAUDE.md`: operational, runtime and hardware invariants.

**Core Logic:**

- `android-app/app/src/main/java/com/onevoice/link/core/NpuEngine.kt`: model
  lifecycle, workers, route verification and actual playback.
- `android-app/app/src/main/java/com/onevoice/link/core/AutoHybridPipeline.kt`:
  per-turn dual ASR/LID routing, MT/fallback/TTS delegation.
- `android-app/app/src/main/java/com/onevoice/link/core/NpuPipeline.kt`: tensor
  orchestration and per-direction inference host glue.
- `native-core/src/qnn_runtime.cpp`: QNN backend/context/graph lifecycle.

**Testing:**

- `android-app/app/src/test/`: local JVM/unit tests when present.
- `android-app/app/src/androidTest/`: connected-device/instrumentation coverage,
  including QNN graph smoke work when present.
- `native-core/third_party/oboe/tests/`: third-party Oboe tests; do not treat as
  app test coverage.

## Naming Conventions

**Files:**

- Kotlin production files use PascalCase object/class filenames, one main
  public responsibility per file: `NpuEngine.kt`, `DualAsrRouter.kt`,
  `TranslationCard.kt`.
- Kotlin packages mirror feature/layer names: `core`, `headset`, `hf`, `ui`,
  then `ui/components`, `ui/screens`, `ui/theme`.
- C++ headers and source use lower snake case paired names:
  `qnn_runtime.h` / `qnn_runtime.cpp`, `asr_frontend.h` /
  `asr_frontend.cpp`.

**Directories:**

- Use package/layer directories for Kotlin behavior, not generic `utils`.
- Keep native public interfaces in `native-core/include/`; implementation-only
  QNN details belong in `native-core/src/` behind the pimpl.

## Where to Add New Code

**New live-pipeline feature:**

- Primary orchestration: extend or add a focused `*.kt` file under
  `android-app/app/src/main/java/com/onevoice/link/core/`.
- Preserve `NpuEngine.kt` as owner of capture/playback/session lifecycle; make
  router/model stages collaborators rather than adding Compose dependencies.
- Tests: put pure policy/VAD/router tests under `android-app/app/src/test/` and
  hardware/QNN verification under `android-app/app/src/androidTest/`.

**New Compose component or screen:**

- Reusable implementation: `android-app/app/src/main/java/com/onevoice/link/ui/components/`.
- Screen composition: `android-app/app/src/main/java/com/onevoice/link/ui/screens/`.
- State/lifecycle operation: `ui/screens/DashboardState.kt` or an equivalent
  screen state holder; keep composables declarative.
- Theme token: `android-app/app/src/main/java/com/onevoice/link/ui/theme/`.

**New Android device/Bluetooth behavior:**

- Inventory, preference or physical route helper: add under
  `android-app/app/src/main/java/com/onevoice/link/headset/`.
- Route-choice policy independent of framework objects: place near
  `core/AudioRoute.kt` and keep it unit-testable.

**New QNN graph or tensor transform:**

- Kotlin host orchestration: `core/NpuPipeline.kt` and, when necessary,
  `core/NpuManifest.kt`.
- Native QNN behavior: `native-core/src/qnn_runtime.cpp`; keep SDK types out of
  `native-core/include/qnn_runtime.h`.
- New JNI method: update both `core/OneVoiceNative.kt` and
  `native-core/src/main_jni.cpp` in the same change.
- Artifact metadata: regenerate `app/src/main/assets/npu_manifest.json` through
  `tools/models/gen_npu_manifest.py`; do not invent graph names or dimensions.

**Shared helpers:**

- A helper coupled to a runtime concern belongs in its feature package (for
  example `TtsSilenceTrim.kt` in `core/`). Create a new package only for a
  distinct stable subsystem, not one-off utility functions.

## Special Directories

**`models-hf/`, `models-src/`, `models-npu-generated/`, `onevoice-npu-bins/`:**

- Purpose: large external source, converted or deployable model artifacts.
- Generated: partly; tools determine artifact source and conversion path.
- Committed: large/license-restricted contents are not normally committed.
- Rule: do not use these paths as an application runtime contract directly;
  deploy into the app-owned directory and load through `NpuManifest`.

**`android-app/app/build/`, `android-app/build/`, `android-app/.gradle/`,
`android-app/.cxx/`:**

- Purpose: Gradle/CMake build outputs and caches.
- Generated: yes.
- Committed: no.
- Rule: preserve incremental artifacts unless a targeted rebuild is necessary;
  native clean builds are resource-heavy.

**`.planning/codebase/`:**

- Purpose: generated codebase map for planning agents.
- Generated: yes, by codebase mapping.
- Committed: follow the repository's planning-artifact convention; it is not
  runtime application code.

---

*Structure analysis: 2026-11-02*
