# Architecture

OneVoice Link is built around one hard rule: **the realtime audio→AI→audio path never enters the JVM.** Everything latency-sensitive lives in `libonevoice_core.so`; Kotlin only starts and stops it. This is what lets the app hit conversational latency without ART garbage-collection pauses tearing the audio stream.

## Component map

```
                       Kotlin / Compose (UI + lifecycle only)
                       ────────────────────────────────────────
  MainActivity ─────▶ OneVoiceNative ──(JNI: init/start/stop/destroy)──┐
                                                                        │
  ══════════════════════════════ JNI boundary ═════════════════════════╪══════════
                                                                        │
                       C++ realtime core  (libonevoice_core.so)         ▼
  ┌──────────────┐   ┌─────────────────┐   ┌───────────────┐   ┌────────────────────┐
  │ Oboe capture │──▶│  input SPSC ring │──▶│   AI worker   │──▶│  QNN / Hexagon HTP │
  │  (RT thread) │   │  (lock-free)     │   │ (native thread)│   │  graphExecute()    │
  └──────────────┘   └─────────────────┘   └───────┬───────┘   └─────────┬──────────┘
                                                    │  synthesized PCM     │
  ┌──────────────┐   ┌─────────────────┐            ▼                     │
  │ Oboe playback│◀──│ output SPSC ring │◀───────────────────────────────┘
  │  (RT thread) │   │  (lock-free)     │
  └──────────────┘   └─────────────────┘
```

| Layer | File(s) | Responsibility |
|---|---|---|
| UI / lifecycle | `MainActivity.kt` | Compose dashboard, runtime permissions, maps user actions to the four native calls, tears down on `onDestroy`. |
| JNI bridge | `core/OneVoiceNative.kt`, `src/main_jni.cpp` | The *only* place JNI is touched. Owns the aggregate engine state and spawns the AI worker thread. |
| Audio | `include/audio_engine.h`, `src/audio_engine.cpp` | Two Oboe streams (capture + playback) and the realtime callbacks. |
| Transport | `include/ring_buffer.h`, `src/ring_buffer.cpp` | Lock-free SPSC ring buffers between the audio and AI threads. |
| Inference | `include/qnn_inference.h`, `src/qnn_inference.cpp` | Loads the QNN context onto the HTP and runs the S2ST graph. |

## Threading model

Three threads cooperate at runtime; **none of them ever blocks on another with a lock**:

1. **Oboe capture callback** — a framework-owned, high-priority realtime thread. On each `onAudioReady()` it does an O(1), allocation-free `write()` into the input ring. It never calls Java, never allocates, never locks.
2. **AI worker thread** — spawned in `main_jni.cpp` on `nativeStartEngine()`. It drains a full **1-second (16 000-sample) chunk** from the input ring, runs the QNN cascade, and writes synthesized PCM into the output ring. When the ring is starved it yields with a 100 µs micro-sleep rather than spinning a whole core.
3. **Oboe playback callback** — another realtime thread. It `read()`s from the output ring; on underrun it zero-fills the tail so the DAC gets a clean gap instead of a glitch.

The 16 ms (256-frame) Oboe callback granularity decouples **audio latency** (governed by the callback size) from **model latency** (governed by the 1 s chunk), so a slow inference pass degrades to a momentary gap, not a torn stream.

## The lock-free SPSC ring buffer

`SpscRingBuffer<T>` (`ring_buffer.h`) is the linchpin of the zero-contention design:

- **Single-producer / single-consumer.** `head_` is written only by the producer; `tail_` only by the consumer. Each is read by the other with acquire semantics. On ARM64 this gives a happens-before edge with **no fence** on the hot path.
- **Power-of-two capacity.** Indices are masked (`idx & mask_`) instead of modulo'd — a single branch-free `and`.
- **Cache-line isolation.** `head_` and `tail_` are each `alignas(64)` on their own cache line, so the producer and consumer never false-share — critical for keeping the audio callback off the coherence bus.
- **Monotonic 64-bit counters.** In-use count is `head - tail`, correct across natural wrap.
- **Overflow policy: drop-newest.** A strict SPSC producer cannot advance the consumer-owned tail, so on a full ring `write()` drops the newest incoming samples and returns the count accepted. The buffer is sized at **32 768 samples (~2.048 s)** so a transient AI stall becomes a brief gap rather than corruption.

Requires `std::is_trivially_copyable_v<T>` (enforced by `static_assert`) because it moves data with `memcpy`.

## Audio configuration

| Parameter | Value | Rationale |
|---|---|---|
| Sample rate | 16 kHz | Matches the model's static input shape. |
| Channels | 1 (mono) | Speech. |
| Format | PCM `int16` | Model I/O and the Android voice path. |
| Performance mode | `LowLatency` | Engages the AAudio MMAP fast path (Android 8.1+). |
| Sharing mode | `Exclusive` | No mixer/resampler; falls back to `Shared` if unavailable. |
| Callback size | 256 frames (~16 ms) | Small buffer → low audio latency. |
| Output routing | `Usage::VoiceCommunication` + `ContentType::Speech` | Engages the platform AEC / noise-suppression path. |

## Data flow, end to end

1. `nativeInitEngine(backendPath, contextBinPath, graphName)` opens both Oboe streams and loads the QNN context onto the HTP. Audio is **not** started yet, so the AI thread can be primed first.
2. `nativeStartEngine()` spawns the AI worker and starts full-duplex audio. The output ring is pre-primed with one callback of silence to avoid a startup click.
3. Steady state: capture → input ring → AI worker → QNN/HTP → output ring → playback, all in native code.
4. `nativeStopEngine()` halts the worker and stops audio but keeps the engine initialized for a fast restart.
5. `nativeDestroyEngine()` (wired into `Activity.onDestroy`) joins the worker and releases all audio + QNN resources.

## Inference pipeline (QNN / Hexagon HTP)

`QnnInference::init()` runs an ordered, self-cleaning setup: `loadBackend_` → `createContextFromBinary_` → `retrieveGraph_` → `mapTensors_` → `registerZeroCopyMem_`. Any failure tears down what was built so far. The design targets **zero-copy** tensor inputs backed by ION/FastRPC memory so the HTP DMAs buffers directly. See [QNN_INTEGRATION.md](QNN_INTEGRATION.md) for the SDK-level detail and current implementation status.

## Design invariants (do not break)

- Nothing in an Oboe callback may allocate, lock, block, or call into Java.
- Each ring buffer has exactly one producer thread and one consumer thread.
- The JVM crosses the JNI boundary only for the four lifecycle calls — never per audio frame.
- The QNN backend `.so` and its `dlopen` dependencies must be present in the APK's `nativeLibraryDir` (see [QNN_INTEGRATION.md](QNN_INTEGRATION.md)).
