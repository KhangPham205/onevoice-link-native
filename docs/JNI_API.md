# JNI API contract

Kotlin owns audio capture/playback (`AudioRecord`/`AudioTrack`) and pipeline
orchestration (`NpuEngine`, `AutoHybridPipeline`, `NpuPipeline`). JNI supplies
QNN lifecycle, generic direct-buffer graph execution, native signal frontends
and benchmark probes. Audio crosses the boundary only as whole bounded
utterances (`FloatArray` PCM in, direct `ByteBuffer` features out).

There are two JNI surfaces:

| Kotlin class | Native library | C++ source |
|---|---|---|
| `com.onevoice.link.core.OneVoiceNative` (object) | `libonevoice_core.so` | `native-core/src/main_jni.cpp` |
| `com.onevoice.link.core.SeaG2p` (internal class) | `libsea_g2p_jni.so` → `dlopen("libsea_g2p_rs.so")` | `native-core/src/sea_g2p_jni.cpp` |

JNI binds by mangled name (`Java_com_onevoice_link_core_<Class>_<name>`), not
by registration. `JNI_OnLoad` reports `JNI_VERSION_1_6`.

## Arity is an ABI

JNI resolves by name, not signature. If Kotlin and C++ disagree on argument
count, order or type, the call still links and C++ reads garbage arguments:
undefined behavior, usually a native crash. Change both
`OneVoiceNative.kt` and `main_jni.cpp` (or `SeaG2p.kt` and `sea_g2p_jni.cpp`)
together.

## `OneVoiceNative`

`System.loadLibrary("onevoice_core")` runs in the object's `init` and sets
`isLoaded`; it is false on non-arm64 devices, and any `external` call then
throws `UnsatisfiedLinkError`. The core has no QNN `DT_NEEDED`, so loading it
does not load QNN. `NpuEngine` still sets `ADSP_LIBRARY_PATH` before the first
reference to `OneVoiceNative`.

Errors are returned, not thrown: a `String?` result is `null` on success and an
error message otherwise.

### QNN lifecycle and execution

| Function | Purpose |
|---|---|
| `nativeInitRuntime(backendPath, systemPath, adspLibraryPath, useSignedProcessDomain): Boolean` | `dlopen` `libQnnSystem.so`/`libQnnHtp.so`, request unsigned PD via `libcdsprpc.so` (or signed-PD device config), create backend and device. Loads no models. |
| `nativeLoadModel(stage, binPath, graphName): String` | Load one context binary, retrieve `graphName`, return a JSON record (stage, ok, loadMs, sizeBytes, graphName, socModel, buildId, input/output tensors, error) parsed by `ModelLoadReport`. |
| `nativeLoadedModelCount(): Int` | Resident context count (Hybrid expects 6). |
| `nativeExecuteGraph(stage, inputs: Array<ByteBuffer>, outputs: Array<ByteBuffer>): String?` | Execute one loaded graph with caller-owned direct buffers; checks count and byte capacity against the binary's tensor contract. |
| `nativeReleaseModels()` | Free all contexts; backend/device stay up. |
| `nativeDestroyEngine()` | Full QNN teardown. Safe when nothing was created. |

`stage` is the direction-qualified runtime key, for example `vi-en/asr` or
`en-vi/mt_decoder`. It is mandatory: both Marian directions embed the same
graph names (`opus_mt_encoder`, `opus_mt_decoder`). Graph names come from
`assets/npu_manifest.json`, never from filenames. The convenience wrappers
`initRuntime(nativeLibDir, ...)` and `loadModel(...)` build the library paths
and parse the JSON.

### Native frontends

| Function | Purpose |
|---|---|
| `nativeExtractGipformerFeatures(samples, output): String?` | VI ASR: Kaldi/icefall fbank, 16 kHz PCM → direct `[1,1000,80]` FP32. |
| `nativeExtractAsrFeatures(samples, output): String?` | EN ASR (Parakeet): NeMo log-mel, per-feature normalized → `[1,1001,80]` FP32; Kotlin takes the first 1000 frames and quantizes to UFIXED_16. |
| `nativeExtractSpeechFlowFeatures(samples, output, outFrames: IntArray): String?` | SpeechFlow LID (`-ProutingMode=LID` only): time-major `[1,1000,80]`, unpadded frame count in `outFrames[0]`. |
| `nativeBuildKokoroHar(f0, har, resetPhase): String?` | Historical: harmonic STFT for the retired fixed-window QNN Kokoro path. Not called in Hybrid. |

### Benchmark surface

Kept off `nativeExecuteGraph` so the hot call's arity and cost do not change.
Used by `BenchControlReceiver`/`BenchTelemetry` and per-turn reports in `NpuEngine`.

| Function | Purpose |
|---|---|
| `nativeSetQnnProfiling(enabled): Boolean` | Toggle BASIC per-execute QNN profiling (adds time inside `graphExecute`). |
| `nativeApplyHtpPowerConfig(mode, rpcControlLatencyUs, rpcPollingTimeUs): String?` | Pin HTP DVFS: `burst`, `sustained_high_performance` or `balanced`. |
| `nativeDrainQnnProfileStages(): Array<String>` | Snapshot profile samples and return their stage names. Call first. |
| `nativeDrainQnnProfile(): LongArray` | Consume that snapshot as `[wallUs, executeUs, acceleratorUs, queueWaitUs, profiled] * n`. |
| `nativeActivePowerMode(): String` | Current power mode, or `unavailable`. |
| `nativeThermalStatus(): Int` | `AThermalStatus` (0–6), or -1 below API 30 (resolved with `dlsym`). |

### Obsolete entries

`nativeStartEngine()` logs a warning and returns `false`; `nativeStopEngine()`
is a no-op. They remain only so the symbol set stays stable. The old
four-function start/stop contract and the Oboe full-duplex engine are not used
by Hybrid, and `QnnRuntime::executeCascade` (no JNI binding) returns `false`.

## `SeaG2p`

VieNeu-TTS's Vietnamese normaliser + G2P (Rust, C ABI). Used only when
`viTtsEngine=VIENEU`.

| Function | Purpose |
|---|---|
| `nativeOpen(dictPath: ByteArray): Long` | Open the memory-mapped `sea_g2p.bin` dictionary; returns a handle. |
| `nativePhonemize(handle, text: ByteArray, puncNorm: Boolean): ByteArray` | Normalise and phonemize. |
| `nativeClose(handle)` | Free the handle. |

- Strings cross as UTF-8 `ByteArray`: JNI's `GetStringUTFChars`/`NewStringUTF`
  use modified UTF-8, which the Rust library does not expect.
- `SeaG2p.open` loads `sea_g2p_rs` before `sea_g2p_jni`, so the shim's
  `dlopen` resolves an already loaded soname in the app linker namespace.
- The shim checks `sea_g2p_abi_version() == 1` and throws
  `IllegalStateException` on a missing library, symbol or ABI mismatch.
- A handle is not thread-safe; the Kotlin methods are `@Synchronized`.

## Threading

- `QnnRuntime` wraps every `graphExecute` and context release in one global
  `executionMutex`. The two ASR branches are submitted concurrently, so HTP
  encoder execution is serial while one branch's CPU decoding overlaps the
  other's HTP call. Teardown cannot race an in-flight execute.
- Lifecycle calls (`init`, `load`, `release`, `destroy`) are serialized by
  `NpuEngine`; the native side keeps one global engine.
- Kotlin reuses persistent direct buffers across utterances to avoid per-call
  native allocation.

## Adding a native method

1. Add an `extern "C" JNIEXPORT` function named
   `Java_com_onevoice_link_core_<Class>_<name>` in the matching `.cpp`.
2. Add the `external fun` with identical Java-visible arity, order and types.
3. A name mismatch fails at first call with `UnsatisfiedLinkError`; an arity
   mismatch is silent, so check it by hand.
