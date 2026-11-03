# Báo cáo tích hợp: chạy thử cascade S2ST bằng model HuggingFace trên máy ảo

> Tài liệu này mô tả **những gì đã làm** để tích hợp và chạy thử 3 model HuggingFace
> trên Android Studio emulator (chưa có Samsung S23/NPU để test đường QNN).
> Hướng dẫn **cách chạy** từng bước nằm ở [EMULATOR_TESTING.md](EMULATOR_TESTING.md).

## 1. Mục tiêu & ràng buộc

- Chạy thử **toàn bộ cascade** thu âm → ASR → dịch → TTS → phát loa trên máy ảo
  x86_64 (không có Hexagon NPU), dùng 3 model do team chỉ định:
  | Vai trò | Model | Repo gốc |
  |---|---|---|
  | ASR (tiếng Việt) | Parakeet-CTC-0.6B-Vietnamese | `nvidia/parakeet-ctc-0.6b-Vietnamese` |
  | MT (Việt → Anh) | opus-mt-vi-en (MarianMT) | `Helsinki-NLP/opus-mt-vi-en` |
  | TTS (giọng Việt) | Kokoro-Vietnamese | `contextboxai/Kokoro-Vietnamese` |
- **Ràng buộc cứng:** giữ nguyên đường chạy `.bin`/QNN hiện có — khi model team
  giao context binary thật thì đường NPU chạy được ngay, không đụng lại code.

## 2. Kiến trúc tích hợp

Tách một interface `EngineBackend` với **hai** hiện thực, dashboard chọn qua UI
(mặc định tự nhận diện: emulator x86_64 → HF, máy Snapdragon arm64 → QNN):

```
EngineBackend
 ├─ QnnEngineBackend   -> OneVoiceNative -> C++ core -> .bin trên Hexagon HTP
 │                        (adapter mỏng, call-sequence giữ NGUYÊN như cũ)
 └─ HfEngineBackend    -> AudioRecord 16 kHz mono
                           -> VAD năng lượng (ngắt câu theo khoảng lặng 700 ms)
                           -> sherpa-onnx OfflineRecognizer (nemo_ctc)   [ASR]
                           -> ONNX Runtime + SentencePiece Kotlin        [MT]
                           -> sherpa-onnx OfflineTts (kokoro)            [TTS]
                           -> AudioTrack
```

UI thêm 2 thành phần: **BackendSelector** (chip chọn `NPU · .bin` / `HF · CPU`,
chỉ đổi được khi chưa init) và **TranslationCard** (hiện transcript VI + bản
dịch EN realtime khi chạy backend HF).

## 3. Thư viện đã thêm & lý do chọn

| Thư viện | Cách lấy | Dùng cho | Lý do chọn |
|---|---|---|---|
| **sherpa-onnx 1.13.4** (AAR, bản `static-link-onnxruntime`) | GitHub release chính thức của k2-fsa → `android-app/app/libs/` (không có trên Maven; script `tools/models/fetch-sherpa-assets.ps1`) | ASR (nemo_ctc) + TTS (kokoro) | Runtime duy nhất có sẵn feature-extraction (log-mel), CTC decode, phonemizer espeak-ng (có `vi_dict`) đóng gói cho Android; hỗ trợ cả arm64 lẫn x86_64. Bản static-link để không xung đột `libonnxruntime.so` với ORT Maven (xem §6). |
| **ONNX Runtime 1.20.0** (`com.microsoft.onnxruntime:onnxruntime-android`) | Maven Central (khai báo trong version catalog) | Chạy MarianMT encoder/decoder | Artifact chính thức, ổn định; sherpa không hỗ trợ MT. |
| **SentencePiece** | **KHÔNG thêm thư viện** — tự viết `SentencePieceUnigram.kt` (parse protobuf `.spm` tối giản + Viterbi unigram, ~200 dòng Kotlin) | Tokenize cho Marian | Không có bản SentencePiece JNI chính thức đáng tin cho Android; tự viết loại rủi ro dependency và đủ cho unigram của opus-mt. |
| espeak-ng-data | Giải nén từ tarball kokoro chính thức của k2-fsa | Phonemize cho Kokoro | Kokoro nhận input phoneme; espeak-ng có sẵn tiếng Việt. |

Toolchain convert (offline, venv Python trên ổ D: — C: chỉ còn ~9 GB):
`optimum[onnxruntime]`, `transformers`, `torch`, `onnx`, `nemo_toolkit[asr]` (~5 GB, chỉ cho Parakeet).

## 4. Model: tải về → convert → định dạng on-device

Nguồn tải về `models-src/` (git-ignored, ~3.4 GB), kết quả convert ở `models-hf/`
(git-ignored, ~1.1 GB), đẩy lên máy ảo bằng `tools/models/push-to-device.ps1`
vào `/sdcard/Android/data/com.onevoice.link/files/models/hf/{asr,mt,tts}`.

| Model | Gốc | Convert bằng | Kết quả on-device |
|---|---|---|---|
| Parakeet-CTC-vi | `.nemo` 2.44 GB | `convert_parakeet.py`: NeMo `model.export()` → ONNX → quantize **int8** → xoá ~2.3 GB weight-shard fp32 → **ghi metadata sherpa** | `asr/model.int8.onnx` (624 MB) + `tokens.txt` (1024 BPE + `<blk>`) |
| opus-mt-vi-en | PyTorch 289 MB | `convert_marian.py`: optimum export (encoder + decoder, no-KV-cache) → quantize **int8** | `mt/encoder_model.onnx` (45 MB) + `decoder_model.onnx` (78 MB) + `source.spm`/`vocab.json`/`mt_config.json` (chứa `decoder_start=pad=53738`, `eos=0`) |
| Kokoro-vi | đã có `kokoro_vi.onnx` 326 MB + voicepack `.pt` | `convert_kokoro.py`: copy onnx, vocab 114 phoneme → `tokens.txt`, voicepack → `voices.bin` (raw f32) → **ghi metadata sherpa** | `tts/model.onnx` + `tokens.txt` + `voices.bin` + `espeak-ng-data/` |

**Điểm mấu chốt phát hiện khi debug:** sherpa-onnx **bắt buộc** model ONNX phải
có `metadata_props` theo quy ước của họ, thiếu là **abort cả process** (app tự
tắt — không catch được từ Kotlin vì là native exit):
- NeMo CTC: `vocab_size` (= số dòng tokens.txt), `normalize_type=per_feature`,
  `subsampling_factor=8` (FastConformer — đọc từ config `.nemo`), `model_type=EncDecCTCModelBPE`.
- Kokoro: `model_type=kokoro`, `voice=vi` (giọng espeak để phonemize),
  `sample_rate=24000`, `style_dim=510,1,256`, `n_speakers`, `speaker2id`, ...

Cả hai script convert đã tự ghi metadata này (parakeet có thêm `--meta-only`
để vá file có sẵn không cần export lại ~20 phút).

## 5. Thay đổi build & code

### Build (hỗ trợ x86_64 mà không ảnh hưởng arm64)
- `native-core/CMakeLists.txt`: cờ `-march=armv8.2-a+dotprod...` và link QNN
  **chỉ áp cho `arm64-v8a`**; trên x86_64 core vẫn compile với header QNN
  (dlopen fail lúc runtime → `nativeInitEngine` trả false, không crash).
- `app/build.gradle.kts`: thêm ABI `x86_64`; dep AAR sherpa + ORT Maven;
  `pickFirsts` cho `libonnxruntime.so` (chỉ đụng ABI 32-bit không đóng gói).
- `AndroidManifest.xml`: `android:pageSizeCompat="enabled"` — tắt dialog cảnh
  báo 16 KB trên emulator image 16k-page (lib x86_64 upstream chưa align ELF
  16 KB; arm64 của ta đã align đúng từ trước).

### Code Kotlin mới / sửa
| File | Nội dung |
|---|---|
| `core/EngineBackend.kt` (mới) | Interface 4 lifecycle call + `PipelineListener` (transcript/translation) |
| `core/QnnEngineBackend.kt` (mới) | Adapter giữ nguyên semantics OneVoiceNative |
| `core/OneVoiceNative.kt` | Thêm cờ `isLoaded` (loadLibrary bọc runCatching) |
| `hf/HfEngineBackend.kt` (mới) | AudioRecord + VAD + điều phối ASR→MT→TTS + AudioTrack |
| `hf/MarianTranslator.kt` (mới) | Greedy decode encoder/decoder trên ORT |
| `hf/SentencePieceUnigram.kt` (mới) | Parse `.spm` + Viterbi encode thuần Kotlin |
| `ui/.../DashboardState.kt` | `EngineController` chuyển sang `EngineBackend`; thêm `BackendKind`, transcript/translation state (envelope busy/message giữ nguyên) |
| `ui/components/BackendSelector.kt`, `TranslationCard.kt` (mới) | UI chọn backend + hiện text pipeline |
| `MainActivity.kt` | Tạo 2 backend, auto-detect emulator, destroy cả 2 trong `onDestroy` |

## 6. Sự cố đã gặp & cách xử lý (theo thứ tự thời gian)

| # | Sự cố | Nguyên nhân | Xử lý |
|---|---|---|---|
| 1 | `huggingface-cli` fail `UnicodeEncodeError: charmap` | Python trên Windows ghi progress-bar Unicode ra pipe cp1252 | Chạy với `PYTHONUTF8=1` |
| 2 | Gradle fail `jlink.exe does not exist` | Daemon cũ do VS Code extension (đã gỡ) khởi chạy, java home không còn tồn tại | `gradlew --stop` + build với `JAVA_HOME=C:\Program Files\Java\jdk-21` |
| 3 | `2 files found with path lib/**/libonnxruntime.so` | AAR sherpa thường và ORT Maven cùng bundle libonnxruntime | Đổi sang AAR **static-link-onnxruntime** + `pickFirsts` cho ABI 32-bit còn sót |
| 4 | App tự tắt khi bấm Initialize | sherpa abort: `'vocab_size' does not exist in the metadata` (model NeMo export trần) | Ghi metadata sherpa vào ONNX (ASR **và** TTS — TTS chắc chắn dính tiếp nếu không vá trước); vá script convert |
| 5 | `push-to-device.ps1` lỗi parse `Unexpected token` | Ký tự em-dash Unicode trong chuỗi; PowerShell 5.1 đọc file không BOM theo ANSI | Viết lại cả 3 script `.ps1` ASCII thuần |
| 6 | `adb push /sdcard/...` đẩy sai đích trong Git Bash | MSYS path conversion dịch `/sdcard` thành path Windows | `MSYS_NO_PATHCONV=1` + local path kiểu `D:/...` (script PowerShell không bị) |
| 7 | Dialog "app isn't 16 KB compatible" trên emulator | Image `sdk_gphone16k` (page 16 KB) + prebuilt x86_64 upstream chưa align | `android:pageSizeCompat="enabled"` trong manifest |

## 7. Trạng thái & giới hạn hiện tại

- ✅ APK build xanh 2 ABI (222 MB); đường `.bin`/QNN không đổi hành vi trên arm64.
- ✅ `models-hf/` đủ 3 model đã convert + metadata, đã push lên emulator.
- ⏳ Đang chờ xác nhận chạy end-to-end trên emulator sau fix metadata (§6.4).
- Giới hạn đã biết:
  - Parakeet 0.6B trên CPU giả lập chậm (vài giây/câu, kể cả int8) — đây là rig
    kiểm chứng **chức năng**, không phải demo độ trễ.
  - Giọng Kokoro là tiếng Việt; mặc định app đọc **bản dịch tiếng Anh** bằng
    giọng Việt (đổi `speakTranslation = false` trong `HfEngineBackend` để đọc
    lại transcript tiếng Việt).
  - Voicepack hiện đóng gói 1 giọng (`kokoro_vi_voicepack.pt`); repo gốc có
    14 giọng trong `voicepacks/` — muốn nhiều giọng thì mở rộng
    `convert_kokoro.py` đọc cả thư mục đó (sid = thứ tự trong `speaker_names`).
- Mọi artifact nặng đều git-ignored: `models-src/` (3.4 GB), `models-hf/`
  (1.1 GB), `app/libs/*.aar`, venv/pip-cache.

## 8. Song hướng VI⇄EN + fix phát âm thanh bản dịch

**Bug "không phát âm thanh bản dịch" — nguyên nhân gốc:** phiên bản một chiều
đọc bản dịch **tiếng Anh** bằng Kokoro-**Việt** (espeak voice `vi` phonemize
text tiếng Anh → phoneme rỗng/rác → im lặng). Fix tận gốc bằng song hướng:
bản dịch luôn được đọc bởi TTS **đúng ngôn ngữ đích**.

**Model bổ sung cho chiều EN→VI** (Kokoro-vi và Parakeet-vi dùng lại):

| Vai trò | Model | Ghi chú |
|---|---|---|
| ASR EN | `csukuangfj/sherpa-onnx-nemo-ctc-en-conformer-small` (int8 46 MB) | Đã convert sẵn cho sherpa — tải về dùng ngay, nhanh trên CPU |
| MT EN→VI | `Helsinki-NLP/opus-mt-en-vi` → ONNX int8 | Tái dùng `convert_marian.py --direction en-vi` |
| TTS EN (đầu ra chiều VI→EN) | `kokoro-en-v0_19` (330 MB, 11 giọng) | Gói chính chủ k2-fsa, có sẵn metadata |

**Layout on-device mới** (7 thư mục, breaking — phải push lại):
`asr-vi/ asr-en/ mt-vi-en/ mt-en-vi/ tts-vi/ tts-en/ espeak-ng-data/` (espeak dùng chung).

**Thay đổi code chính:**
- `TranslationDirection` (VI_TO_EN/EN_TO_VI) trên interface `EngineBackend`
  (QNN no-op — chiều dịch của `.bin` do model team quyết).
- `HfEngineBackend` nạp **cả 2 chiều** khi Initialize (~1.6–2 GB RAM →
  **AVD cần ≥ 4 GB**), đổi chiều tức thì giữa hội thoại; snapshot direction
  theo từng segment để không trộn model 2 chiều.
- **Half-duplex gate**: khi loa đang phát TTS, mic drop frame + hủy segment
  dở — tiếng máy đọc không bị thu ngược vào ASR (bắt buộc cho kịch bản 2 người
  dùng chung 1 máy).
- **Nút replay 🔊** trên Live Translation card: phát lại audio bản dịch gần
  nhất (`replayLastAudio()`); card đổi nhãn VI/EN theo chiều.
- UI mới: `DirectionToggle` (SegmentedButton VI→EN / EN→VI, đổi được cả khi
  đang RUNNING).
