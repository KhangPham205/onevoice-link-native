# JNI API contract

The Kotlin app and the C++ core communicate through exactly **four** lifecycle functions. No audio data ever crosses this boundary — only start/stop signals. This keeps the JVM off the realtime path entirely.

## The contract

| Kotlin (`OneVoiceNative`) | C++ symbol (`main_jni.cpp`) | Returns |
|---|---|---|
| `nativeInitEngine(backendPath, contextBinPath, graphName)` | `Java_com_onevoice_link_core_OneVoiceNative_nativeInitEngine` | `Boolean` |
| `nativeStartEngine()` | `…_nativeStartEngine` | `Boolean` |
| `nativeStopEngine()` | `…_nativeStopEngine` | `void` |
| `nativeDestroyEngine()` | `…_nativeDestroyEngine` | `void` |

Class/package: **`com.onevoice.link.core.OneVoiceNative`**. The shared library is **`onevoice_core`** (loaded via `System.loadLibrary("onevoice_core")` in the object's `init` block). JNI is registered dynamically by name; `JNI_OnLoad` reports `JNI_VERSION_1_6`.

## ⚠️ `nativeInitEngine` takes THREE strings, not one

This is the single most important detail on the boundary. The C++ signature is:

```cpp
JNIEXPORT jboolean JNICALL
Java_com_onevoice_link_core_OneVoiceNative_nativeInitEngine(
        JNIEnv* env, jclass clazz,
        jstring backendPath, jstring contextBinPath, jstring graphName);
```

so the Kotlin `external` declaration **must** match its arity exactly:

```kotlin
external fun nativeInitEngine(
    backendPath: String,
    contextBinPath: String,
    graphName: String,
): Boolean
```

If Kotlin declared a single-argument `nativeInitEngine(modelPath: String)`, the JVM would push an argument frame one `jstring` short of what C++ expects. JNI resolves by name (not arity), so it would still *link* — then the C++ side would read uninitialized register/stack slots for `contextBinPath` and `graphName` and `GetStringUTFChars` a garbage pointer. **Undefined behavior / native crash.** Always keep the Kotlin arity identical to the C++ arguments (the implicit `JNIEnv*`/`jclass` don't count).

| C++ parameter | Meaning |
|---|---|
| `backendPath` | Absolute path to the QNN backend `.so` to `dlopen`, e.g. `<nativeLibraryDir>/libQnnHtp.so`. |
| `contextBinPath` | Absolute path to the serialized QNN context binary (`.bin`) with the graphs pre-compiled for the HTP. |
| `graphName` | Name of the graph to retrieve from that context (must match the `.dlc`/`.bin` export). |

## The Kotlin bridge

`OneVoiceNative` (an `object` — one engine per session) exposes the raw externals plus a convenience wrapper so UI code can think in terms of a single model location:

```kotlin
fun initialize(nativeLibDir: String, modelDir: String): Boolean =
    nativeInitEngine(
        backendPath    = "$nativeLibDir/libQnnHtp.so",
        contextBinPath = "$modelDir/onevoice_s2st.bin",
        graphName      = "onevoice_s2st",
    )
```

`MainActivity` passes `applicationInfo.nativeLibraryDir` (where the packaged QNN `.so` files are unpacked) as `nativeLibDir`.

## Lifecycle & threading rules

- **State machine:** `NOT_INITIALIZED → (init) → INITIALIZED → (start) → RUNNING → (stop) → INITIALIZED → (destroy) → NOT_INITIALIZED`.
- **Serialize the calls.** The native side keeps a single global engine and assumes the Kotlin layer does not call these concurrently. `MainActivity` runs each call on `Dispatchers.IO` one at a time and gates the UI on a `busy` flag.
- **`nativeInitEngine`** opens audio + loads QNN but does **not** start audio (so the AI thread can be primed first). Idempotent: a second call returns `true` without re-initializing.
- **`nativeStartEngine`** spawns the AI worker and starts full-duplex audio; returns `false` (and rolls back) if the stream fails to start. Idempotent — a call while already running is a no-op returning `true` (a second start would otherwise move-assign onto the live worker thread and `std::terminate()`).
- **`nativeStopEngine`** joins the worker and stops audio but keeps the engine initialized for a fast restart.
- **`nativeDestroyEngine`** fully releases everything. **Safe to call unconditionally** — it no-ops if the engine was never created — so it is wired directly into `Activity.onDestroy()`.
- Calls may fail gracefully: `initialize`/`start` are wrapped in `runCatching` in the UI so a native error surfaces as a status message rather than crashing the process.

## Adding a new native method (checklist)

1. Add the `extern "C"` `JNIEXPORT` function in `main_jni.cpp` with the exact mangled name `Java_com_onevoice_link_core_OneVoiceNative_<name>`.
2. Add a matching `external fun <name>(…)` in `OneVoiceNative.kt` with **identical Java-visible arity and types**.
3. Rebuild — a name mismatch surfaces at first call as `UnsatisfiedLinkError`; an arity mismatch is silent and dangerous, so double-check it.
