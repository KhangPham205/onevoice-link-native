# Sự cố chất lượng TTS QNN: âm thanh giật, nói gấp và bị cụt

- **Ngày ghi nhận:** 2026-08-09
- **Trạng thái:** Đã có giải pháp giảm thiểu bằng Hybrid; Full-QNN vẫn giữ để
  benchmark và tiếp tục tối ưu
- **Thiết bị xác minh:** Samsung SM-F731B, Snapdragon 8 Gen 2/SM8550,
  Hexagon v73, Android 16
- **ADB serial:** `R5CW72TP4EP`
- **Phạm vi:** TTS tiếng Anh trong luồng VI → EN

## Tóm tắt cho team

TTS chạy hoàn toàn bằng các context QNN hiện phát âm giật, nhịp không tự nhiên,
nói nhanh và đôi khi nghe như bị cắt. Đây **không phải** lỗi `AudioTrack`, thiếu
buffer, NPU không theo kịp realtime, hoặc model bị load/unload giữa câu.

Nguyên nhân chính là bộ artifact TTS hiện tại không có một graph tạo waveform
cho toàn câu và cũng không cung cấp giao diện streaming có state. Decoder QNN
chỉ nhận 25 alignment frame mỗi lần, tương ứng 15.000 mẫu/625 ms ở 24 kHz. Host
phải chia câu thành nhiều cửa sổ, gọi prosody/decoder độc lập rồi ghép waveform.
Crossfade có thể che tiếng click nhưng không thể phục hồi ngữ cảnh temporal,
convolution state và prosody toàn câu đã mất tại biên cửa sổ.

Giải pháp đang áp dụng trong sản phẩm là **Hybrid**:

```text
Audio/VAD
  → QNN ASR
  → QNN Marian MT encoder/decoder
  → translated text
  → CPU Kokoro xử lý nguyên câu
  → AudioTrack
```

Hybrid giữ ASR và dịch máy trên HTP, nhưng dùng Kokoro CPU thống nhất cho phần
phát âm. Full-QNN vẫn có trong UI để benchmark và phục vụ việc re-export model.

## Hiện tượng và bằng chứng trên thiết bị

Trong lần nói thật gần nhất:

| Hạng mục | Kết quả |
|---|---:|
| Audio thu vào | 64.320 mẫu @ 16 kHz = 4,020 giây |
| RMS / peak | 0,02162 / 0,28336 |
| Bản dịch | `Hello how are you today?` |
| Full-QNN TTS | 29 token, 48 frame, 3 decoder bucket |
| Waveform Full-QNN | 28.800 mẫu @ 24 kHz = 1,200 giây |
| Thời gian Full-QNN TTS | 1.681 ms (khoảng 1,68 giây) |
| Playback | 28.800/28.800 frame, `underruns=0` |

Với câu kiểm thử tương đương trong Hybrid:

| Hạng mục | Kết quả |
|---|---:|
| Bản dịch | `Hello, how are you today?` |
| CPU Kokoro | Một lần `generate()` cho toàn câu |
| Waveform Hybrid | 64.800 mẫu @ 24 kHz = 2,700 giây |
| Thời gian CPU TTS | 2.434 ms (khoảng 2,43 giây) |
| Playback | 64.800/64.800 frame, `underruns=0` |

Hai backend đều tạo xong và nạp toàn bộ waveform trước khi phát. Hardware đã
tiêu thụ đủ mọi frame. Chênh lệch 1,2 giây so với 2,7 giây, cùng việc Full-QNN
phải đi qua ba bucket, giải thích trực tiếp cảm giác nói gấp, giật và cụt.

> Các số latency trên được đo ở hai lần chạy khác nhau, vì vậy chỉ dùng để quan
> sát vận hành; không dùng như benchmark hiệu năng tuyệt đối giữa CPU và NPU.

## Hợp đồng TTS QNN hiện tại

TTS Full-QNN gồm ba learned stage độc lập:

### 1. Kokoro encoder

| Tensor | Kiểu/shape | Ý nghĩa |
|---|---|---|
| `input_ids` | INT32 `[1,512]` | Chuỗi phoneme/token |
| `ref_s` | FLOAT32 `[1,256]` | Voice/style vector |
| duration output | FLOAT32 `[1,512]` | Duration dự đoán theo token |
| `d` output | FLOAT32 `[1,512,640]` | Đặc trưng cho prosody |
| `t_en` output | FLOAT32 `[1,512,512]` | Đặc trưng ASR/alignment |

### 2. Prosody W8A8 bổ sung

| Tensor | Kiểu/shape |
|---|---|
| `en` | FLOAT32 `[1,25,640]` |
| style tail | FLOAT32 `[1,128]` |
| `F0` | FLOAT32 `[1,50]` |
| `N` | FLOAT32 `[1,50]` |

Stage này phải được bổ sung vì cặp encoder/decoder ban đầu **không tạo thành
một pipeline đóng**. Decoder cần `F0` và `N`, nhưng learned network
`F0Ntrain` không nằm trong hai `.bin` được bàn giao. Prosody W8A8 giúp pipeline
có thể chạy, nhưng khác precision với reference và có thể đóng góp thêm sai
khác chất lượng. Hiện chưa có bằng chứng cho thấy W8A8 là nguyên nhân duy nhất
gây giật.

### 3. Waveform decoder

| Tensor | Kiểu/shape |
|---|---|
| `F0`, `N` | FLOAT32 `[1,50]` |
| `asr` | FLOAT32 `[1,512,25]` |
| style head | FLOAT32 `[1,128]` |
| `har` | FLOAT32 `[1,22,3001]` |
| waveform | FLOAT32 `[1,1,15000]` |

15.000 mẫu ở 24 kHz tương ứng 625 ms. Host hiện dùng cửa sổ 25 frame, stride
20 frame và overlap 5 frame, tức khoảng mỗi 500 ms lại có một lần gọi decoder
mới.

## Tại sao cách ghép bucket không thể đạt chất lượng CPU

CPU Kokoro nhận toàn bộ chuỗi phoneme và thực hiện duration, alignment,
prosody, harmonic source và waveform decoder trong một lần inference thống
nhất. Các layer nhìn thấy ngữ cảnh trước/sau xuyên suốt câu.

Full-QNN hiện phải thực hiện:

```text
toàn bộ token
  → duration/alignment trên host
  → [frame 0..24]   → prosody + HAR + decoder
  → [frame 20..44]  → prosody + HAR + decoder
  → [frame 40..]    → prosody + HAR + decoder
  → overlap/crossfade waveform
```

Cơ chế này có bốn giới hạn:

1. **Prosody bị tính theo từng cửa sổ.** Graph không nhận cache/state từ cửa
   sổ trước và không biết đầy đủ ngữ điệu của cả câu.
2. **Decoder không có state streaming.** Convolution/receptive-field context bị
   reset hoặc chịu zero-padding tại mỗi biên 25 frame.
3. **`har` phải dựng ở host.** Artifact bàn giao không chứa harmonic source
   hoàn chỉnh. Host đã giữ phase/RNG liên tục theo stride, nhưng chưa có golden
   vector chứng minh bit-parity với exporter/reference Kokoro.
4. **Duration/alignment contract chưa có golden reference.** Việc round/clip,
   padding, tốc độ và quy tắc trim hiện được suy ra từ model. Waveform Full-QNN
   ngắn bất thường cho thấy phần này cần được team model xác minh thêm.

Crossfade chỉ trộn biên độ của hai waveform đã sinh. Nó không thể tạo lại
hidden state hoặc ngữ cảnh mà graph chưa từng nhận. Tăng overlap có thể làm
click nhỏ hơn nhưng cũng tăng số lần inference và không giải quyết nguyên nhân
gốc.

## Những nguyên nhân đã được loại trừ

| Giả thuyết | Kết luận |
|---|---|
| `AudioTrack` bị starvation | Đã loại trừ: toàn bộ waveform được prime trước `play()` và mọi frame đều delivered. |
| NPU inference chậm hơn playback | Đã loại trừ: TTS hoàn tất trước khi bắt đầu phát. |
| Model bị load/unload giữa câu | Đã loại trừ: QNN contexts giữ resident cho đến khi đổi backend/destroy. |
| G2P dùng ký tự Latin sai | Đã sửa: Full-QNN hiện dùng chính xác eSpeak/Kokoro token IDs qua frontend bridge nhỏ. |
| Thiếu signed Hexagon skeleton | Không liên quan tới chất lượng TTS; QNN contexts đã load và execute thành công. |

## Giải pháp hiện tại: Hybrid

Hybrid là backend mặc định và được khuyến nghị cho sử dụng thực tế:

- Chỉ load ba context QNN: `asr`, `mt_encoder`, `mt_decoder`.
- Không load `tts_encoder`, `tts_prosody`, `tts_decoder` QNN.
- Sau khi MT tạo câu đích, gọi sherpa-onnx Kokoro đúng một lần cho toàn câu.
- Giữ một CPU Kokoro session resident để không phải load lại mỗi utterance.
- Phát một waveform 24 kHz hoàn chỉnh qua cùng cơ chế `AudioTrack` đã kiểm
  chứng.

Các lựa chọn UI:

| Backend | Mục đích |
|---|---|
| **Hybrid** | Mặc định; QNN ASR/MT và TTS CPU mượt |
| **CPU** | Reference end-to-end và hỗ trợ hai chiều khi đủ asset |
| **NPU** | Full-QNN để benchmark, debug và đánh giá artifact mới |

Code liên quan:

- [NpuEngine.kt](../../android-app/app/src/main/java/com/onevoice/link/core/NpuEngine.kt)
- [NpuPipeline.kt](../../android-app/app/src/main/java/com/onevoice/link/core/NpuPipeline.kt)
- [kokoro_audio.cpp](../../native-core/src/kokoro_audio.cpp)
- [push-npu-models.sh](../../tools/models/push-npu-models.sh)

### Trade-off của Hybrid

- Thêm CPU Kokoro ONNX khoảng 345,6 MB cùng ORT workspace.
- Bỏ ba QNN TTS context có tổng artifact khoảng 193,3 MB.
- Kích thước file không tương đương PSS; cần đo RAM thực tế trên thiết bị sau
  các đợt tối ưu tiếp theo.
- TTS CPU có thể chậm hơn Full-QNN, nhưng chất lượng và độ đầy đủ của câu hiện
  quan trọng hơn latency thấp nhưng waveform sai.

## Giải pháp dài hạn để trở lại Full-NPU

### Phương án A — Export một graph TTS đóng cho toàn utterance (khuyến nghị)

Export graph nhận token, length/mask, style và speed rồi trả waveform hoàn chỉnh,
hoặc ít nhất export một post-alignment graph chứa toàn bộ:

```text
aligned en + aligned asr + ref_s
  → F0Ntrain
  → harmonic source/HAR
  → decoder
  → waveform
```

Nếu dynamic shape không được HTP hỗ trợ ổn định, có thể dùng một số bucket lớn
theo **toàn utterance** (ví dụ short/medium/long), với length/mask và trim contract
rõ ràng. Bucket phải đủ lớn để không chia lại mỗi 625 ms.

### Phương án B — Export streaming model có state chính thức

Nếu bắt buộc streaming theo block, model cần công khai state/cache tensor:

- prosody state;
- decoder convolution/cache state;
- harmonic phase/noise state hoặc graph harmonic source;
- left/right context;
- số sample cần discard ở đầu/cuối mỗi block;
- window/overlap-add và phase-continuity contract.

Host chỉ nên nối block theo contract do exporter cung cấp, không tự suy đoán.

### Phương án C — Giữ split hiện tại nhưng cung cấp reference đầy đủ

Đây là phương án rủi ro hơn, nhưng tối thiểu team model phải cung cấp:

1. Graph F0N/prosody ở precision reference và bản W8A8 để A/B.
2. Exact HAR implementation cùng mọi learned constant, RNG/phase initialization.
3. Quy tắc duration: activation, speed, round/ceil/floor, min/max và padding.
4. Quy tắc alignment, bucket stride, context, trim và crossfade.
5. Golden tensors tại từng ranh giới encoder → prosody → HAR → decoder.

## Artifact cần yêu cầu từ team model

Mỗi lần bàn giao TTS mới cần kèm:

- source/export commit và phiên bản QAIRT/QNN;
- graph name, tensor name, dtype, shape, layout và quantization metadata;
- sample rate, token/phoneme contract, voice/style row contract;
- input length/mask và padding semantics;
- state/cache/overlap contract nếu streaming;
- ít nhất một câu ngắn, một câu dài và một câu nhiều dấu câu làm golden vector;
- token IDs, duration, alignment, F0, N, HAR và waveform reference cho các câu đó;
- waveform hoặc checksum/tolerance của output CPU reference;
- báo cáo chất lượng float so với W8A8 trên cùng tập câu.

Không nên nhận một tập `.bin` là “pipeline hoàn chỉnh” nếu output của stage này
cần một learned stage hoặc model-specific host transform nhưng artifact/code
reference của phần đó không được bàn giao.

## Tiêu chí nghiệm thu Full-NPU TTS mới

1. Không còn discontinuity định kỳ theo đúng chu kỳ bucket/stride.
2. Không mất âm tiết, không nói gấp bất thường và không dừng trước khi hết câu.
3. Duration của cùng câu không lệch lớn bất thường so với CPU reference; mọi
   chênh lệch phải giải thích được bằng `speed` hoặc voice contract.
4. Toàn bộ PCM finite, đúng 24 kHz và `AudioTrack` delivered đủ frame.
5. Test ít nhất câu ngắn, câu trên 5 giây, câu trên 10 giây và câu có nhiều dấu
   câu/số/tên riêng.
6. A/B listening test phải được thực hiện trên cùng text, voice và speed.
7. Device test xác nhận đúng số context QNN, không fallback CPU ngoài những host
   transform đã công bố.

## Lưu ý về ASR

Lần đo nói thật còn phát hiện một lỗi độc lập: CTC decoder từng đọc cả 126
timestep của cửa sổ ASR 10 giây, gồm phần zero-padding sau khi người dùng ngừng
nói, nên có thể lặp một đoạn transcript ở cuối. App đã giới hạn số CTC timestep
theo độ dài audio thực tế và subsampling 8. Vấn đề này không phải nguyên nhân
của artifact TTS, nhưng có thể làm câu đưa vào MT sai nếu tái diễn.

## Quyết định hiện tại

- Dùng **Hybrid** cho demo và vận hành thực tế.
- Giữ **Full-QNN** để benchmark và xác minh artifact mới, không coi chất lượng
  hiện tại ngang CPU reference.
- Không tiếp tục tinh chỉnh crossfade như giải pháp chính.
- Ưu tiên yêu cầu team model re-export TTS dạng whole-utterance hoặc stateful
  streaming, kèm đầy đủ golden vectors và stitching contract.
