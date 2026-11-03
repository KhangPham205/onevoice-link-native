# Qualcomm AI Hub — Tài liệu trình bày nội bộ

> Tìm hiểu repo **github.com/qualcomm/ai-hub-models** và hệ sinh thái Qualcomm AI Hub.
> Mục đích: hiểu nó **ra đời để làm gì** và **dùng nó thế nào** cho OneVoice AI Challenge 2026.
> Ngữ cảnh: xem `COMPETITION_BRIEF.md` (cuộc thi Edge AI dịch thuật, on-device, internet = DQ, 2/5 giám khảo từ Qualcomm).

---

## 0. TL;DR — đọc 30 giây

- `ai-hub-models` **KHÔNG phải** một thư viện chạy model trên điện thoại. Nó là **bộ "công thức" (recipes) mã nguồn mở** giúp lấy model PyTorch/ONNX → **biên dịch + lượng tử hoá (quantize) + đo hiệu năng trên thiết bị Snapdragon thật** → ra file chạy được offline trên NPU.
- Phần "chạy trên thiết bị thật" diễn ra trên **đám mây device farm của Qualcomm** (gọi qua `qai-hub`). Tức là: **muốn biết model chạy bao nhiêu ms trên Snapdragon 8 Elite mà KHÔNG cần mua máy** — đây chính là công cụ.
- Nó ra đời (MWC 2/2024) để giải quyết **nỗi đau "port model lên edge cực khổ"**: quá nhiều chip, quá nhiều runtime, quantize thủ công. Khẩu hiệu chiến lược của Qualcomm: **"develop once, deploy everywhere"**.
- **Với cuộc thi:** đây gần như là **đường ray bắt buộc** để biến pipeline ASR→MT→TTS thành thứ chạy <500ms offline trên Snapdragon, và là cách "nói cùng ngôn ngữ" với 2 giám khảo Qualcomm.
- **Cảnh báo quan trọng:** model dựng sẵn của họ phủ EN/ZH, **KHÔNG có tiếng Việt và tiếng Hàn**. Ta không tải về dùng ngay được — ta phải **mang model của mình (PhoWhisper, NLLB, Piper-vi…) và tự "đẩy" qua Workbench**. AI Hub là *đường ống*, không phải *kho hàng có sẵn tiếng Việt*.

---

## 1. AI Hub là gì — và vì sao dễ confuse (gỡ rối tên gọi trước)

**Nguồn gốc của sự rối:** Qualcomm đặt mấy cái tên gần giống hệt nhau cho mấy thứ khác nhau. Tách bạch các tên này ra là hết confuse:

| Tên hay gặp | Thực chất nó là gì | Chạm vào nó bằng |
|---|---|---|
| **Qualcomm AI Hub** (hay gọi là **"Workbench"**) | **Dịch vụ đám mây** — server + dàn thiết bị Snapdragon thật. Nơi MỌI việc compile/quantize/đo thực sự diễn ra | Tài khoản + **API token** trên aihub.qualcomm.com |
| **`qai-hub`** | **Pip package = client** để gọi dịch vụ cloud ở trên | `pip install qai-hub` |
| **`ai-hub-models`** (= "AI Hub Models") ← *repo ta nghiên cứu* | **Repo mã nguồn mở** — bộ "công thức" + ~175 model dựng sẵn. Code chạy ở MÁY TA | `pip install qai-hub-models` / clone GitHub |
| **`ai-hub-apps`** (= "AI Hub Apps") | **Repo mã nguồn mở** — app native mẫu (Android/Windows/Linux) để nhúng model vào ứng dụng | clone GitHub |

→ Hai dòng đầu (**Workbench + client `qai-hub`**) là **một cặp**: hạ tầng cloud + cách gọi nó. Hai dòng sau là **hai repo trên GitHub** chứa code mẫu.

### Khác nhau cốt lõi: repo nằm TRÊN, Workbench nằm DƯỚI — và repo gọi xuống Workbench

Đây là điểm then chốt: 3 mảnh này **KHÔNG ngang hàng** — chúng **xếp tầng**, tầng trên gọi xuống tầng dưới:

```
        ┌─────────────────────────────────────────────┐
TẦNG 3  │  ai-hub-models (repo)   ai-hub-apps (repo)   │  ← TIỆN ÍCH, mã nguồn mở,
        │  "sách công thức+model"   "app mẫu"          │     CHẠY Ở MÁY TA, TUỲ CHỌN
        └───────────────────┬─────────────────────────┘
                            │ gọi xuống (import qai_hub)
                            ▼
        ┌─────────────────────────────────────────────┐
TẦNG 2  │  qai-hub (client — pip package)             │  ← cách nói chuyện với cloud
        └───────────────────┬─────────────────────────┘
                            │ gửi job qua internet (cần API token)
                            ▼
        ┌─────────────────────────────────────────────┐
TẦNG 1  │  ☁️ QUALCOMM AI HUB / WORKBENCH (cloud)      │  ← HẠ TẦNG THẬT, BẮT BUỘC
        │     server + device farm Snapdragon thật     │     mọi compile/đo xảy ra ở đây
        └─────────────────────────────────────────────┘
```

**Repo `ai-hub-models` làm gì?** Nó là **quyển sách công thức + tủ nguyên liệu mẫu**. Với ~175 model phổ biến (Whisper, Llama, Stable Diffusion…), Qualcomm viết sẵn `export.py` / `demo.py`. Khi ta chạy `export.py`, **bản thân repo không tự làm gì** — nó chỉ đóng gói model rồi **gọi xuống `qai-hub` để nhờ Workbench làm việc thật**. Bỏ repo đi ta vẫn export được model — chỉ là phải tự viết script. Repo = tiện lợi + ví dụ để học, **KHÔNG bắt buộc**.

**Workbench làm gì?** Nó là **cái bếp/nhà máy đám mây thật**. Đây là nơi duy nhất model được compile, quantize, và **chạy trên điện thoại Snapdragon thật để đo**. Không có Workbench thì không có gì xảy ra. Nó **bắt buộc**, và là thứ cần **API token** (vì ta đang mượn máy + tính toán của Qualcomm).

### So sánh trực diện (đọc cái này là hết confuse)

| | ☁️ Workbench (Qualcomm AI Hub) | 📦 Repo `ai-hub-models` |
|---|---|---|
| Bản chất | Dịch vụ cloud (server + device farm) | Thư viện Python mã nguồn mở |
| Chạy ở đâu | Trên cloud của Qualcomm | Trên máy của ta |
| Cần API token? | **Có** | Bản thân không — nhưng nó gọi xuống Workbench nên rốt cuộc vẫn cần |
| Có thiết bị thật để đo? | **Có** (đây chính là nó) | Không — nó đi mượn của Workbench |
| Chứa model dựng sẵn? | Không (là hạ tầng) | **Có** — ~175 model + công thức |
| Bắt buộc cho dự án? | **Bắt buộc** | Tuỳ chọn (chỉ để học/tiện) |
| Mã nguồn mở? | Không | Có (BSD-3) |
| Với ta | Nơi compile/đo PhoWhisper, NLLB… **của chính ta** | Đọc ví dụ Whisper để học cách viết export cho model của ta |

> **Một câu chốt:** *Workbench là cái bếp đám mây (bắt buộc, cần token, làm mọi việc thật). `ai-hub-models` là quyển sách công thức 175 món có sẵn (tuỳ chọn, để học và nấu nhanh món phổ biến). Vì món của ta — tiếng Việt/Hàn — KHÔNG có trong sách, ta tự viết công thức rồi vẫn mang vào đúng cái bếp đó.* (`ai-hub-apps` là tập ví dụ "dọn món ra bàn" — khung app để nhúng model, dùng ở cuối khi làm demo.)

> Trích README repo: *"The Qualcomm AI Hub Models are a collection of state-of-the-art machine learning models optimized for deployment on Qualcomm devices."*
> Trích docs Workbench: AI Hub *"helps to optimize, validate, and deploy machine learning models on-device for vision, audio, speech, and multi-modal use cases."*

---

## 2. Tại sao nó ra đời? (Đây là phần "WHY" được yêu cầu)

### 2.1. Bối cảnh: công bố tại MWC, Barcelona — tháng 2/2024
Qualcomm ra mắt AI Hub với **75+ model** AI/GenAI tối ưu sẵn (Whisper, Stable Diffusion, ControlNet, các LLM…). Lời của Durga Malladi (Qualcomm): cung cấp cho lập trình viên *"a comprehensive AI model library to quickly and easily integrate pre-optimized AI models into their applications, leading to faster, more reliable and private user experiences."*

### 2.2. Nỗi đau kỹ thuật nó giải quyết — "model fragmentation"
Trước AI Hub, đưa một model AI lên thiết bị edge là một cơn ác mộng kỹ thuật:

```
Vấn đề CŨ (mỗi mũi tên = một chỗ có thể vỡ):
  PyTorch model
     │  convert thủ công
     ▼
  ONNX / TFLite                  ← chọn runtime nào? QNN? TFLite? ONNX RT?
     │  quantize thủ công (INT8/INT16) — dễ tụt accuracy nếu làm sai
     ▼
  Chạy trên chip nào?            ← 8 Gen 1/2/3, 8 Elite, X Elite, automotive, IoT…
     │  mỗi chip một profile khác
     ▼
  Đo latency THẬT?               ← phải MUA từng thiết bị, tự cắm dây, tự đo
```

→ Mỗi nền tảng phần cứng đòi một lần tối ưu lại. Lập trình viên phải tự lo **convert + quantize + chọn runtime + tìm máy thật để đo**. Rất tốn thời gian, dễ sai, không reproducible.

**AI Hub gói toàn bộ chuỗi đó thành dịch vụ:** một lệnh → nó tự compile, tự quantize (dùng **AIMET** bên dưới), tự chạy trên **thiết bị thật trong đám mây**, trả về latency theo từng lớp + bộ nhớ đỉnh, và đưa link tải model đã tối ưu.

### 2.3. Đặt trong chiến lược lớn của Qualcomm: "develop once, deploy everywhere"
AI Hub là tầng "thân thiện lập trình viên" của **Qualcomm AI Stack**. Bên dưới là các công cụ "trần trụi" hơn:
- **QNN / Qualcomm AI Engine Direct** — runtime cấp thấp nói chuyện trực tiếp với Hexagon NPU.
- **AIMET (AI Model Efficiency Toolkit)** — bộ quantize/nén model (AI Hub gọi AIMET ở hậu trường khi chạy quantize job).

Giá trị cốt lõi (theo phân tích chiến lược): *"develop once, deploy everywhere"* — viết một lần, chạy trên mọi chip Qualcomm, xoá bỏ sự phân mảnh phần cứng/phần mềm.

### 2.4. Động cơ kinh doanh (đừng ngây thơ — hiểu để tận dụng)
- **Bán nhiều chip Snapdragon hơn:** càng nhiều app AI on-device → càng nhiều lý do mua điện thoại Snapdragon. AI Hub là "mồi nhử" hệ sinh thái.
- **Khoá chân lập trình viên (stickiness):** AIMET + AI Hub hạ rào cản gia nhập → tạo ra một hệ sinh thái "dính", nuôi lòng trung thành.
- **Đẩy làn sóng on-device AI:** Qualcomm cược rằng tương lai AI là **chạy trên thiết bị** (riêng tư hơn, hoạt động offline, độ trễ thấp, tiết kiệm chi phí server), không phải chỉ ở cloud.

> **Hệ quả cho cuộc thi:** Bản chất "on-device, offline" mà Qualcomm cổ vũ **trùng khít** với luật chơi OneVoice (internet = DQ). Cuộc thi này về cơ bản là một "showcase" cho tầm nhìn on-device AI của Qualcomm. Đi đúng đường ray AI Hub = đi đúng thứ giám khảo Qualcomm muốn thấy.

---

## 3. Nó hoạt động thế nào (cơ chế thực tế)

![Sơ đồ luồng Qualcomm AI Hub Workbench: Upload model → Compile · Validate · Profile · Quantize → Download](assets/workbench-flow.png)

*Sơ đồ chính thức của Qualcomm (nguồn: aihub.qualcomm.com/get-started). Đọc trái → phải: **Upload** model custom (PyTorch/ONNX) → vòng lặp trên cloud **Compile → Validate on device → Profile on device → Quantize** → **Download** model đã tối ưu (ONNX Runtime / LiteRT / Qualcomm AI Runtime). Toàn bộ các mục 3.1–3.5 bên dưới chỉ là giải thích chi tiết một hình này.*

### 3.1. Hai gói Python — đừng nhầm
| Gói | Là gì |
|---|---|
| `qai-hub` | **Client** gọi dịch vụ đám mây Workbench (submit compile/profile/inference job). Cần **API token**. |
| `qai_hub_models` | Bộ **recipe + model** mã nguồn mở (repo ta đang xem). Mỗi model có sẵn `export.py` và `demo.py`. |

### 3.2. Luồng làm việc end-to-end (ví dụ Whisper)
```bash
pip install qai_hub_models
qai-hub configure --api_token <API_TOKEN>     # lấy token ở account settings trên aihub.qualcomm.com

# Một lệnh làm tất cả:
python -m qai_hub_models.models.whisper_small_v2.export \
       --target-runtime qnn --device "Snapdragon 8 Elite QRD"
```
Lệnh `export` tự động chạy 5 bước:
1. **Compile** model cho thiết bị + runtime đã chọn.
2. **Quantize** (nếu áp dụng) — INT8/INT16 qua AIMET.
3. **Profile trên thiết bị THẬT** lấy từ device farm đám mây → trả về latency trung bình **theo từng lớp**, ánh xạ lớp nào chạy trên CPU/GPU/NPU, và **bộ nhớ đỉnh**.
4. **Validate**: so output trên thiết bị vs PyTorch gốc (kiểm tra quantize có làm hỏng không).
5. **Download** model đã tối ưu về máy.

> Điểm vàng cho cuộc thi: **device farm**. Trích docs: *"The system automatically provisions devices in the cloud for on-device profiling and inference."* → Ta **đo được latency thật trên Snapdragon 8 Elite mà không cần mua máy**. Đúng thứ rubric chấm (RTF < 1.0, turnaround < 2.0s).

### 3.3. Local ↔ Cloud — chính xác thì code chạy ở đâu? (điểm dễ hiểu nhầm)

Workbench hoạt động theo cơ chế **hai phần phối hợp**: máy ta là "người điều khiển", còn thiết bị thật nằm trên cloud của Qualcomm.

```
MÁY CỦA BẠN (Local)                         CLOUD QUALCOMM (Device Farm)
─────────────────────────                   ──────────────────────────────────
 import qai_hub                              
 load model (vd MobileNet/Whisper)           
 job = hub.submit_compile_job(...)  ──gửi──▶  Nạp model vào MỘT MÁY THẬT
 job = hub.submit_profile_job(...)           (vd Samsung Galaxy S25 / SD 8 Elite)
 job = hub.submit_inference_job(...)          → biên dịch · chạy thử · đo hiệu năng
                                              → trạng thái + số liệu hiện trên
        ▲                                       Web Dashboard (đang chạy/xong/lỗi)
        │                                       (tốc độ, RAM, mức dùng NPU…)
 results = job.download_output_data() ◀─trả──  Kết quả + model tối ưu trả ngược về
 # hậu xử lý tiếp trên máy bạn
```

**Diễn giải:**
1. **Code chạy ở Local:** toàn bộ đoạn Python (import thư viện, load model, gọi `submit_*`) chạy trên máy/Notebook/IDE của bạn. Máy bạn là **nơi điều khiển và gửi yêu cầu**.
2. **Xử lý trên Cloud:** mỗi hàm có chữ `submit` (`submit_compile_job`, `submit_profile_job`, `submit_inference_job`) sẽ **đóng gói model và gửi lên đám mây Qualcomm**; model được **nạp vào một thiết bị thật trong Device Farm** (ví dụ Samsung Galaxy S25) để compile / inference / profile.
3. **Theo dõi trên Web:** trạng thái (đang xử lý / hoàn thành / lỗi) cùng số liệu chi tiết (tốc độ, dung lượng RAM, mức tiêu thụ NPU) hiển thị trực tiếp trên **Web Dashboard** của bạn.
4. **Trả ngược về Local:** xong thì kết quả được trả về máy bạn (vd `inference_job.download_output_data()`) để **hậu xử lý tiếp**.

![Web Dashboard của AI Hub — kết quả một Inference Job trên thiết bị thật](assets/inference-output.png)

*Web Dashboard thật (mục 3 ở trên trông như thế này): kết quả một **Inference Job** chạy trên **Samsung Galaxy S25 / Snapdragon 8 Elite** — thời gian inference **3.1 ms**, bộ nhớ đỉnh **0–30 MB**, kèm phiên bản TFLite/QAIRT/Android. Đây đúng là màn hình hiện ra khi `submit_inference_job` chạy xong.*

> Hệ quả cho cuộc thi: bước `submit_*` **cần internet** vì gọi cloud — nhưng đây là lúc **build/đo**, không phải lúc demo. Sản phẩm cuối tải về (`download_output_data` / model đã tối ưu) chạy **100% offline trên thiết bị** → không vi phạm luật "internet = DQ".

**📑 Ba loại job trên Workbench cloud + "ai thực sự COMPILE?"**

Mọi việc "nặng" đều là **job gửi lên Workbench**. Code dưới máy chỉ *gọi* hàm `submit_*`, *chờ*, rồi *tải kết quả về*.

| Job | Lệnh (client `qai-hub`) | Cloud làm gì | Trả về gì |
|---|---|---|---|
| **Compile** | `hub.submit_compile_job(...)` | Biên dịch đồ thị → **QNN context binary / TFLite** tối ưu cho chip đích | File model đã tối ưu (`get_target_model()` → `.bin/.tflite`) |
| **Profile** | `hub.submit_profile_job(...)` | Nạp model đã compile vào **1 thiết bị THẬT** trong device farm rồi đo | Latency (ms), peak memory, **số layer chạy NPU/CPU/GPU** |
| **Inference** | `hub.submit_inference_job(...)` | Chạy model trên thiết bị thật **với data bạn gửi lên** | Tensor output thật (để **so accuracy với PyTorch gốc**) |
| *(Quantize)* | `hub.submit_quantize_job(...)` | Lượng tử hoá ONNX qua **AIMET** (cần data calibrate) | Model ONNX đã quantize (INT8/INT16) |

> Có thể gộp: `submit_compile_and_profile_jobs(...)` chạy compile xong profile liền. Mỗi job đều hiện trạng thái (đang chạy/xong/lỗi) trên **Web Dashboard**.

**Ai thực sự compile? → Cả repo lẫn thư viện đều KHÔNG. Cloud mới compile.**

```
DƯỚI MÁY BẠN (chuẩn bị + điều phối)             TRÊN CLOUD (làm thật)
─────────────────────────────────              ─────────────────────────────
• Code REPO (export.py / model.py):             • Trình biên dịch QNN / QAIRT
    - phẫu thuật kiến trúc (tách enc/dec…)          → biến đồ thị thành binary cho HTP
    - torch.jit.trace → đồ thị tĩnh              • AIMET → quantize / calibrate
    - GỌI hub.submit_compile_job(...)            • THIẾT BỊ THẬT (Galaxy S25…)
• Thư viện qai-hub (client):                        → chạy để profile / inference
    - đóng gói · upload · poll · download
        │  (gửi lên) ────────────────────────────▶  ◀──────── (trả binary + số liệu về)
```

- **Code repo (`qai-hub-models`)** = *chuẩn bị model* (surgery + trace) + *quyết định gọi job nào*. **Không tự dịch ra mã máy NPU.**
- **Thư viện `qai-hub`** = *vận chuyển* (đóng gói, upload, theo dõi, tải về). Cũng **không compile** — chỉ là client.
- **Workbench cloud** = nơi **trình biên dịch QNN thật sự chạy** (cùng AIMET + thiết bị thật).

→ `export` của repo và `submit_compile_job` chỉ *kích hoạt*; **compile thật diễn ra trên cloud** → bước này **cần internet + API token** (mục 3.3 ở trên). File `.bin/.tflite` tải về sau đó mới chạy 100% offline.

**"Model CÓ SẴN trong repo" vs "KHÔNG có" — khác ở ĐÂU?** Chỉ khác **phần chuẩn bị dưới máy**; phần cloud (compile/profile/inference) **y hệt nhau**:

| | CÓ SẴN (Whisper, MobileNet, OpusMT En↔Zh…) | KHÔNG có (PhoWhisper, NLLB Vi↔Ko, Piper-vi) |
|---|---|---|
| Surgery + trace | **Repo viết sẵn** trong `model.py`/`export.py` | **Bạn tự viết** (hoặc mượn recipe rồi thay weight) |
| Lệnh chạy | `python -m qai_hub_models.models.<model>.export` (1 lệnh) | Tự code: `torch.jit.trace` → `submit_compile_job` (§3.5) |
| Cài gì | `qai_hub_models[<model>]` | `qai-hub[torch]` |
| Job cloud | compile + profile + inference (**như nhau**) | compile + profile + inference (**như nhau**) |
| Công sức | 🟢 gần như chỉ cần token | 🟡 phải tự lo export/trace cho đúng |

> **Mẹo OneVoice:** PhoWhisper = Whisper-small fine-tuned → **mượn nguyên recipe Whisper, chỉ thay weight** → hưởng sẵn phần surgery (tách encoder/decoder, KV-cache) repo đã làm (§4.1), khỏi tự viết lại tầng tối ưu kiến trúc.

### 3.4. Ba runtime đích & phần cứng
- **Runtime:** Qualcomm AI Engine Direct (**QNN** — nhanh nhất, sát NPU) · **LiteRT/TFLite** · **ONNX Runtime (QNN Execution Provider)**.
- **Hệ điều hành:** Android, Linux, Windows.
- **Chip:** Snapdragon **8 Elite (Gen 5)**, 8 Elite, 8 Gen 3/2/1, X/X2 Elite, automotive (SA), IoT (QCS), XR (QCS8450).
- **Độ chính xác trên Hexagon NPU:** FP16, INT16, INT8 (8 Elite còn hỗ trợ INT4). Qualcomm tuyên bố tăng tốc AI ~45% so với đời trước, NPU nhanh ~**100× so với CPU**, ~10× so với GPU.

> Lưu ý "internet = DQ": ràng buộc offline áp cho **giai đoạn test/inference** (lúc demo chạy thật). Quá trình **build** (gọi đám mây để compile/đo) dùng internet là hoàn toàn hợp lệ — sản phẩm cuối là file `.bin/.so/.tflite` chạy 100% offline trên máy.

### 3.5. Quy trình 3 bước đưa mô hình CUSTOM của ta lên chip (bring-your-own-model)

> Mục 3.2 dùng lệnh `export` cho model **có sẵn trong repo**. Còn **model fine-tune của ta** (PhoWhisper-vi, NLLB Vi↔Ko, Piper-vi…) — thứ KHÔNG có sẵn (xem khoảng trống ở §5.2) — đi theo API cấp thấp `qai_hub` qua **3 bước** sau. Đây là quy trình ta sẽ thực sự dùng trong Phase 2.

```
[Bước 1: TRACE]            [Bước 2: COMPILE]              [Bước 3: PROFILE & DEPLOY]
model .pt động (Python)    gửi đồ thị tĩnh lên Workbench   (a) profile trên S25 thật (cloud) → đo ms/RAM/NPU
   │ torch.jit.trace          │ submit_compile_job          (b) tải .tflite/.bin → nhúng vào app, chạy
   ▼                          ▼  (chip không hiểu Python)        offline trên thiết bị người dùng cuối
đồ thị tĩnh (static graph) .tflite / QNN tối ưu cho NPU      qua Qualcomm AI Stack SDK / TFLite Runtime
```

**Bước 1 — Trace: "đóng băng" model thành đồ thị tĩnh.**
Model deep learning thường (file `.pt` PyTorch) chứa code Python **động**. Chip Snapdragon không hiểu Python — nó cần một **đồ thị tính toán tĩnh (static graph)**. Ta "trace" model dưới máy local trước:
```python
import torch

# 1. Load model fine-tune Việt-Hàn custom của ta ở local
my_custom_model = LoadYourVietKoreanModel()
my_custom_model.eval()

# 2. Tạo input mẫu ĐÚNG kích thước model cần:
#    - Vision: tensor ảnh; - ASR: đặc trưng audio (mel-spectrogram);
#    - MT/Embedding: tensor chỉ số token (token indices)
example_input = torch.rand(1, 3, 224, 224)   # ví dụ minh hoạ cho Vision

# 3. Trace model thành đồ thị tĩnh
traced_model = torch.jit.trace(my_custom_model, example_input)
```
> Nếu model của ta đã xuất ra `.onnx` từ trước, **Workbench nhận trực tiếp file ONNX** — bỏ qua bước Torch JIT này. (Pipeline của ta thường export ONNX rồi → đây là đường thuận tiện.)

**Bước 2 — Compile: biên dịch sang định dạng của chip.**
Gửi model đã trace lên Workbench. Workbench đóng vai "thợ dịch", chuyển đồ thị tính toán sang **TFLite (hoặc QNN)** tối ưu riêng cho NPU/GPU của dòng Snapdragon trên thiết bị mục tiêu:
```python
import qai_hub as hub

device = hub.Device("Samsung Galaxy S25 (Family)")

compile_job = hub.submit_compile_job(
    model=traced_model,                        # model custom vừa trace ở Bước 1
    device=device,
    input_specs=dict(image=(1, 3, 224, 224)),  # khai báo đầu vào (đổi theo ASR/MT của ta)
    options="--target_runtime tflite",          # hoặc "qnn" tuỳ runtime đã chọn
)

optimized_model = compile_job.get_target_model()  # tải model đã tối ưu hoàn toàn cho chip về máy
```

**Bước 2.5 — Quantize: `calibration_data` là mắt xích quyết định độ chính xác (đọc kỹ).**

> ⚠️ **Đính chính kỹ thuật để nói cho đúng trước giám khảo Qualcomm:** `calibration_data` **KHÔNG phải tham số của `submit_compile_job`** — nó là tham số của một job riêng, **`submit_quantize_job`**, chạy **TRƯỚC** compile. Trong pipeline build muốn ra INT8/INT16, thứ tự đúng là **Quantize → Compile**:

```python
# (A) LƯỢNG TỬ HOÁ — cần calibration_data (đây là chỗ ta tập trung)
quantize_job = hub.submit_quantize_job(
    model=onnx_model,                            # model FP32 (ONNX) của ta
    calibration_data=calib_dataset,              # ← NHÂN VẬT CHÍNH (giải thích bên dưới)
    weights_dtype=hub.QuantizeDtype.INT8,        # trọng số → INT8
    activations_dtype=hub.QuantizeDtype.INT16,   # activation → INT16  (= w8a16, an toàn accuracy hơn w8a8)
)
quantized_model = quantize_job.get_target_model()   # ONNX đã gắn scale/zero-point

# (B) COMPILE — lúc này mới đưa model ĐÃ quantize xuống chip
compile_job = hub.submit_compile_job(
    model=quantized_model,
    device=hub.Device("Samsung Galaxy S25 (Family)"),
    options="--target_runtime qnn",
)
```

**`calibration_data=calib_dataset` đóng vai trò gì?**
Lượng tử hoá = ánh xạ số thực FP32 → số nguyên INT8/INT16. Để ánh xạ không phá model, AIMET phải biết **mỗi lớp thực tế cho ra giá trị nằm trong khoảng nào**. Nó **không đoán** — nó **chạy thử model trên `calib_dataset`**, quan sát phân bố activation thật ở từng lớp, rồi tính **scale + zero-point** (công thức ánh xạ FP↔INT). Trích docs Qualcomm:
> *"quantized models need to be calibrated with unlabeled sample input data. Calibration is the process of determining the fixed point mapping (scales and zero points) between floating point and its quantized integer representation."*

Các điểm phải nắm:
- **Định dạng:** `calib_dataset` là `Dataset` / `Mapping[str, list[ndarray]]` / dataset-ID → thực chất là **dict `{tên_input: [mảng numpy mẫu, …]}`**, đúng định dạng input model nhận (ASR: mel-spectrogram; MT: token indices; TTS: text/phoneme ids).
- **KHÔNG cần nhãn** (unlabeled) — chỉ cần input đại diện, không cần ground-truth.
- **Số mẫu:** docs khuyến nghị **500–1000 mẫu** (ví dụ chính thức dùng 100). Quá ít → khoảng ước lượng lệch; quá nhiều → bão hoà lợi ích, tốn thời gian.
- **PHẢI đại diện cho input thật lúc chạy** — điểm sống còn: nếu `calib_dataset` lệch phân bố so với dữ liệu thật, scale/zero-point bị sai → activation thật bị **clip/bão hoà** → **accuracy sụp** (BLEU/WER tụt mạnh) dù compile vẫn báo "thành công".

**Hệ quả trực tiếp cho OneVoice (rất quan trọng — đây là chỗ "dữ liệu thật" biến thành điểm):**
- **ASR:** calib phải là **mel-spectrogram từ audio tiếng Việt/Hàn thật**, có giọng/nhiễu giống lúc demo — đừng calibrate model tiếng Việt bằng audio tiếng Anh sạch.
- **MT:** dùng **câu thật trong miền công nghiệp** (van bi, logistics…) — calibrate bằng câu phổ thông sẽ làm hỏng đúng phần từ vựng ta muốn ăn điểm.
- **TTS:** dùng phoneme/text thật của câu sẽ đọc.
→ Cùng một model: calib tốt thì giữ được accuracy ở INT8/INT16 (nhanh + nhẹ); calib ẩu thì buộc phải lùi về FP16 (chậm hơn, tốn RAM hơn) để cứu accuracy.

**Bước 3 — Profile & đóng gói triển khai (on-device deployment).**
Compile xong ta có file model tối ưu (thường `.tflite` hoặc `.program`). Hai lựa chọn:
- **(a) Test trên cloud trước:** `hub.submit_profile_job(...)` → đẩy vào con S25 thật trên cloud, xem chạy trên NPU mất bao nhiêu mili-giây, có ngốn RAM không. → Đây là số liệu phục vụ rubric latency.
- **(b) Mang về app thật:** tải file đã compile về, **nhúng thẳng vào mã nguồn ứng dụng** (Android App / C++ backend), gọi qua **Qualcomm AI Stack SDK** hoặc **TFLite Runtime** chạy **trực tiếp trên thiết bị người dùng cuối** (100% offline — đúng luật DQ).

> Áp vào pipeline cuộc thi: ta sẽ chạy quy trình 3 bước này **3 lần** — một cho ASR (PhoWhisper), một cho MT (NLLB Vi↔Ko), một cho TTS (Piper-vi) — rồi profile từng khối để biết khối nào là nút thắt latency cần tối ưu.

---

## 4. Repo `ai-hub-models` có gì (catalog liên quan đến ta)

Bản chất: ~175+ model phủ Vision, NLP, Audio/Speech, Generative. Trích đúng 3 mảng dùng cho pipeline **ASR → MT → TTS** của ta:

| Tầng pipeline | Model có sẵn trong repo | Ngôn ngữ phủ sẵn |
|---|---|---|
| **ASR (nói→chữ)** | **Whisper** (Tiny/Base/Small/Medium/**Large-V3-Turbo**), **Distil-Whisper**, **Zipformer** | Đa ngữ (Whisper) — *nhưng tiếng Việt chưa được fine-tune sẵn* |
| **MT (dịch)** | **OpusMT** (En↔Es, En↔Zh), các LLM (Llama 3.x, Qwen2/3, Mistral, Phi-3.5…) | EN/ZH/ES — **KHÔNG có Vi, KHÔNG có Ko** |
| **TTS (chữ→nói)** | **MeloTTS** (EN/ES/ZH), **PiperTTS** (DE/EN/IT) | EN/ZH/ES/DE/IT — **KHÔNG có Vi, KHÔNG có Ko** |

Mỗi model còn có:
- **`App` class** (Python) bọc inference + tiền/hậu xử lý bằng torch/numpy.
- **`demo`**: `python -m qai_hub_models.models.<model>.demo --eval-mode {fp,on-device}` — chạy thử local (PyTorch) hoặc trên thiết bị đám mây.
- Bảng số liệu hiệu năng on-device công khai tại **aihub.qualcomm.com/models**.

### 4.1. "Đã được tối ưu hoá" CỤ THỂ là tối ưu gì? — 4 tầng (dữ liệu thật từ repo)

> Câu "model PyTorch đã tối ưu để chạy trên NPU/HTP Hexagon" rất dễ bị hiểu mơ hồ. Bóc tách thật ra, **"tối ưu" KHÔNG chỉ là "nén nhỏ lại"** — nó là **4 tầng** chồng lên nhau. Dưới đây là bằng chứng lấy trực tiếp từ mã nguồn repo (`src/qai_hub_models/models/whisper_base/`, `_shared/hf_whisper/model.py`, `perf.yaml`) — không phải nói chung chung.

**Tầng 1 — Phẫu thuật KIẾN TRÚC để thành "đồ thị tĩnh" (quan trọng & ít người biết nhất).**
NPU không chạy được code Python động / shape động. Repo **viết lại định nghĩa model** (không chỉ chạy model gốc) để biến nó thành đồ thị tĩnh trace được. Với Whisper, mã nguồn cho thấy 3 can thiệp thật:
- **Tách encoder ↔ decoder thành 2 đồ thị riêng** — hai class `HfWhisperEncoder` và `HfWhisperDecoder` ghi rõ docstring *"optimized for export and inference"*. `perf.yaml` xác nhận model được đo như **2 component tách rời** (`encoder`, `decoder`).
- **Đưa KV-cache ra ngoài thành tensor đầu vào CỐ ĐỊNH SHAPE** — decoder nhận `k_cache_self_*`, `v_cache_self_*`, `k_cache_cross_*`, `v_cache_cross_*` làm input và trả cache mới làm output. Vòng lặp sinh token (autoregressive) vốn là code Python động → được "duỗi thẳng" thành graph tĩnh. Encoder *"directly produce cross attention key and value cache tensors"*.
- **Cố định mọi kích thước** — chunk audio 30s = `N_SAMPLES 480000`, độ dài giải mã `MEAN_DECODE_LEN`, embedding audio `AUDIO_EMB_LEN 1500`. Không còn shape động → trace được, không control-flow trong graph.

**Tầng 2 — Hạ ĐỘ CHÍNH XÁC số học (quantization, qua AIMET).**
- Float variant: chạy **FP16** — bằng chứng là cờ compile thật trong code: `--quantize_full_type float16 --quantize_io`.
- Quantized variant: ví dụ `whisper_small_quantized` chạy precision **`w8a16`** = **weights INT8, activations INT16** (khai báo thật trong `perf.yaml`).
- Cơ chế: **post-training quantization** qua **AIMET**, hiệu chỉnh (calibrate) bằng **500–1000 mẫu** dữ liệu để tìm scale/zero-point; weights→INT8, activations→INT8/INT16. Docs Qualcomm: *"Hexagon Tensor Processor performs best with quantized operations"* → có thể nhanh thêm ~**3×**.

**Tầng 3 — BIÊN DỊCH backend xuống HTP (Workbench/QNN compile).**
Workbench dịch đồ thị thành **QNN context binary** (định dạng máy của Hexagon): operator fusion, lowering, ánh xạ op xuống đơn vị tính NPU. Runtime ghi nhận trong `perf.yaml`: **QAIRT** (Qualcomm AI Runtime).

**Tầng 4 — KẾT QUẢ ĐO THẬT (mục tiêu cuối: 100% chạy trên NPU).**
Số liệu thật trong `perf.yaml` của `whisper_base` (float/FP16, QNN context binary, đo trên device farm):

| Component | Thiết bị | Latency | Bộ nhớ đỉnh | Compute unit | Layer trên NPU |
|---|---|---|---|---|---|
| Encoder | Samsung Galaxy S26 | **22.05 ms** | 0–10 MB | NPU | **556 / 556** |
| Decoder (mỗi bước token) | Samsung Galaxy S26 | **2.67 ms** | 20–29 MB | NPU | **975 / 975** |
| Encoder | Samsung Galaxy S24 | ~36.3 ms | — | NPU | 556 / 556 |

> **Điểm chốt:** chỉ số đắt giá nhất là `layer_counts: npu == total` → **toàn bộ layer chạy trên NPU, 0 layer rơi xuống CPU/GPU**. Đây chính là định nghĩa thực dụng của "đã tối ưu cho Hexagon": không có op nào bị "fallback" về CPU làm nghẽn cổ chai. Một model PyTorch bê nguyên xi thường có hàng loạt op không chạy được trên NPU → tụt về CPU → chậm gấp nhiều lần.

![Profile Job Results thật trên AI Hub — Compute Units = NPU 245](assets/profile-output.png)

*Ảnh chụp **Profile Job Results** thật từ Workbench: model `efficientnet_b0` trên **Galaxy S25 / Snapdragon 8 Elite** → **Minimum Inference Time 0.8 ms**, peak memory **0–36 MB**, và **Compute Units = NPU 245** — tức 245 đơn vị tính đều nằm trên NPU. Đây chính là dạng bằng chứng "100% layer trên NPU" mà bảng số liệu §4.1 nói tới, hiển thị trực quan trên dashboard.*

**Tóm tắt cho buổi họp:** *"tối ưu" = (1) viết lại kiến trúc cho graph tĩnh + KV-cache ngoài, (2) hạ precision FP16/INT8-INT16 bằng AIMET, (3) compile fusion xuống QNN/HTP, (4) đẩy 100% layer lên NPU — và tất cả được đo bằng số liệu thật trên thiết bị thật, không phải lý thuyết.*

---

## 5. Liên hệ trực tiếp với OneVoice — dùng AI Hub thế nào để THẮNG

### 5.1. Map vào roadmap đã có (`COMPETITION_BRIEF.md` Phase 2 — EDGE)
"PHASE 2 — EDGE: export ONNX → quantize INT8 → setup Qualcomm AI Hub → compile cho Snapdragon → latency profiling" — **toàn bộ phase này CHÍNH LÀ AI Hub Workbench**. Tài liệu này lấp đầy chi tiết "làm bằng cách nào".

### 5.2. Cái ta DÙNG ĐƯỢC NGAY vs cái phải TỰ LÀM
```
✅ DÙNG ĐƯỢC NGAY (lấy recipe/model từ repo):
   • Whisper / Distil-Whisper       → khung ASR, học cách export sang QNN
   • Device farm                    → đo latency Snapdragon 8 Elite không cần mua máy
   • AIMET quantize pipeline        → INT8/INT16 có validate accuracy tự động
   • App native mẫu (AI Hub Apps)   → khung app Android cho demo cuối

⚠️ PHẢI TỰ MANG MODEL & TỰ PORT (đây là KHOẢNG TRỐNG = cơ hội tạo khác biệt):
   • ASR tiếng Việt   → PhoWhisper-Small (fine-tune của ta) — repo chỉ có Whisper gốc
   • MT Vi↔En/Ko/Zh   → NLLB-600M / model của ta — OpusMT KHÔNG có cặp Việt/Hàn
   • TTS tiếng Việt    → Piper-vi — repo chỉ có EN/ZH/ES/DE/IT
   • TTS tiếng Hàn     → cần tự tìm/port — repo KHÔNG có Ko
```

> **Đây là insight chiến lược quan trọng nhất:** AI Hub là *đường ống tối ưu hoá*, không phải kho model tiếng Việt/Hàn dựng sẵn. Lợi thế cạnh tranh của ta vẫn nằm ở **model fine-tune domain + cặp Việt-Hàn** (như brief đã chốt). AI Hub giúp ta **chạy chúng nhanh & ổn định trên Snapdragon** — nó khuếch đại lợi thế, không thay thế lợi thế.

> 🔧 **Cách port model của ta lên chip:** theo **quy trình 3 bước Trace → Compile → Profile & Deploy** ở §3.5. Mỗi khối ASR/MT/TTS chạy quy trình này một lần.

### 5.3. Vì sao đi đường AI Hub là lựa chọn ăn điểm (theo rubric)
| Tiêu chí rubric | AI Hub giúp gì |
|---|---|
| **Efficiency & Latency (phần lớn của 50% Technical)** | Profiling trên thiết bị thật → tối ưu RTF<1.0, turnaround<2s có số liệu chứng minh, không đoán mò |
| **On-Device Constraint (DQ nếu cần internet)** | Sản phẩm cuối là binary QNN chạy 100% offline trên NPU — đúng luật |
| **Business Viability (25%)** | "Develop once, deploy everywhere" + chạy trên flagship Snapdragon = câu chuyện thương mại hoá thật, scale được |
| **2/5 giám khảo Qualcomm** | Tối ưu QNN/Hexagon + dùng đúng toolchain của họ = nói đúng "tiếng mẹ đẻ" của ban giám khảo |

### 5.4. Rủi ro & lưu ý kỹ thuật cần nói rõ trong họp
- **Cần API token + tài khoản AI Hub** (miễn phí, đăng ký tại aihub.qualcomm.com). Việc compile/profile chạy trên cloud của Qualcomm → cần internet **lúc build** (không phải lúc demo).
- **Quantize có thể tụt accuracy** (BLEU/WER) — bước validate vs PyTorch của AI Hub giúp phát hiện sớm; phải có ngân sách thời gian để hiệu chỉnh.
- **Model lớn (LLM/NLLB-600M) cần chia/khéo** để vừa NPU; bám sát budget RAM ~3–4GB trong brief.
- **Hàng đợi device farm** có thể chờ khi nhiều người dùng — đừng để sát deadline mới đo.

---

## 6. Nguồn (đã xác minh)

- Repo chính: [github.com/qualcomm/ai-hub-models](https://github.com/qualcomm/ai-hub-models)
- Docs nền tảng: [Overview of Qualcomm AI Hub Workbench](https://workbench.aihub.qualcomm.com/docs/) · [Getting Started](https://workbench.aihub.qualcomm.com/docs/hub/getting_started.html) · [submit_compile_job](https://workbench.aihub.qualcomm.com/docs/hub/generated/qai_hub.submit_compile_job.html)
- Get started: [aihub.qualcomm.com/get-started](https://aihub.qualcomm.com/get-started)
- **Ảnh minh hoạ** (sơ đồ Workbench `workbench-flow`, ảnh chụp Profile/Inference Job) lấy thật từ [aihub.qualcomm.com/get-started](https://aihub.qualcomm.com/get-started), lưu tại `assets/`.
- Ra mắt MWC 2024: [Hackster — Qualcomm Launches AI Hub (4× performance)](https://www.hackster.io/news/qualcomm-launches-its-ai-hub-offering-optimized-on-device-ai-models-with-four-times-the-performance-3dddfcb8a84f) · [Beebom](https://beebom.com/qualcomm-ai-hub-released-run-ai-models-on-device/) · [Medium — Accelerating on-device AI](https://brownbeat.medium.com/accelerating-on-device-ai-qualcomm-ai-hub-at-mwc-2024-f4ef44a0b417)
- Chiến lược AI Stack/AIMET: [Klover.ai — Qualcomm's AI Strategy](https://www.klover.ai/qualcomm-ai-strategy-analysis-of-dominance-in-semiconductors/)
- Phần cứng: [Qualcomm AI Engine / Hexagon NPU](https://www.qualcomm.com/processors/ai-engine) · [Unlocking on-device generative AI with an NPU (Qualcomm whitepaper)](https://www.qualcomm.com/content/dam/qcomm-martech/dm-assets/documents/Unlocking-on-device-generative-AI-with-an-NPU-and-heterogeneous-computing.pdf)
- **Dữ liệu tối ưu (§4.1) lấy thật từ mã nguồn repo:** [`whisper_base/perf.yaml`](https://github.com/qualcomm/ai-hub-models/blob/main/src/qai_hub_models/models/whisper_base/perf.yaml) (số liệu latency/NPU) · [`_shared/hf_whisper/model.py`](https://github.com/qualcomm/ai-hub-models/blob/main/src/qai_hub_models/models/_shared/hf_whisper/model.py) (tách encoder/decoder, KV-cache, cờ compile) · [`whisper_small_quantized/perf.yaml`](https://github.com/qualcomm/ai-hub-models/blob/main/src/qai_hub_models/models/whisper_small_quantized/perf.yaml) (precision w8a16)
- Cơ chế quantization: [AI Hub — Quantization docs](https://workbench.aihub.qualcomm.com/docs/hub/quantize_examples.html) · [AIMET (github.com/qualcomm/aimet)](https://github.com/qualcomm/aimet)
