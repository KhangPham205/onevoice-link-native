# Testing Patterns

**Analysis Date:** 2026-11-02

## Test Framework

**Runner:**
- JVM unit tests: JUnit 4.13.2, declared through `android-app/gradle/libs.versions.toml` and run by the Android Gradle Plugin.
- Device/instrumentation tests: AndroidX JUnit 1.1.5 with `AndroidJUnitRunner`, configured in `android-app/app/build.gradle.kts`.
- UI test dependencies are available through Compose UI Test and Espresso, but no Compose interaction tests are currently present.

**Assertions:**
- Use `org.junit.Assert` static assertions (`assertEquals`, `assertTrue`, `assertFalse`, `assertNull`, `assertThrows`). See `core/DualAsrRouterTest.kt` and `core/UtteranceSegmenterTest.kt`.

**Run commands:**
```bash
cd android-app
./gradlew :app:testDebugUnitTest                         # JVM unit tests
./gradlew :app:test --tests '*DualAsrRouterTest*'         # one targeted JVM class
./gradlew :app:connectedDebugAndroidTest                  # connected-device instrumentation tests
./gradlew :app:assembleDebug                              # compile/package validation
```

No coverage-report task, watch mode, or CI pipeline is configured in the repository.

## Test File Organization

**Locations:**
- JVM tests mirror packages under `android-app/app/src/test/java/com/onevoice/link/`.
- Device tests mirror packages under `android-app/app/src/androidTest/java/com/onevoice/link/`.

**Naming:**
- One `ClassUnderTestTest.kt` class per focused behavior, for example `core/AudioResamplerTest.kt`, `core/HalfDuplexCoordinatorTest.kt`, and `headset/HeadsetRoutePlannerTest.kt`.

```text
android-app/app/src/
├── test/java/com/onevoice/link/
│   ├── core/                 # routing, VAD, segmentation, audio utility tests
│   └── headset/              # headset policy tests
└── androidTest/java/com/onevoice/link/
    └── NpuPipelineInstrumentedTest.kt  # engine/model smoke test
```

## Test Structure

**Suite organization:**
```kotlin
class DualAsrRouterTest {
    private val config = RoutingConfig(/* explicit fixture values */)

    @Test
    fun ambiguousFirstTurnFailsClosed() {
        val result = DualAsrTurnRouter(config).decide(-1.10, -1.20)

        assertNull(result.direction)
        assertEquals(RoutingDecisionReason.NO_PRIOR, result.reason)
    }
}
```

**Patterns:**
- Give each test an executable behavior name (`floorWinsBeforeMarginOrPrior`, `speechStartingInsideTheGuardKeepsItsHead`) rather than a generic `testX`; see `core/DualAsrRouterTest.kt` and `core/UtteranceSegmenterTest.kt`.
- Use small literal fixtures and local fake implementations. `UtteranceSegmenterTest.kt` defines a `FakeDetector` implementing `SpeechActivityDetector` and constructs deterministic PCM frame arrays.
- Test invariants and edge thresholds explicitly: equality at thresholds, forced boundaries, empty/invalid values, and fail-closed behavior are covered in `DualAsrRouterTest.kt`, `SpeechActivityDetectorTest.kt`, and `ParakeetCtcTest.kt`.
- Keep processing/routing algorithms free of Android dependencies so they remain JVM-testable.

## Mocking

**Framework:**
- No mocking library is declared. Use handwritten fakes/stubs and dependency injection points.

**Patterns:**
```kotlin
private class FakeDetector : SpeechActivityDetector {
    override fun isSpeech(frame: ShortArray, count: Int): Boolean = frame[0] != 0.toShort()
    override fun reset() { /* record observations when the assertion needs them */ }
}
```

- Prefer fakes for audio/VAD/model inputs and simple lambdas for clocks/callbacks over mocking implementation internals.
- Do not mock QNN execution for a test intended to validate deployed artifacts; use the real, opt-in instrumented smoke test instead.

## Fixtures and Factories

- Fixtures are local to each test class: small arrays, explicit routing configurations, and miniature headset objects. See `core/AudioResamplerTest.kt`, `core/ParakeetCtcTest.kt`, and `headset/HeadsetRoutePlannerTest.kt`.
- No shared fixture directory or factory framework is present. Introduce one only for duplicated, stable domain fixtures; keep audio fixtures short and deterministic.

## Test Types and Coverage

**Unit tests:**
- Well covered: routing policy/confidence (`core/DualAsrRouterTest.kt`, `core/RnntConfidenceTest.kt`, `core/ParakeetCtcTest.kt`), half-duplex state transitions (`core/HalfDuplexCoordinatorTest.kt`), utterance segmentation/VAD (`core/UtteranceSegmenterTest.kt`, `core/SpeechActivityDetectorTest.kt`), audio utilities (`core/AudioResamplerTest.kt`, `core/TtsSilenceTrimTest.kt`), CPU TTS policy (`core/CpuTtsEngineTest.kt`), and headset route planning (`headset/HeadsetRoutePlannerTest.kt`).

**Instrumentation tests:**
- `androidTest/.../NpuPipelineInstrumentedTest.kt` initializes `NpuEngine`, verifies six resident contexts, executes a Vietnamese smoke utterance, and requires nonblank translation plus generated audio. It needs a provisioned arm64-v8a Snapdragon device and staged model/QNN assets.
- `androidTest/.../ExampleInstrumentedTest.kt` is the default package-name sanity test.

**Not detected:**
- Native C++ unit-test target, JNI contract test, UI interaction test, hardware-free end-to-end test, coverage threshold, and CI automation.

## Test Guidance for New Work

- Add deterministic unit tests under the matching `src/test/...` package before changing routing, buffering, segmentation, sample transforms, or headsets.
- When behavior crosses Android/QNN/model boundaries, add or extend an instrumented test only if it can assert a stable device outcome; always destroy `NpuEngine` in `finally` as `NpuPipelineInstrumentedTest.kt` does.
- Test failure behavior as carefully as success behavior. In this app, ambiguous route/ASR results should result in no selected direction or a controlled error, never a guessed translation.

---

*Testing analysis: 2026-11-02*
