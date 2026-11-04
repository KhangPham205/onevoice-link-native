# Model handoff contract (AI / model team → native integration)

> [!IMPORTANT]
> Đây là template lịch sử cho thiết kế một graph S2ST end-to-end chạy trên Oboe,
> chunk 1 giây. App hiện tại không chạy theo thiết kế đó: runtime Hybrid dùng sáu
> QNN context thường trú (`vi-en`/`en-vi` × ASR encoder, Marian encoder, Marian
> decoder), còn RNN-T decoder/joiner, greedy CTC và TTS cả câu chạy trên CPU.
> Chiều dịch do dual-ASR router chọn cho từng câu. Tên graph, tensor contract và
> scale/offset lấy từ `android-app/app/src/main/assets/npu_manifest.json` (sinh
> bằng `tools/models/gen_npu_manifest.py` từ chính các `.bin`); bảng tensor đã
> đối chiếu từ binary nằm ở [HANDOFF_FORM.md](HANDOFF_FORM.md). Kiến trúc hiện tại:
> [ARCHITECTURE.md](ARCHITECTURE.md). Không dùng tên `onevoice_s2st`, chunk 1
> giây hay đường dẫn `.bin` bên dưới để sửa app; danh sách câu hỏi (graph, tensor,
> quantization, golden vector) vẫn dùng được khi nhận model mới.

This is the checklist the AI team follows to hand the model over. **A `.bin` alone is not runnable** — the native layer needs the tensor/graph contract below to bind inputs and outputs. Fill in every 🟨 field and deliver the artifacts in [§1](#1-deliverables-checklist).

> How these fields map to code today: they become one stage entry in `assets/npu_manifest.json` (file, graph name, tensor names/dims/dtype, fixed-point scale/offset), which `core/NpuManifest.kt` parses into a `StageSpec` per stage; `native-core/src/qnn_runtime.cpp` then loads the context and retrieves that graph. The original `qnn_inference.{h,cpp}` this template targeted has been removed.

---

## 1. Deliverables checklist

- [ ] **Context binary** `*.bin` — one per target HTP architecture (see [§3](#3-target-htp-architectures)).
- [ ] **QAIRT / QNN SDK version** used to build it (must load on the app's runtime: the in-tree QAIRT 2.47.0 SDK, QNN API v2.36).
- [ ] **Graph name(s)** exactly as exported ([§4](#4-graph-identity)).
- [ ] **Tensor contract table**, fully filled ([§5](#5-tensor-contract-the-critical-part)).
- [ ] **Audio I/O semantics** ([§6](#6-audio--io-semantics)).
- [ ] **Quantization parameters** (scale/offset per quantized tensor), or confirmation that I/O is float ([§7](#7-quantization)).
- [ ] **The converter/build command(s)** used, for reproducibility ([§8](#8-how-the-bin-is-produced-offline)).
- [ ] **A golden test vector** — one input buffer + expected output (with tolerance) so the integrated path can be validated numerically ([§9](#9-validation--acceptance)).

> **No Python or extra runtime library ships on the device.** The QNN Python tools (`qairt-converter`, `qnn-context-binary-generator`, `qnn-net-run`) are used **offline by the AI team only**; on-device execution is pure C++ via the QNN C API.

---

## 2. What we already provide (so you don't re-do it)

- **Backend `.so`** — `libQnnHtp.so` (+ `libQnnSystem.so`, `libQnnHtpPrepare.so`, HTP stubs) are packaged into the APK and available at `applicationInfo.nativeLibraryDir`. You do **not** ship these.
- **Realtime plumbing** — Kotlin owns utterance capture (`AudioRecord` + VAD), strict half-duplex scheduling and stereo `AudioTrack` playback; host feature frontends and decoders run around each graph. (The Oboe/ring-buffer path this template assumed is unreachable legacy code.)
- **Load path** — the app `dlopen`s the backend and calls `contextCreateFromBinary(.bin)` → `graphRetrieve(graphName)`. No on-device graph compilation.

---

## 3. Target HTP architecture(s)

A `.bin` is compiled for a specific Hexagon version. Tell us which SoC(s)/arch(es) to target; deliver one `.bin` per arch (the CPU-side stubs for v68–v81 are already packaged). Today the app targets only Snapdragon 8 Gen 2 (SM8550, Hexagon v73, `socModel` 43) and builds only `arm64-v8a`.

| Snapdragon (example) | Hexagon arch | Stub in APK | Provide `.bin`? |
|---|---|---|---|
| 8 Gen 2 | v73 | ✅ | 🟨 |
| 8 Gen 3 | v75 | ✅ | 🟨 |
| 8 Elite (Gen 4) | v79 / v81 | ✅ | 🟨 |
| _target device for the demo_ | 🟨 | | 🟨 (**required**) |

**File-naming convention:** `onevoice_s2st_htp-v<arch>.bin` (e.g. `onevoice_s2st_htp-v75.bin`).

---

## 4. Graph identity

| Field | Value |
|---|---|
| Number of graphs in the context | 🟨 (1 end-to-end, or N for a cascade) |
| Graph name(s), in call order | 🟨 (exact export strings) |
| If cascade (e.g. ASR → MT → TTS): which graph in which `.bin` | 🟨 |
| Is the graph **stateful** across chunks? (KV-cache / recurrent tensors) | 🟨 yes/no — if yes, describe the state tensors and reset semantics |

> This template originally assumed one graph named `onevoice_s2st`. Today every stage's graph name is read from the binary into `npu_manifest.json`; graph names are not guessable from filenames.

---

## 5. Tensor contract (the critical part)

For **every** input and output tensor of each graph. Shapes **must be static** (confirmed by your team). Dimensions in QNN order (typically NHWC / N-last-dim as exported).

**Inputs** (`QNN_TENSOR_TYPE_APP_WRITE`):

| # | Exact name | Dims (static) | Data type | Quant (scale, offset) | Meaning / units |
|---|---|---|---|---|---|
| 0 | 🟨 | 🟨 e.g. `[1, 16000]` | 🟨 | 🟨 | 🟨 e.g. raw PCM, mono |
| … | | | | | |

**Outputs** (`QNN_TENSOR_TYPE_APP_READ`):

| # | Exact name | Dims (static) | Data type | Quant (scale, offset) | Meaning / units |
|---|---|---|---|---|---|
| 0 | 🟨 | 🟨 e.g. `[1, 16000]` | 🟨 | 🟨 | 🟨 e.g. synthesized PCM |
| … | | | | | |

**Data-type field** — use the QNN enum name:

| If the tensor is… | `dataType` |
|---|---|
| 32-bit float | `QNN_DATATYPE_FLOAT_32` |
| 16-bit float | `QNN_DATATYPE_FLOAT_16` |
| unsigned 8-bit quantized | `QNN_DATATYPE_UFIXED_POINT_8` |
| signed 8-bit quantized | `QNN_DATATYPE_SFIXED_POINT_8` |
| unsigned/signed 16-bit quantized | `QNN_DATATYPE_UFIXED_POINT_16` / `QNN_DATATYPE_SFIXED_POINT_16` |
| plain int32 | `QNN_DATATYPE_INT_32` |

---

## 6. Audio & I/O semantics

| Question | Answer |
|---|---|
| **Input** format the graph expects | 🟨 raw PCM `int16`? float `[-1, 1]`? mel/features? |
| If input is **features**, is extraction baked into the graph? | 🟨 (**strongly preferred** — otherwise we need the exact feature spec: window, hop, n_mels, etc.) |
| Input sample rate / channels | 🟨 (we currently capture **16 kHz mono**) |
| Samples per inference (chunk) | 🟨 (today: one bounded utterance of up to 10 s; the ASR encoders take `[1,1000,80]` feature frames computed on the host) |
| **Output** format | 🟨 PCM `int16`? float? |
| Output sample rate / channels | 🟨 (today's playback is 24 kHz stereo; other TTS rates are resampled to 24 kHz) |
| Output length per chunk (static) | 🟨 (fixed # samples, or variable → we need a length/EOS signal) |
| Source & target **languages** for S2ST | 🟨 (fixed pair? or an input tensor selects language?) |

---

## 7. Quantization

If any I/O tensor is quantized, we must (de)quantize between `int16` PCM and the model's format in `execute()`.

- Provide **scale** (float) and **offset** (int32) per quantized I/O tensor (in the [§5](#5-tensor-contract-the-critical-part) table).
- Confirm the convention. QNN affine: `real = scale × (quantized − offset)` and `quantized = round(real / scale) + offset`. 🟨 confirm this matches your export.
- **Simplest option (recommended for v1):** export the model with **float32 (or float16) input and output** so the app does zero quant math — just a straight copy/convert to PCM. Tell us if this is acceptable.

---

## 8. How the `.bin` is produced (offline)

For reproducibility, record the exact pipeline you ran (we do not run these on device):

```bash
# 1) Convert the source model → QNN IR / .cpp+.bin (or DLC)
qairt-converter --input_network <model> --output_path onevoice_s2st ...

# 2) (If quantized) quantize with a representative calibration set
qairt-quantizer --input_dlc onevoice_s2st.dlc --input_list calib.txt ...

# 3) Generate the HTP context binary for a target arch
qnn-context-binary-generator \
    --model libQnnModelDlc.so --backend libQnnHtp.so \
    --binary_file onevoice_s2st_htp-v75 \
    --config <htp_soc_v75.json>

# 4) Sanity-check on device BEFORE handoff
qnn-net-run --backend libQnnHtp.so --retrieve_context onevoice_s2st_htp-v75.bin \
    --input_list test_inputs.txt
```

Deliver: 🟨 the actual commands + the QAIRT/QNN version string (`qnn-net-run --version`).

---

## 9. Validation & acceptance

- [ ] `qnn-net-run` runs the `.bin` on the target device without error (attach the console log).
- [ ] **Golden vector:** one input file (raw bytes, matching [§5](#5-tensor-contract-the-critical-part) dims/dtype) + the expected output file, and a tolerance (e.g. max abs error for float, or exact for int). This lets us assert the integrated C++ path produces the same numbers as your reference — the fastest way to catch a wiring mistake without a full audio demo.
- [ ] Confirm on-device latency budget per chunk (target: ≤ chunk duration so the pipeline keeps up in realtime).

---

## 10. Where the files go at runtime

- The **`.bin` is not committed to git** (large). Today the app reads each context from internal `<filesDir>/models/npu/<direction>/`, staged with `run-as` by `tools/models/push-npu-models.sh` (see [NPU_DEPLOY.md](NPU_DEPLOY.md)).
- File and graph names come from `npu_manifest.json`; regenerate it with `tools/models/gen_npu_manifest.py` for every model drop.

---

## Copy-paste handoff summary (fill this in and send back)

```
QAIRT/QNN version : ____________________
Target HTP arch(es): ____________________   .bin file(s): ____________________
Graph name(s)     : ____________________   (cascade? ______  stateful? ______)

INPUT tensors
  0: name=____________  dims=__________  dtype=______________  scale/offset=________  meaning=________
  ...
OUTPUT tensors
  0: name=____________  dims=__________  dtype=______________  scale/offset=________  meaning=________
  ...

Audio: in=______(PCM16/float/features)  rate/ch=__________  chunk=______ samples
       out=______(PCM16/float)          rate/ch=__________  out length=______ samples
Languages (src→tgt): ____________________

Golden vector attached: [ ] input  [ ] expected output  tolerance=________
qnn-net-run on-device log attached: [ ]
```
