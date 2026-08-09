# QNN / Hexagon HTP integration

OneVoice dynamically loads QAIRT, restores six context binaries on HTP, and
executes their graphs through a generic raw-buffer bridge. The live orchestration
is in Kotlin; native code owns QNN metadata and the performance-sensitive audio
frontends.

## Runtime loading order

`libonevoice_core.so` has no QNN `DT_NEEDED` entries. The order is deliberate:

1. extract the matching Hexagon v73 skeleton into app-accessible storage;
2. publish `ADSP_LIBRARY_PATH`;
3. expose/load the public vendor `libcdsprpc.so` and request unsigned PD;
4. `dlopen` packaged `libQnnSystem.so` and `libQnnHtp.so`;
5. create one backend/device and one context per `.bin`;
6. retrieve the exact graph name declared in `npu_manifest.json`.

The manifest must contain the Android linker declaration because target API 31+
cannot access even public vendor libraries unless it requests them:

```xml
<uses-native-library
    android:name="libcdsprpc.so"
    android:required="true" />
```

`ADSP_LIBRARY_PATH` only controls DSP skeleton lookup. It does not modify the
Android CPU linker namespace and it does not bypass SELinux or signing policy.

## Packaging

Gradle stages the ARM64 QAIRT host libraries in the APK. The Hexagon skeleton is
a DSP ELF, not an ARM64 JNI library; it is kept as an asset and copied
byte-for-byte into `filesDir/dsp` before runtime initialization.

The current retail device runs the QAIRT unsigned v73 skeleton in unsigned PD.
A generated `testsig-*.so` is neither a replacement QNN skeleton nor a way to
authenticate arbitrary DSP code on production Samsung firmware.

## Graph metadata and execution

`QnnRuntime::loadModel` calls `QnnSystemContext_getBinaryInfo` before freeing the
system context. It deep-copies every execution-critical tensor field:

- tensor version, ID, name, type and data format;
- data type and quantization parameters;
- rank and dimensions.

`QnnRuntime::executeGraph(stage, inputs, outputs, error)` then recreates the QNN
tensor descriptors, attaches caller-owned direct buffers as RAW client buffers,
validates count and byte capacity, and invokes `graphExecute`. Execution and
context release share a mutex so a teardown cannot race a non-interruptible HTP
call.

Kotlin passes direct `ByteBuffer` instances through `OneVoiceNative` and reuses
persistent buffers across utterances. This avoids per-token/per-bucket native
allocations and direct-buffer accumulation.

## Host glue versus learned inference

The QNN package is a cascade, not one end-to-end graph. `NpuPipeline` performs
the deterministic glue required to drive it:

- ASR: 16 kHz NeMo-compatible fbank and CTC greedy decoding;
- MT: SentencePiece and autoregressive Marian decoding with a persistent KV
  cache;
- TTS: token/style lookup, duration alignment, learned QNN prosody, harmonic
  STFT, bucketed QNN waveform decoding, and seam fades.

Every learned stage runs on HTP. The legacy `executeCascade` native entry point
remains only for ABI compatibility and returns `false`; the live path calls the
generic executor graph by graph.

## Failure diagnosis

`0x36b1` is `QNN_DEVICE_ERROR_INVALID_CONFIG`, not a signature diagnosis. Always
read earlier lines in the log. The resolved incident on the target phone was:

```text
libcdsprpc.so not found
  -> loadRemoteSymbols error 4000
  -> transport setup 14001 / 0x36b1
```

Adding the `uses-native-library` declaration exposed the firmware's public
FastRPC library; `remote_session_control(UNSIGNED_MODULE)=0` then succeeded and
all contexts loaded. See
[the incident postmortem](incidents/2026-08-08-qnn-0x36b1-fastrpc-linker-policy.md).

## Lifecycle invariants

- Set the DSP search path and unsigned/signed PD mode before the first FastRPC
  interaction.
- Never silently fall back from requested signed PD to a null/default device.
- Never free a context while its graph is executing.
- Graphs are owned by contexts; QNN has no standalone `graphFree`.
- Stop capture, join the pipeline worker, release contexts, then free device,
  backend, and dynamic-library handles in reverse order.
