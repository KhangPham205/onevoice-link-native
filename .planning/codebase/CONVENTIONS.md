# Coding Conventions

**Analysis Date:** 2026-11-02

## Naming Patterns

**Files:**
- Use one Kotlin type or a closely related group per PascalCase `.kt` file, for example `DualAsrRouter.kt`, `HalfDuplexCoordinator.kt`, and `DashboardScreen.kt`.
- Keep C++ interface/implementation pairs in `native-core/include/<name>.h` and `native-core/src/<name>.cpp`, such as `qnn_runtime.h` and `qnn_runtime.cpp`.
- Test file names are the production type plus `Test`, co-located by package under `android-app/app/src/test/java/`, for example `core/DualAsrRouterTest.kt`.

**Functions and variables:**
- Kotlin uses lower camel case for functions and properties; types, `enum` values, and Compose functions use UpperCamelCase. Constants at file/class scope use upper snake case (`MAX_SEGMENT_SAMPLES`, `HALF_DUPLEX_TAG`) in `core/NpuEngine.kt`.
- C++ uses `snake_case` in local APIs/fields where inherited from the native core and UpperCamelCase types such as `QnnRuntime` and `ModelLoadResult` in `native-core/include/qnn_runtime.h`.
- Prefer expressive domain names (`forcedBoundary`, `priorExpected`, `normalizedMargin`) over generic values.

**Types:**
- Model immutable state as Kotlin `data class` and bounded alternatives as `enum class`, as in `core/DualAsrRouter.kt` and `core/HalfDuplexCoordinator.kt`.
- Keep implementation-only Kotlin APIs `internal`; expose an `EngineBackend` boundary instead of exposing the whole QNN implementation to UI (`core/EngineBackend.kt`, `core/NpuEngine.kt`).

## Code Style

**Formatting:**
- Kotlin official code style is configured by `android-app/gradle.properties` (`kotlin.code.style=official`). There is no repository formatter configuration; preserve existing four-space indentation, trailing commas in multi-line declarations, and import-per-line layout.
- Native code targets C++20 and follows the locally established four-space indentation and opening-brace-on-declaration style in `native-core/CMakeLists.txt` and `native-core/src/*.cpp`.

**Linting:**
- No Detekt, ktlint, Android Lint task configuration, or C++ lint configuration is detected. Build correctness is currently the automated style gate.

## Import Organization

**Kotlin order:**
1. Android/AndroidX imports.
2. Project imports (`com.onevoice.link...`).
3. Java/Kotlin/JVM imports.
4. External-library imports.

Follow the closest neighboring file when adding imports because the order is pragmatic rather than tool-enforced; see `core/NpuEngine.kt` and `ui/screens/DashboardScreen.kt`.

**Path aliases:**
- Not used. Kotlin packages map directly to directories beneath `android-app/app/src/main/java/com/onevoice/link/`.

## Error Handling

**Patterns:**
- Validate impossible programmer/build states early with `require`, `check`, and constructor/init validation. Examples: build-property validation in `android-app/app/build.gradle.kts`; legal state edges in `core/HalfDuplexCoordinator.kt`; routing-input validation in `core/RoutingConfig.kt`.
- Model expected runtime outcomes as nullable values or result/status objects instead of throwing across live audio paths. `core/DualAsrRouter.kt` returns a nullable direction; `core/NpuEngine.kt` publishes `StageStatus` and errors.
- Fail closed on ambiguous audio/routing/inference conditions. Preserve that behavior when extending `core/AutoHybridPipeline.kt`, `core/NpuEngine.kt`, or routing code.
- Use `try/finally` for resource lifecycle. The instrumented smoke test in `androidTest/.../NpuPipelineInstrumentedTest.kt` always calls `engine.destroy()`.

## Logging

**Framework:** Android `Log` for Kotlin and Android native log APIs for C++.

**Patterns:**
- Use component-specific tags (`OneVoiceNpu`, `OneVoiceHalfDuplex`, `OneVoiceRouting`, `OneVoiceVad`) declared near the top of `core/NpuEngine.kt`.
- Log operational stage/routing measurements, never replace an error return/status with logging alone. Keep real-time paths bounded; do not add verbose per-sample logging.

## Comments and Documentation

- Use KDoc/block comments to explain hardware boundaries, audio-safety invariants, measurement rationale, and non-obvious thresholds. `core/DualAsrRouter.kt`, `core/HalfDuplexCoordinator.kt`, and `native-core/include/qnn_runtime.h` are the reference style.
- Prefer comments that state an invariant or why a constraint exists over comments that paraphrase code.
- Preserve source-of-truth constraints in code comments when changing QNN, JNI, or half-duplex behavior; several general docs are older than current source.

## Function and Module Design

- Keep pure, deterministic algorithms separated and directly unit-testable: `core/DualAsrRouter.kt`, `core/UtteranceSegmenter.kt`, `core/AudioResampler.kt`, and `core/TtsSilenceTrim.kt` are reference patterns.
- Inject small seams for nondeterminism where needed. `HalfDuplexCoordinator` accepts a clock and transition callback; tests supply a deterministic clock.
- Make state mutations synchronized/locked and document ownership. `DualAsrTurnRouter` uses `@Synchronized`; `HalfDuplexCoordinator` owns a `ReentrantLock` and condition.
- Maintain Kotlin/C++ ABI symmetry: change both `core/OneVoiceNative.kt` and `native-core/src/main_jni.cpp` together, with exact JNI argument order/types.
- Compose screens take state and event callbacks; keep stateless render functions previewable as in `ui/screens/DashboardScreen.kt` and keep lifecycle ownership in `MainActivity.kt`/`EngineController`.

## Module Design

- Place app-level lifecycle/permission setup in `MainActivity.kt`; core runtime behavior in `core/`; Bluetooth/device policy in `headset/`; pure on-device fallback ML helpers in `hf/`; Compose presentation in `ui/`.
- Avoid barrel exports; Kotlin imports concrete packages/classes directly.
- Keep native QNN headers SDK-agnostic: QNN handle types stay behind `QnnRuntime::State` in `native-core/src/qnn_runtime.cpp`, with public contracts in `native-core/include/qnn_runtime.h`.

---

*Convention analysis: 2026-11-02*
