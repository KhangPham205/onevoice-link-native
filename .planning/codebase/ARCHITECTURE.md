<!-- refreshed: 2026-11-02 -->
# Architecture

**Analysis Date:** 2026-11-02

## System Overview

```text
┌───────────────────────────────────────────────────────────────────┐
│ Compose UI / Activity                                               │
│ `android-app/app/src/main/java/com/onevoice/link/MainActivity.kt`  │
│ `.../ui/OneVoiceApp.kt`, `.../ui/screens/DashboardState.kt`        │
└────────────────────────────────┬──────────────────────────────────┘
                                 │ EngineBackend lifecycle/listeners
                                 ▼
┌───────────────────────────────────────────────────────────────────┐
│ Hybrid orchestration                                                │
│ `.../core/NpuEngine.kt`                                            │
│ AudioRecord → VAD → bounded queue → AudioTrack                     │
│ `.../core/HalfDuplexCoordinator.kt`                                │
└───────────┬───────────────────────────────┬───────────────────────┘
            │                               │
            ▼                               ▼
┌─────────────────────────┐    ┌────────────────────────────────────┐
│ Routing and model host   │    │ Android device routing              │
│ `AutoHybridPipeline.kt` │    │ `.../core/AudioRoute.kt`            │
│ `NpuPipeline.kt`        │    │ `.../headset/*.kt`                  │
│ `CpuFallbackBranch.kt`  │    └────────────────────────────────────┘
└───────────┬─────────────┘
            │ direct ByteBuffers / JNI
            ▼
┌───────────────────────────────────────────────────────────────────┐
│ C++ QNN boundary and runtime                                        │
│ `native-core/src/main_jni.cpp` → `qnn_runtime.cpp`                 │
│ native ASR frontend / Kokoro signal transforms                      │
└────────────────────────────────┬──────────────────────────────────┘
                                 │ dlopen QNN; per-stage contexts
                                 ▼
┌───────────────────────────────────────────────────────────────────┐
│ On-device execution and model artifacts                             │
│ QNN HTP (ASR + Marian MT), CPU host decoders/TTS, app filesDir      │
│ contracts in `app/src/main/assets/npu_manifest.json`                │
└───────────────────────────────────────────────────────────────────┘
```

The production path is a single Hybrid backend, created in `MainActivity.kt` as
`NpuEngine`. It is not a runtime backend selector: QNN performs ASR and MT,
while CPU renders the whole target utterance. `HfEngineBackend.kt` is retained
as a CPU/reference implementation behind the common `EngineBackend` interface,
but is not constructed by the activity.

## Component Responsibilities

| Component | Responsibility | File |
|-----------|----------------|------|
| Activity | Owns permissions and the sole `NpuEngine`/`EngineController` lifetime; pauses translation when backgrounded. | `android-app/app/src/main/java/com/onevoice/link/MainActivity.kt` |
| UI state/controller | Keeps Compose snapshot state, transfers lifecycle operations to `Dispatchers.IO`, and maps engine callbacks to the dashboard. | `android-app/app/src/main/java/com/onevoice/link/ui/screens/DashboardState.kt` |
| UI navigation | Switches Dashboard/Settings and subscribes to headset inventory only while Settings is visible. | `android-app/app/src/main/java/com/onevoice/link/ui/OneVoiceApp.kt` |
| Live engine | Loads the six contexts, owns capture/pipeline/playback workers, route proof, bounded segment queue, replay and telemetry. | `android-app/app/src/main/java/com/onevoice/link/core/NpuEngine.kt` |
| Half-duplex guard | Enforces one owner and only `IDLE → CAPTURING → PROCESSING → PLAYING → IDLE` transitions. | `android-app/app/src/main/java/com/onevoice/link/core/HalfDuplexCoordinator.kt` |
| Direction router | Runs both ASR branches or build-time LID A/B, selects a direction from confidence, runs MT, and opens cold CPU fallback only after selected text-path failure. | `android-app/app/src/main/java/com/onevoice/link/core/AutoHybridPipeline.kt` |
| Per-direction host pipeline | Connects ASR decoding, Marian encoder/decoder graph loops, CPU Kokoro/Supertonic rendering, and direct tensor buffers. | `android-app/app/src/main/java/com/onevoice/link/core/NpuPipeline.kt` |
| Manifest contract | Parses and validates the fixed two directions and three stages per direction before load. | `android-app/app/src/main/java/com/onevoice/link/core/NpuManifest.kt` |
| Native ABI facade | Holds the exact Kotlin declarations for runtime init, per-graph execution, signal transforms, profiling and cleanup. | `android-app/app/src/main/java/com/onevoice/link/core/OneVoiceNative.kt` |
| Native runtime | Keeps QNN handles behind a pimpl, dynamically loads backend/system libraries, validates model contexts, serializes QNN execution. | `native-core/include/qnn_runtime.h`, `native-core/src/qnn_runtime.cpp` |
| JNI boundary | Is the only C++ file touching JNI; checks direct buffers and maps Kotlin calls to `QnnRuntime` and native transforms. | `native-core/src/main_jni.cpp` |

## Pattern Overview

**Overall:** layered Android application with a Kotlin-owned live audio/dataflow
pipeline, JNI adapter, and native hardware-runtime adapter.

**Key Characteristics:**

- `EngineBackend` is the lifecycle seam; user-facing code does not call QNN or
  Android audio APIs directly.
- The turn is bounded before model work. Routing decisions travel with the
  resulting transcript in `PipelineSegment`, so later state cannot relabel it.
- The manifest is the model contract. The app uses direction-qualified runtime
  keys (`vi-en/asr`, `en-vi/mt_encoder`, etc.) because MT graph names repeat.
- Native graph APIs accept only caller-owned direct buffers; model metadata,
  tensor order and dimensions remain native/context-derived.
- Failure policy is deliberately fail-closed for ambiguous routing and verified
  Bluetooth capture/playback; it does not infer a language from headset settings.

## Layers

**Presentation:**

- Purpose: render dashboard, stage load state, transcript/translation and
  settings without owning audio or model resources.
- Location: `android-app/app/src/main/java/com/onevoice/link/ui/`.
- Depends on: `EngineBackend`, `NpuEngine` status types, Android permissions and
  headset repository callbacks.
- Used by: `MainActivity.kt`.

**Application control:**

- Purpose: turn UI lifecycle operations into serialized asynchronous engine
  calls and surface worker results on the Compose state owner.
- Location: `.../ui/screens/DashboardState.kt` (`EngineController`).
- Depends on: `kotlinx.coroutines`, `EngineBackend`.
- Used by: dashboard controls and `MainActivity.kt` teardown.

**Audio/session orchestration:**

- Purpose: resolve route per turn, capture 16 kHz frames, segment with VAD,
  coordinate strict half-duplex execution, then prove/drive playback.
- Location: `.../core/NpuEngine.kt`, `SpeechActivityDetector.kt`,
  `UtteranceSegmenter.kt`, `AudioRoute.kt`, `HalfDuplexCoordinator.kt`.
- Depends on: Android `AudioRecord`/`AudioTrack`, manifest and model pipeline.
- Used by: `EngineController` via `EngineBackend`.

**Inference/routing:**

- Purpose: prepare features, run and score dual ASR or LID, select one
  direction, execute QNN Marian and render complete CPU speech.
- Location: `.../core/AutoHybridPipeline.kt`, `NpuPipeline.kt`,
  `DualAsrRouter.kt`, `ParakeetCtc.kt`, `ZipformerFamilyRnntDecoder.kt`,
  `CpuTtsEngine.kt` and `CpuFallbackBranch.kt`.
- Depends on: `OneVoiceNative`, ORT/sherpa bindings and staged model files.
- Used by: the pipeline worker in `NpuEngine.kt`.

**Hardware adapter:**

- Purpose: initialize QNN once, create one context per binary, retrieve exact
  graphs, execute raw graph buffers and expose native transforms/profiling.
- Location: `native-core/src/main_jni.cpp`, `qnn_runtime.cpp`,
  `asr_frontend.cpp`, `kokoro_audio.cpp`, `thermal_probe.cpp`.
- Depends on: QNN SDK only in `.cpp` via compile-time availability guards.
- Used by: `OneVoiceNative.kt`.

## Data Flow

### Primary Live Turn

1. `MainActivity.kt` creates `NpuEngine`; `EngineController.initialize()` moves
   initialization to `Dispatchers.IO` (`.../ui/screens/DashboardState.kt`).
2. `NpuEngine.initialize()` extracts a matching DSP skeleton, sets
   `ADSP_LIBRARY_PATH` before native load, initializes QNN, then loads six
   contexts described by `npu_manifest.json` (`.../core/NpuEngine.kt`).
3. `NpuEngine.start()` resets `HalfDuplexCoordinator` and starts its capture
   and pipeline workers (`.../core/NpuEngine.kt`).
4. The capture worker takes the next `IDLE → CAPTURING` lease, resolves
   `AudioRoutePreference`, verifies the actual `AudioRecord` route, and feeds
   20 ms frames through `UtteranceSegmenter`/Silero or energy VAD.
5. A bounded `CapturedSegment` is offered to a `LinkedBlockingQueue` of
   capacity one; capture releases communication routing before inference.
6. The pipeline worker moves the owning turn to `PROCESSING`, calls
   `AutoHybridPipeline.routeAndTranslate()` and emits an ASR then an MT
   `PipelineSegment` to the UI listener.
7. In `DUAL_ASR`, `AutoHybridPipeline` shares the GIPFormer frontend and starts
   two host tasks. Each reaches `NpuPipeline`, which uses
   `OneVoiceNative.nativeExecuteGraph()` for its QNN graph stages. Native
   `QnnRuntime::executeGraph()` serializes device graph calls with
   `executionMutex` (`native-core/src/qnn_runtime.cpp`).
8. After one direction is selected, Marian MT runs on QNN. A selected MT/text
   path failure may cold-load `CpuFallbackBranch` and reprocess the original
   bounded input atomically; no partial mixed result is emitted.
9. `NpuPipeline` synthesizes full-utterance CPU speech. `NpuEngine` proves the
   Bluetooth media route where applicable, transitions to `PLAYING`, writes
   stereo content to `AudioTrack`, drains it, then returns the turn to `IDLE`.

### Initialization and Model Loading

1. `NpuManifest.load()` parses `app/src/main/assets/npu_manifest.json`, requires
   exact `vi-en`/`en-vi` direction IDs and `asr`/`mt_encoder`/`mt_decoder`
   stages (`.../core/NpuManifest.kt`).
2. `NpuEngine.loadStagesFor()` resolves each staged binary under app storage,
   calls `OneVoiceNative.loadModel()` under a direction-qualified key, and
   publishes each `StageStatus` for the UI.
3. JNI maps this to `QnnRuntime::loadModel()`, which obtains binary metadata,
   creates a context from binary and retrieves the exact graph name
   (`native-core/src/main_jni.cpp`, `native-core/src/qnn_runtime.cpp`).
4. The engine rejects partial residency: it must report exactly six contexts or
   calls `nativeReleaseModels()`.

### Settings/Headset Inventory

1. `OneVoiceApp.kt` constructs `ConnectedHeadsetRepository` and starts it only
   while Settings is selected.
2. `SettingsScreen.kt` displays connected devices and persisted preferences via
   `HeadsetLanguagePreferences.kt`; these preferences describe listening
   preference and do not choose source-language routing.
3. `AudioRoutePreference` persists independently in SharedPreferences through
   `AudioRoutePreferences` (`.../core/AudioRoute.kt`) and is resolved only at a
   new capture edge.

**State Management:**

- Compose presentation state uses `mutableStateOf` in `EngineController`.
- `NpuEngine` has explicit atomics for lifecycle (`running`, `destroyed`,
  `releasePending`), volatile pipeline/listener/current audio fields, and a
  lock-backed half-duplex state machine for cross-worker ownership.
- Native singleton `g_engine` lives only in `main_jni.cpp`; it owns one
  `QnnRuntime` per app engine lifetime.

## Key Abstractions

**`EngineBackend`:**

- Purpose: isolate UI lifecycle/listener code from the implementation.
- Examples: `.../core/EngineBackend.kt`, `.../core/NpuEngine.kt`,
  `.../hf/HfEngineBackend.kt`.
- Pattern: narrow imperative lifecycle plus callback interface. New backends
  must implement cleanup and asynchronous listener behavior consistently.

**`AutoHybridPipeline`:**

- Purpose: owns both resident directional pipelines and router-local state.
- Examples: `.../core/AutoHybridPipeline.kt`, `.../core/DualAsrRouter.kt`.
- Pattern: direction decision is per bounded utterance, then fixed for that
  result; branch-level model objects are closed together.

**`NpuManifest` / `StageSpec`:**

- Purpose: represent verified binary metadata rather than filename guesses.
- Examples: `.../core/NpuManifest.kt`, `.../assets/npu_manifest.json`.
- Pattern: validate file paths/shape/direction invariants before native work;
  regenerate the asset when a context binary changes.

**`QnnRuntime`:**

- Purpose: hide SDK types and manage backend/device/model-context lifetime.
- Examples: `native-core/include/qnn_runtime.h`,
  `native-core/src/qnn_runtime.cpp`.
- Pattern: pimpl adapter; keep QNN handles out of public headers and pass only
  `ModelSpec`, tensor metadata and raw buffer spans across the boundary.

## Entry Points

**Android application:**

- Location: `android-app/app/src/main/java/com/onevoice/link/MainActivity.kt`.
- Triggers: Android activity creation and runtime permission callbacks.
- Responsibilities: constructs the Hybrid engine, installs Compose content,
  pauses capture in `onStop`, and delegates teardown to `EngineController`.

**JNI library:**

- Location: `native-core/src/main_jni.cpp` (`JNI_OnLoad` and
  `Java_com_onevoice_link_core_OneVoiceNative_*`).
- Triggers: `System.loadLibrary("onevoice_core")` and external calls in
  `.../core/OneVoiceNative.kt`.
- Responsibilities: owns native singleton lifetime, validates direct buffers,
  forwards graph execution/model management, serializes load reports as JSON.

**Benchmark receiver:**

- Location: `android-app/app/src/main/java/com/onevoice/link/core/BenchControlReceiver.kt`.
- Triggers: explicitly delivered benchmark broadcast.
- Responsibilities: uses the weak active `NpuEngine` reference to run
  measurement controls without putting profiling operations in the hot path.

## Architectural Constraints

- **Threading:** `EngineController` performs lifecycle work on `Dispatchers.IO`.
  `NpuEngine` owns capture/pipeline/replay threads; its segment queue capacity
  is one, and `HalfDuplexCoordinator` makes capture and playback mutually
  exclusive. `AutoHybridPipeline` has two ASR host workers; native HTP calls
  remain serialized by `QnnRuntime::State::executionMutex`.
- **JNI ABI:** Every external function in `OneVoiceNative.kt` must retain exact
  name, arity and ordering with `main_jni.cpp`; buffer graph APIs require direct
  `ByteBuffer`s.
- **Global state:** JNI has `g_engine`; `NpuEngine` stores a weak active engine
  for `BenchControlReceiver`; `kokoro_audio.cpp` has native source mutex state.
  Do not introduce another engine/runtime singleton.
- **Hardware target:** app/native build is arm64-v8a only and expects SM8550 /
  Hexagon v73 (`qnnSocModel` 43). The QNN SDK can be absent at build time, in
  which case native calls report stub failures rather than providing emulation.
- **Model residency:** six contexts are loaded together. Reused graph names
  require direction-qualified runtime keys.
- **Audio safety:** Bluetooth must prove communication input and matching A2DP
  output; Auto may fall back to phone, explicit Bluetooth fails closed.

## Anti-Patterns

### Reintroducing a user-selectable backend or source-language toggle

**What happens:** UI code selects a direction/backend before the utterance.
**Why it's wrong:** current routing compares ASR confidence for each bounded
utterance; audio route is only physical I/O and headset preferences are not
speaker-language evidence.
**Do this instead:** keep UI on `EngineBackend`/`NpuEngine` and let
`AutoHybridPipeline.routeAndTranslate()` return the committed direction.

### Treating QNN graph execution as concurrent because two ASR tasks exist

**What happens:** a caller assumes both HTP executions can overlap.
**Why it's wrong:** `QnnRuntime::executeGraph()` locks one global execution
mutex to protect runtime/profile/lifecycle state.
**Do this instead:** preserve the two host tasks for CPU/HTP overlap, but change
native serialization only with a QNN lifecycle/profiling design and device test.

### Using legacy native Oboe cascade for the live product

**What happens:** code calls `nativeStartEngine()`/`executeCascade()` expecting
native capture and end-to-end inference.
**Why it's wrong:** they are compatibility stubs; live `AudioRecord`/`AudioTrack`
and stage-by-stage inference are Kotlin-owned. `AudioEngine` and its SPSC ring
remain native support/legacy code, not the active Hybrid owner.
**Do this instead:** extend `NpuEngine` and `NpuPipeline`, using the direct-buffer
JNI methods in `OneVoiceNative.kt`.

## Error Handling

**Strategy:** fail closed at safety/proof boundaries and surface recoverable
per-turn errors through listeners, while preserving model residency where safe.

**Patterns:**

- Initialization uses `runCatching`, updates `lastError`, releases partial QNN
  contexts, and publishes `StageStatus` for precise dashboard diagnostics.
- Capture/route/routing errors end the current coordinated turn through the
  half-duplex safety net rather than leaving the engine blocked.
- A dual-ASR branch failure, low/floor confidence, or ambiguous decision emits
  no translation. Selected MT failure can atomically retry on the cold CPU
  fallback using the original PCM.
- JNI returns nullable error strings or JSON reports rather than throwing across
  the native boundary; Kotlin parses malformed reports as failed load results.

## Cross-Cutting Concerns

**Logging:** Android `Log` uses stable tags including `OneVoiceNpu`,
`OneVoiceHalfDuplex`, `OneVoiceRouting`, and native `OneVoiceCore`; routing rows
contain per-turn diagnostics in `NpuEngine.kt`.

**Validation:** `NpuManifest.kt` validates schema/directions/stages/relative
paths/tensor contracts; JNI rejects non-direct graph buffers; native load checks
binary identity before graph use.

**Authentication:** Not applicable. The product is offline/on-device and has no
network user-authentication layer in first-party source.

---

*Architecture analysis: 2026-11-02*
