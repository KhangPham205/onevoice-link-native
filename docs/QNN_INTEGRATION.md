# QNN / Hexagon HTP integration

OneVoice dynamically loads QAIRT, restores six context binaries on HTP, and
executes their graphs through a generic direct-buffer bridge. Orchestration is
in Kotlin (`NpuEngine`, `AutoHybridPipeline`, `NpuPipeline`); native code owns
QNN state and the ASR frontends. See [JNI_API.md](JNI_API.md) for the boundary
and [ARCHITECTURE.md](ARCHITECTURE.md) for the pipeline.

## Resident contexts

Hybrid loads exactly six contexts and fails initialization (releasing partial
state) unless all six load:

| Direction | `asr` | `mt_encoder` | `mt_decoder` |
|---|---|---|---|
| `vi-en` | GIPFormer encoder, FP32 `[1,1000,80]` → `[1,248,512]` | Marian | Marian |
| `en-vi` | Parakeet-TDT-CTC-110M CTC head, UFIXED_16 `[1,1000,80]` → `[1,125,1025]` | Marian | Marian |

Runtime keys are direction-qualified (`vi-en/mt_encoder`, `en-vi/asr`, ...)
because both Marian directions embed the same graph names (`opus_mt_encoder`,
`opus_mt_decoder`). RNN-T decode/join, CTC greedy decode, SentencePiece and all
TTS run on CPU; no TTS graph runs on HTP.

## Runtime loading order

`libonevoice_core.so` has no QNN `DT_NEEDED` entries, so
`System.loadLibrary("onevoice_core")` does not load QNN. The order is
deliberate:

1. `NpuEngine` extracts the Hexagon v73 skeleton asset to
   `<filesDir>/dsp/unsigned/` (or `dsp/signed/` when a signed skeleton was
   staged at build time);
2. `Os.setenv("ADSP_LIBRARY_PATH", ...)`, before `libQnnHtp.so` first loads;
3. `nativeInitRuntime` `dlopen`s the public vendor `libcdsprpc.so` and calls
   `remote_session_control(UNSIGNED_MODULE)` before any FastRPC session;
4. `dlopen` packaged `libQnnSystem.so` and `libQnnHtp.so` (by soname first,
   since uncompressed jniLibs are mmap'd from the APK, then by absolute path);
5. create one backend and device, then one context per `.bin`;
6. retrieve the exact graph name declared in `assets/npu_manifest.json`.

Each load reports three distinct checks: `QnnSystemContext_getBinaryInfo`
(graph name, tensor contract, `socModel`, build id, no hardware touched),
`contextCreateFromBinary` (placement on HTP) and `graphRetrieve` (graph
identity). Graph names are not guessable from filenames; regenerate the
manifest with `python3 tools/models/gen_npu_manifest.py` for every model drop.

Because the app targets API 36, the manifest must request the vendor FastRPC
library; without it the app linker namespace cannot see it:

```xml
<uses-native-library
    android:name="libcdsprpc.so"
    android:required="true" />
```

`ADSP_LIBRARY_PATH` only controls DSP skeleton lookup. It does not modify the
Android CPU linker namespace, and it does not bypass SELinux or signing policy.

## Packaging

- `copyQnnRuntimeLibs` stages the ARM64 host libraries (`libQnnSystem.so`,
  `libQnnHtp.so`, `libQnnHtpPrepare.so`, `libQnnHtpV*Stub.so`) from
  `native-core/third_party/qnn_sdk/lib/aarch64-android/` into the APK.
- The Hexagon skeleton is a DSP6 ELF, not an ARM64 JNI library. It ships
  uncompressed as asset `dsp/libQnnHtpV73Skel.so` (a git-ignored copy of
  `qnn_sdk/lib/hexagon-v73/unsigned/`) and is copied byte-for-byte to
  `filesDir` because FastRPC needs a real file on disk.
- Model binaries are not in the APK; they are staged under internal `filesDir`
  with `run-as` (see [NPU_DEPLOY.md](NPU_DEPLOY.md)).

The retail SM8550 test device runs the QAIRT unsigned v73 skeleton in unsigned
PD. A testsig is not required and is not a production trust anchor; see
[QNN_SIGNED_DSP.md](QNN_SIGNED_DSP.md).

## Graph metadata and execution

`QnnRuntime::loadModel` deep-copies every execution-critical tensor field from
the binary info (version, ID, name, type, data format, data type, quantization
parameters, rank, dimensions) before freeing the system context. The manifest
records the fixed-point scale/offset, and `StageSpec.quantization` carries it to
the host for Parakeet's UFIXED_16 I/O.

`QnnRuntime::executeGraph(stage, inputs, outputs, error)` rebuilds the tensor
descriptors, attaches caller-owned direct buffers as RAW client buffers,
validates tensor count and byte capacity, and calls `graphExecute`.

All `graphExecute` calls and context release share one global
`executionMutex`. The two ASR branches are submitted concurrently, so HTP
encoder execution is serial while one branch's CPU decoder overlaps the other
branch's HTP call. This is intentional: the shared profiling/lifecycle state
does not support asserting QNN concurrency, and the mutex stops teardown from
racing a non-interruptible HTP call.

## Failure diagnosis

`0x36b1` (14001) is `QNN_DEVICE_ERROR_INVALID_CONFIG`, not a signature
diagnosis. Always read the earlier log lines. The resolved incident on the
target phone was:

```text
libcdsprpc.so not found
  -> loadRemoteSymbols error 4000
  -> transport setup 14001 / 0x36b1
```

Adding the `uses-native-library` declaration exposed the firmware's public
FastRPC library; `remote_session_control(UNSIGNED_MODULE)=0` then succeeded and
all contexts loaded. If 14001 recurs, check `libcdsprpc` visibility, the
unsigned-control result, skeleton lookup and stub/skel versions first. See
[the incident postmortem](incidents/2026-08-08-qnn-0x36b1-fastrpc-linker-policy.md).

## Lifecycle invariants

- Set the DSP search path and PD mode before the first FastRPC interaction.
- `deviceCreate` failure is non-fatal in unsigned mode (contexts load on the
  default device), but fatal in signed mode: never silently fall back from a
  requested signed PD to the default unsigned one.
- Never free a context while its graph is executing.
- Graphs are owned by contexts; QNN has no standalone `graphFree`.
- Stop capture, join the pipeline worker, release contexts, then free device,
  backend and dynamic-library handles in reverse order.
- Without the SDK headers, every QNN call compiles as a logged stub and
  `nativeInitRuntime` returns `false`.

`OneVoiceNpu` logs a memory snapshot after the six contexts load and another
after host glue loads; `peak_rss_kb` is app-process `VmHWM` and excludes most
DSP-private memory.
