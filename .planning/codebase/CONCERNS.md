# Concerns

## High priority

- **Documentation drift will mislead implementation work.** `CLAUDE.md` and the current source agree on dual-ASR routing (GIPFormer VI + Parakeet CTC EN); `docs/OFFLINE_PIPELINE.md` and `docs/NPU_DEPLOY.md` still describe Bluetooth-headset identity routing and Zipformer-small. `docs/JNI_API.md` also describes an obsolete four-method lifecycle ABI, while `OneVoiceNative.kt` now exposes runtime, model-load, graph-execute, frontend and benchmark methods. Treat the Kotlin/C++ source and `CLAUDE.md` as the closest available authority until the docs are reconciled.
- **A real build and device run depend on non-versioned licensed/large artifacts.** QNN SDK libraries, QNN context binaries, model artifacts, and the sherpa AAR are ignored or supplied locally. Source compiles in a QNN stub mode without the SDK, but that cannot validate the production NPU path.
- **The production target is intentionally narrow.** Only `arm64-v8a` and SM8550 / Hexagon v73 (`socModel=43`) are supported. The manifest requires Samsung-exposed `libcdsprpc.so`; portability to another Snapdragon/OEM is not assumed.

## Engineering risks

- **Dual routing is host-concurrent but HTP-serialized.** The two ASR host tasks run in a two-thread pool, but every `QnnRuntime::executeGraph` takes one global `executionMutex`. It gains CPU/HTP overlap, not two simultaneous HTP graph executions; latency decisions should use hardware measurements.
- **The JNI boundary is broad and manually mirrored.** Kotlin `external` declarations and C++ exports must stay arity/order compatible, with raw direct buffers for graph execution. There is no generated binding layer or ABI compatibility test visible in the repository.
- **The native runtime is process-global.** `main_jni.cpp` owns a global engine/runtime. Multiple app sessions or concurrent lifecycle use need the Kotlin coordination to remain correct.
- **Fallback is intentionally restrictive.** A selected direction can restart the original bounded PCM on cold CPU ASR+MT only after a QNN text-path failure; routing ambiguity and partial dual-ASR failure fail closed. This protects wrong-language output but causes avoidable rejected turns if calibration is poor.

## Verification gaps

- Unit tests cover routing thresholds, segmentation, VAD helpers, resampling, audio-route policy and TTS helpers. Instrumentation coverage is sparse and does not prove QNN, Bluetooth/SCO/A2DP routing, DSP loading, model deployment, thermal behavior, or end-to-end translation on SM8550.
- No CI configuration is present. Build commands require a constrained local Android/QNN environment and large proprietary inputs.
- The repository has a pre-existing uncommitted `.gitignore` change (ignoring `Vie-Eng dataset/`); it was not touched by this map.

## Useful next checks

1. Update the stale deployment/offline/JNI documents from the live source and test scripts.
2. Run the unit test suite, then assemble a debug APK on a machine with the declared SDK/AAR/QNN prerequisites.
3. Verify both directions on the target phone with deployment checks and collect the built-in benchmark telemetry before changing routing thresholds.
