# QNN / Hexagon HTP integration

This document covers how the Qualcomm AI Runtime (QNN) SDK is wired into the build, how its shared libraries are packaged into the APK, and how they are loaded at runtime.

## SDK layout

The SDK is vendored (not committed) under `native-core/third_party/qnn_sdk/`, flat — `include/QNN/` and `lib/aarch64-android/` sit directly beneath it (no nested version folder). `CMakeLists.txt` points at exactly those paths:

```cmake
set(QNN_SDK_DIR ${THIRD_PARTY_DIR}/qnn_sdk)
target_include_directories(onevoice_core PRIVATE ${QNN_SDK_DIR}/include/QNN)
target_link_directories   (onevoice_core PRIVATE ${QNN_SDK_DIR}/lib/aarch64-android)
target_link_libraries     (onevoice_core PRIVATE QnnSystem QnnHtp QnnHtpPrepare)
```

## The versioned interface accessor

QNN does not expose its functions as free symbols. A backend `.so` fills a `QnnInterface_t`, whose function pointers live inside a **versioned union member** named by the macro `QNN_INTERFACE_VER_NAME` (e.g. `v2_xx`, resolved from the SDK's API version):

```cpp
// WRONG — no such member on the outer struct:
state_->api->backendCreate(…);

// RIGHT — go through the version accessor:
state_->api->QNN_INTERFACE_VER_NAME.backendCreate(…);
```

Using the macro (rather than a hard-coded `v2_23`) keeps the code portable across SDK versions. Note also that **there is no `graphFree`** in the QNN API — a graph is owned by its context and released by `contextFree`; and **`deviceFree` takes a single argument** (the device handle). These are reflected in `qnn_inference.cpp`.

## Bring-up sequence

`QnnInference::init()` runs these steps in order, tearing down on any failure:

1. **`loadBackend_`** — `dlopen(backendPath)` (e.g. `libQnnHtp.so`), `dlsym("QnnInterface_getProviders")`, enumerate the returned providers and select the one whose `coreApiVersion` matches the compiled headers (`QNN_API_VERSION_MAJOR`, `minor >=`), copy its interface, then `backendCreate(logger=null, config=null, &backend)`.
2. **`createContextFromBinary_`** — `deviceCreate(logger=null, config=null, &device)`, read the `.bin` into memory, `contextCreateFromBinary(backend, device, config=null, blob, size, &context, profile=null)` (loads the pre-compiled graphs straight onto the HTP — no on-device compilation).
3. **`retrieveGraph_`** — `graphRetrieve(graphName)`.
4. **`mapTensors_`** 🚧 — bind the raw-audio input and synthesized-PCM output tensors to the model's static INT8 shapes. **Placeholder** (model-specific; owned by the AI team).
5. **`registerZeroCopyMem_`** 🚧 — register ION/FastRPC buffers with `QnnMem_register` so the HTP DMAs input/output directly, avoiding a per-`execute()` copy. **Placeholder.**

`execute()` then runs `graphExecute` per 1 s audio chunk (🚧 currently a silence stub until the tensor binding above is wired).

## Packaging the runtime `.so` into the APK

**Linking is not packaging.** CMake links `libonevoice_core.so` against the QNN libs, but AGP does *not* automatically copy prebuilt link-time dependencies into the APK. If they are missing from `nativeLibraryDir`, loading fails at runtime. So `build.gradle.kts` stages the required subset:

```kotlin
val copyQnnRuntimeLibs = tasks.register<Sync>("copyQnnRuntimeLibs") {
    from(qnnAndroidLibDir) {
        include("libQnnSystem.so", "libQnnHtp.so", "libQnnHtpPrepare.so", "libQnnHtpV*Stub.so")
    }
    into(File(qnnStagingRoot, "arm64-v8a"))
    // … existence check via a locally-captured File (config-cache safe)
}
// registered as an extra jniLibs source dir; sequenced before the jniLibs merge.
```

This keeps ~96 MB of binaries out of git while guaranteeing they land in `lib/arm64-v8a/`. `packaging { jniLibs { useLegacyPackaging = false } }` stores them uncompressed and 16 KB page-aligned.

Packaged set: `libQnnSystem.so`, `libQnnHtp.so`, `libQnnHtpPrepare.so`, and the CPU-side FastRPC stubs `libQnnHtpV{68,69,73,75,79,81}Stub.so` (+ their `CalculatorStub` variants), covering every recent Snapdragon HTP generation.

## Runtime load chain (verified with `readelf`)

A subtle but important point about *how* the libraries are loaded:

- `libonevoice_core.so` has **no `NEEDED` entry** for any QNN library. The linker's default `--as-needed` drops them because the core references no QNN symbol at link time — it **`dlopen`s `libQnnHtp.so` at runtime** (the path passed as `backendPath`). Therefore `System.loadLibrary("onevoice_core")` succeeds on its own.
- `libQnnHtp.so` in turn hard-depends only on system libs (`libc/libm/libdl/liblog`) and **`dlopen`s** `libQnnHtpPrepare.so` and the arch-appropriate `libQnnHtpV<arch>Stub.so` by name.

So the whole chain resolves as long as every one of those files sits in `nativeLibraryDir` — which is exactly what the packaging step guarantees.

```
System.loadLibrary("onevoice_core")     → loads cleanly (no QNN in NEEDED)
      └─ dlopen("<nativeLibDir>/libQnnHtp.so")   (backendPath from Kotlin)
             ├─ dlopen("libQnnHtpPrepare.so")
             └─ dlopen("libQnnHtpV<arch>Stub.so")  → FastRPC → Hexagon skel on the DSP
```

> The Hexagon-side skeletons (`libQnnHtpV<arch>Skel.so`, under `lib/hexagon-v<arch>/`) run on the DSP itself and are loaded over FastRPC from the platform's `ADSP_LIBRARY_PATH`, not from the APK. They are intentionally **not** in the packaged set.

## Status & roadmap

| Area | State |
|---|---|
| SDK bind (headers + link) | ✅ done |
| `.so` packaging into APK | ✅ done, verified |
| `dlopen` chain / library resolution | ✅ verified |
| backend / device / context-from-binary / graph retrieve | ✅ wired against the real C API |
| Tensor mapping (INT8 static shapes) | 🚧 placeholder — needs the model's `.dlc` tensor names/shapes |
| QnnMem zero-copy (ION/FastRPC) | 🚧 placeholder — perf optimization |
| `graphExecute` + PCM↔INT8 (de)quant | 🚧 placeholder — silence stub |
| Serialized context binary (`.bin`) | ⛔ not yet supplied; `initEngine` fails at `contextCreateFromBinary` until it is |

**To finish the inference path:** supply a QNN context `.bin` compiled for the target HTP, fill in `mapTensors_` with the model's tensor names/shapes and quant params, implement the (de)quantization in `execute()`, and (optionally) wire `registerZeroCopyMem_` for DMA inputs.

## Bring-up correctness fixes

Every QNN handle is a `void*` typedef, so argument mistakes on the C API compile cleanly and only surface as runtime failures. These were found and fixed in the bring-up path:

| Fix | Was | Now |
|---|---|---|
| Provider entry point | `dlsym("QnnInterface_getProviderInterface")` — **symbol does not exist**; load would always fail | `dlsym("QnnInterface_getProviders")`, enumerate the list, and select the provider whose `coreApiVersion` matches the compiled headers |
| `deviceCreate` | backend handle passed into the **logger** slot | `deviceCreate(logger=null, config=null, &device)` — there is no backend parameter |
| `contextCreateFromBinary` | arguments **shifted** (device passed as backend, null as device) | `(backend, device, config, binary, size, &context, profile)` in the correct order |
| `nativeStartEngine` (JNI) | a second start move-assigned a new thread onto the live worker → `std::terminate()` | idempotent — a start while running is a no-op returning `true` |

There are **no** standalone `graphFree` (graphs are freed with the context) and `deviceFree` takes a single argument — both reflected in `shutdown()`.
