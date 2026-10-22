# HANDOFF MODEL CONTRACT — Production Hybrid và catalog `.bin` lưu trữ

> **Phạm vi quan trọng:** `onevoice-npu-bins/` là catalog gồm cả model đang dùng,
> model thử nghiệm và model đã bị thay thế. Dấu hiệu “deploy/main” trong tài liệu
> bàn giao cũ không còn quyết định runtime hiện tại.
>
> **Production hiện tại chỉ dùng sáu QNN context:** mỗi chiều có ASR encoder,
> Marian encoder và Marian decoder. RNN-T decoder/joiner và Kokoro chạy CPU.
> Routing hướng dịch nằm ngoài QNN model manifest và hiện chỉ được cấp bởi
> headset identity của microphone Bluetooth đã xác thực cùng assignment VI/EN.
> Không còn model classifier. Auracast tự nó là downlink và không xác định người
> đang nói.

## 1. Nguồn chân lý và target

Thứ tự ưu tiên khi có mâu thuẫn:

1. Kết quả `qnn-context-binary-utility` đọc trực tiếp từ đúng file `.bin`.
2. Metadata thật của ONNX decoder/joiner và token table đi cùng bundle.
3. `android-app/app/src/main/assets/npu_manifest.json`, được sinh từ bước 1.
4. `metadata.json`, `MANIFEST.md` và các mô tả lịch sử.

Lệnh dump QNN:

```bash
$QNN_SDK/bin/x86_64-linux-clang/qnn-context-binary-utility \
  --context_binary model.bin \
  --json_file model.io.json
```

Thông tin chung của **sáu context production**:

- QAIRT build ID: `v2.45.0.260326154327`.
- `socModel`: `43` = SM8550, Snapdragon 8 Gen 2, Hexagon HTP v73.
- Runtime: QNN context binary AOT; không chạy trên CPU x86 hoặc đời HTP khác.
- Tất cả tensor QNN production bên dưới là `FLOAT_32` hoặc `INT_32`; không có
  scale/offset quantization.
- Graph name phải dùng đúng từng ký tự. Không suy ra graph name từ tên file.

## 2. Production Hybrid — sáu QNN context chính xác

| Runtime key | File trên app | Graph | Kích thước |
| :-- | :-- | :-- | --: |
| `vi-en/asr` | `vi-en/asr_gipformer_encoder.bin` | `graph_oa00nu3e` | 157,634,560 B |
| `vi-en/mt_encoder` | `vi-en/mt_encoder.bin` | `opus_mt_encoder` | 100,048,896 B |
| `vi-en/mt_decoder` | `vi-en/mt_decoder.bin` | `opus_mt_decoder` | 156,549,120 B |
| `en-vi/asr` | `en-vi/asr_parakeet_ctc.bin` | `model` | 115,240,960 B |
| `en-vi/mt_encoder` | `en-vi/mt_encoder.bin` | `opus_mt_encoder` | 99,991,552 B |
| `en-vi/mt_decoder` | `en-vi/mt_decoder.bin` | `opus_mt_decoder` | 156,426,240 B |

Hai Marian bundle lặp graph name. Vì vậy runtime key bắt buộc có prefix hướng
`vi-en/` hoặc `en-vi/`; chỉ dùng `mt_encoder`/`mt_decoder` sẽ đụng context.

### 2.1 ASR — VI GIPFormer (RNN-T) và EN Parakeet-TDT-CTC-110M (CTC)

> Từ 2026-10-22, EN dùng `nvidia/parakeet-tdt_ctc-110m` (CC-BY-4.0) qua binary
> SM8550 do sherpa-onnx biên dịch sẵn (release `asr-models-qnn-binary-2`,
> bản `-10s` đầu CTC). Zipformer-small EN cũ chỉ huấn luyện trên LibriSpeech
> nên nhận rất kém tiếng Anh giọng Việt.

Parakeet EN (graph `model`, QAIRT build v2.40, nạp được trên runtime 2.45):

```text
INPUT   x          UFIXED_POINT_16 [1,1000,80]  scale 1.2464652536e-4 offset -31906
OUTPUT  log_probs  UFIXED_POINT_16 [1,125,1025] scale 6.816668902e-4  offset -65535
```

- Feature: NeMo log-mel (pre-emphasis 0.97, Hann 400/160, FFT 512, mel Slaney
  0–8 kHz, `log(x+2^-24)`), chuẩn hoá mean/std theo từng kênh trên frame thật,
  đệm 0; lấy 1000 frame đầu của bản `[1001,80]`.
- Đầu ra đã là log-softmax; blank id 1024; subsampling 8; token table 1024
  SentencePiece + `<blk>`. Scale/offset được ghi vào `npu_manifest.json` từ
  chính binary, không chép tay.
- Nguồn: `asr/parakeet_tdt_ctc_110m_en__sherpa_SM8550_10s/`.

GIPFormer VI encoder (Zipformer-small EN cũ có cùng contract này):

```text
INPUT
  0 x         FLOAT_32 [1,1000,80]

OUTPUT
  0 output_0  FLOAT_32 [1,248,512]
```

- Audio đầu vào: PCM mono 16 kHz được host chuyển thành Kaldi/icefall log-fbank
  80 bins, `snip_edges=false`, Povey window, 400-sample window, hop 160,
  FFT 512, low/high frequency 20/7600 Hz, pre-emphasis 0.97.
- `.bin` chỉ chứa encoder. Một pipeline chạy được còn cần ONNX prediction
  network, ONNX joiner và token table đi cùng đúng bundle.
- VI source: `asr/gipformer_65m_rnnt-bundle/`.
- EN cũ (đã thay, giữ trong catalog): `asr/zipformer_small_en-bundle/`.

### 2.2 Host RNN-T decoder/joiner — contract chính xác

Phần này chạy ONNX Runtime trên CPU, không phải QNN context.

Contract chung của prediction network:

```text
INPUT   y            INT64    [N,2]
OUTPUT  decoder_out  FLOAT32  [N,512]
metadata: context_size=2
```

Contract chung của joiner:

```text
INPUT   encoder_out  FLOAT32  [N,512]
INPUT   decoder_out  FLOAT32  [N,512]
OUTPUT  logit        FLOAT32  [N,vocab_size]
metadata: joiner_dim=512
```

Khác biệt theo ngôn ngữ:

| Bundle | `vocab_size` trong ONNX | Shape `logit` | Token rows | Quy ước |
| :-- | --: | :-- | --: | :-- |
| GIPFormer VI | 2000 | `[N,2000]` | 2000 | blank=0, sos/eos=1, unk=2 |
| Zipformer EN | 500 | `[N,500]` | 502 | blank=0, sos/eos=1, unk=2 |

Hai dòng cuối của token table EN là `#0` id 500 và `#1` id 501. Đây là
disambiguation symbols của tokenizer, **không phải logits**. Argmax chỉ chạy trên
500 phần tử do ONNX khai báo, rồi dùng `tokens.take(500)` để decode.

Greedy loop production dùng seed `[-1, blank]`, bỏ blank/unk, cập nhật prediction
network sau mỗi token phát ra và xử lý tối đa một symbol cho mỗi encoder frame.

### 2.3 Marian encoder — giống shape ở hai chiều

```text
GRAPH opus_mt_encoder

INPUT
  0 input_ids               INT_32   [1,256]
  1 encoder_attention_mask  INT_32   [1,256]

OUTPUT — 12 tensor FLOAT_32
  block_{0..5}_cross_key_states    [1,8,256,64]
  block_{0..5}_cross_value_states  [1,8,256,64]
```

“12 tensor” nghĩa là 6 lớp × key/value, không phải 12 cặp.

### 2.4 Marian decoder — shape cache tĩnh

```text
GRAPH opus_mt_decoder

INPUT — 27 tensor
  0 input_ids               INT_32   [1,1]
  1 position                INT_32   [1]
  2 encoder_attention_mask  INT_32   [1,256]

  Với mỗi layer 0..5:
    past_self_key_states    FLOAT_32 [1,8,255,64]
    past_self_value_states  FLOAT_32 [1,8,255,64]
    cross_key_states        FLOAT_32 [1,8,256,64]
    cross_value_states      FLOAT_32 [1,8,256,64]

OUTPUT — 13 tensor
  0 logits                  FLOAT_32 [1,1,V]
  Với mỗi layer 0..5:
    present_self_key_states   FLOAT_32 [1,8,1,64]
    present_self_value_states FLOAT_32 [1,8,1,64]
```

`V=53,739` cho VI→EN và `V=53,685` cho EN→VI. Sau mỗi decode step,
host chép tensor `present` một bước vào slot `position` tương ứng trong buffer
`past[...255...]`. Bắt đầu bằng `decoder_start_token_id` (cũng là pad), bỏ pad
khi argmax và dừng ở `eos_token_id=0`.

EN→VI có `target_prefix_token_id=3` từ tokenizer config; token này được thêm ở
đầu chuỗi source trước SentencePiece. VI→EN không có target prefix.

## 3. TTS production — whole-utterance CPU Kokoro

QNN Kokoro fixed-window trong catalog **không phải production path**. Hybrid gọi
sherpa-onnx `OfflineTts` một lần cho toàn bộ câu để giữ prosody liên tục và tránh
đường nối bucket gây giật/ngắt âm.

| Ngôn ngữ output | Model CPU | Kích thước | Voice asset hiện có |
| :-- | :-- | --: | :-- |
| EN (VI→EN) | `models-hf/tts-en/model.onnx` | 345,555,491 B | `voices.bin` 5,755,904 B, 11 speaker IDs |
| VI (EN→VI) | `models-hf/tts-vi/model.onnx` | 325,732,252 B | `voices.bin` 522,240 B, một voice `default` |

Hai model khai sample rate 24 kHz. Runtime kiểm tra sample rate, toàn bộ waveform
phải hữu hạn, rồi scale toàn câu nếu peak vượt 0.90 trước khi phát PCM float.

**Token table VI phải là `tools/models/kokoro-vi-espeak-tokens.txt`, không phải
`tokens.txt` gốc.** Kokoro-Vietnamese được huấn luyện với G2P `vig2p` (thanh
điệu là mũi tên `→ ↘ ↗ ↓`), còn sherpa-onnx phiên âm bằng espeak-ng `vi` (thanh
điệu là chữ số, đ là `ɗ`). Ký hiệu lạ bị sherpa bỏ âm thầm, nên mọi thanh điệu
biến mất. Bảng đã vá thêm alias chữ số → mũi tên, `ɗ → d`, `ʐ → ʒ`. Đo bằng
vòng TTS → GIPFormer trên 6 câu: CER 9.8% → 0.0%. Còn thiếu: ngã/nặng mất `ʔ`,
cặp s/x, tr/ch, gi/d chưa phân biệt; muốn triệt để phải dùng G2P `vig2p`.

## 4. Catalog lịch sử/thử nghiệm — không tự động đưa vào production

### 4.1 Parakeet-CTC-0.6B Vietnamese

File: `asr/parakeet_ctc_vi__DEPLOY/logits.bin`, 1,207,267,328 B.

Dump thật:

```text
graph: graph_08xlh5ri
input_features  FLOAT_32 [1,1001,80]
output_0        FLOAT_32 [1,126,1025]
```

Blank id là 1024. CTC greedy thực hiện argmax, gộp token trùng liền nhau và bỏ
blank. Tên graph `encoder_ctc` và tên output `logits` trong tài liệu cũ là tên
logic, **không phải** tên nhúng trong context binary. Model này từng là ASR VI
chính nhưng đã được GIPFormer bundle thay thế trong Hybrid hiện tại.

`parakeet_ctc_vi__encoder_only/encoder.bin` chỉ xuất hidden states, không thay thế
được `logits.bin` nếu không có CTC head.

### 4.2 PhoWhisper-small Vietnamese

`phowhisper_small_vi__EXPERIMENTAL/` chứa encoder và decoder tự hồi quy với
self/cross KV cache. Đây là artifact thử nghiệm, không thuộc sáu context
production. Phải dump lại đúng graph/tensor từ binary trước khi viết host loop;
không dùng các tên logic `encoder`/`decoder` làm graph name mặc định.

### 4.3 Zipformer-30M Vietnamese encoder-only cũ

File: `asr/zipformer_30m_rnnt_vi__EXPERIMENTAL_encoder_only/encoder.bin`.

```text
graph: graph_wi3sa4gp
x         FLOAT_32 [1,1000,80]
output_0  FLOAT_32 [1,248,512]
```

Bundle cũ này không có prediction network/joiner. Nó khác với cả GIPFormer VI
production và Zipformer-small EN production.

### 4.4 NLLB-200 distilled 600M

- `nllb_600m_w8a8__8gen2_S23/`: artifact thay thế cho S23, không phải MT
  production hiện tại; license CC-BY-NC cần được đánh giá trước khi thương mại.
- `nllb_600m_w8a8__8elite_NOT_FOR_S23/`: link cho đời HTP khác, tuyệt đối không
  nạp lên SM8550/v73.
- Production dùng Opus/Marian float ở cả hai chiều vì nhẹ hơn và license phù hợp.

### 4.5 QNN Kokoro EN/VI fixed-window

Các context dưới đây được giữ làm bằng chứng kỹ thuật lịch sử. Chúng không nằm
trong `npu_manifest.json` production.

Encoder EN:

```text
graph: graph_5l17io1h
input_ids  INT_32   [1,512]
ref_s      FLOAT_32 [1,256]
output_1   FLOAT_32 [1,512,640]
output_0   FLOAT_32 [1,512]
output_2   FLOAT_32 [1,512,512]
```

Encoder VI có cùng tensor contract, graph `graph_ufuwwhzv`.

Decoder EN:

```text
graph: graph_93073co3
F0        FLOAT_32 [1,50]
N         FLOAT_32 [1,50]
asr       FLOAT_32 [1,512,25]
s         FLOAT_32 [1,128]
har       FLOAT_32 [1,22,3001]
output_0  FLOAT_32 [1,1,15000]
```

Decoder VI có cùng tensor contract, graph `graph_velm2qrc`. Một bucket 25 frame
xuất đúng 15,000 mẫu ở 24 kHz. Thứ tự input binary là `F0, N, asr, s, har`, không
phải thứ tự trong `metadata.json` cũ.

Lưu ý:

- QNN encoder nhận `input_ids` **INT_32**, dù metadata nguồn ghi int64.
- `.bin` không chứa voice style. Voice table/voicepack và G2P là host assets.
- Pipeline QNN lịch sử còn cần alignment, prosody/F0/N, harmonic excitation và
  ghép overlap-add. Chỉ hai file encoder/decoder không tạo thành TTS Android hoàn chỉnh.
- Fixed-window rendering từng tạo seam và nhịp ngắt; không khôi phục nó làm
  production chỉ vì các file vẫn còn trong catalog.

## 5. Deployment layout của Hybrid

```text
<filesDir>/models/npu/
├── vi-en/
│   ├── asr_gipformer_encoder.bin
│   ├── asr_gipformer_{decoder,joiner}.onnx
│   ├── asr_gipformer_tokens.txt
│   ├── mt_{encoder,decoder}.bin
│   ├── mt_source.spm
│   ├── mt_vocab.json
│   ├── mt_config.json
│   ├── tts_cpu.onnx
│   ├── tts_tokens.txt
│   ├── tts_voices.bin
│   └── fallback/...
└── en-vi/
    ├── asr_parakeet_ctc.bin
    ├── asr_parakeet_tokens.txt
    ├── mt_{encoder,decoder}.bin
    ├── mt_source.spm
    ├── mt_vocab.json
    ├── mt_config.json
    ├── tts_cpu.onnx
    ├── tts_tokens.txt
    ├── tts_voices.bin
    └── fallback/...
```

`tools/models/push-npu-models.sh auto <serial>` là mapping triển khai thực tế.
Script dùng SHA-256 và publish atomically; không đổi tên/tự thay model theo nhãn
trong catalog.

## 6. Trạng thái bằng chứng

- Các latency ghi trong metadata/MANIFEST của catalog đến từ Qualcomm AI Hub
  cloud profiling; không được trình bày như end-to-end latency của app.
- Không có golden input/output chuẩn kèm theo toàn bộ catalog.
- Generator từ chối artifact có `socModel` khác 43; runtime đọc/báo lại
  `socModel`, xác nhận graph identity, tensor count, tensor order, dtype và
  dimensions khi load QNN context.
- Host RNN-T kiểm tra thêm ONNX input/output names, dtype, shape,
  `context_size`, `vocab_size` và `joiner_dim` trước khi nhận audio.
- Khi model team thay bất kỳ `.bin` nào, chạy lại generator manifest từ chính
  artifact mới. Không chép graph name hoặc shape thủ công từ tài liệu này.
