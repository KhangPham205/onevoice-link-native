# Nghiên cứu tối ưu route đầu vào (chọn hướng dịch VI↔EN)

> Ngày: 2026-10-17 · Phạm vi: nghiên cứu, chưa thay đổi code ·
> Bối cảnh: 1 người Việt + 1 người Anh, nói lần lượt, tai nghe Bluetooth,
> nhà máy/công trường 85–95 dBA, người dùng bận tay.
>
> Mức tin cậy: **Cao** = số liệu từ nguồn gốc; **TB** = từ abstract/nguồn thứ
> cấp; **Thấp** = ước tính/suy luận, phải đo trên máy.

## 1. Câu hỏi

1. Có cách nào chọn đúng hướng dịch (VI→EN hay EN→VI) cho mỗi utterance tốt
   hơn LID độc lập (SpeechFlow) và dual-ASR hiện tại không?
2. Có nên dùng một "decision model" như Jev không?
3. Phương án nào tối ưu UX khi ở công trường/nhà máy và người dùng bận tay?

## 2. Kết luận

1. **Nút thắt chính là tín hiệu mic, không phải model quyết định.** Với một cặp
   TWS dùng chung, khi mở HFP thường chỉ **một** bud làm mic. Người đeo bud còn
   lại bị thu ở khoảng cách ~1 m. Ở 85–95 dBA, SNR của người này ước tính
   khoảng −20 đến +4 dB (**Thấp**, phải đo). Với tín hiệu như vậy, LID,
   dual-ASR, speaker-ID hay Jev đều sẽ sai nhiều, và ASR của người đó cũng hỏng.
2. **Đổi bài toán từ "đang nói tiếng gì" sang "ai đang nói".** Một phiên chỉ có
   2 người cố định, mỗi người nói đúng một ngôn ngữ. Nhận dạng 2 người đã đăng ký
   giọng dễ hơn nhiều so với nhận dạng ngôn ngữ trên câu ngắn có nhiễu.
3. **Không dùng Jev.** Jev là cloud API, chỉ nhận text/structured state, không
   có audio và không có offline. Vừa trái với yêu cầu offline của cuộc thi, vừa
   trái với privacy gate của app.
4. **Vẫn nên có decision model, nhưng nhỏ và on-device:** logistic regression
   với khoảng 8 đặc trưng, weight lưu trong `BuildConfig`. Chỉ triển khai sau
   khi đã có tín hiệu đầu vào tốt và dữ liệu có nhãn.
5. **Phương án tin cậy nhất cho công trường là mỗi người một kênh mic.** Khi đó
   route gần như tất định, và có thể bỏ hẳn SCO. Phương án này thay đổi tiền đề
   "một cặp JBL dùng chung", nên cần quyết định ở cấp sản phẩm.

## 3. Hiện trạng trong code

| Vấn đề | Vị trí | Ảnh hưởng |
|---|---|---|
| VAD chỉ dựa vào năng lượng (`VAD_RMS_THRESHOLD = 0.008`) | `core/NpuEngine.kt` | Tiếng máy móc có thể tạo đoạn chỉ có nhiễu. Router vẫn buộc phải chọn VI hoặc EN cho đoạn đó (qua prior), trừ khi cả hai điểm đều dưới floor. |
| Luân phiên cứng: lượt chọn bằng `TURN_PRIOR` vẫn được `commit` | `core/AutoHybridPipeline.kt` (`routeWithDualAsr`), `core/DualAsrRouter.kt` | Hangover chỉ 1 s, nên một người ngừng hơn 1 s sẽ tạo 2 đoạn liên tiếp của cùng người. Prior lệch pha, và mọi lượt mơ hồ sau đó sai cho đến khi có một lượt thắng theo margin. |
| Đoạn bị cắt ở mốc 10 s không cập nhật prior | `DualAsrTurnRouter.commit` | Đang bỏ qua thông tin: cắt ở 10 s gần như chắc chắn nghĩa là cùng người vẫn đang nói. |
| Bench LID cho confidence 1.0000 | `docs/proposal/evidence/bench_2026-08-11_*.csv` | Bench dùng fixture sạch/tổng hợp, nên không phản ánh lỗi LID ngoài thực tế. |
| sherpa-onnx 1.13.4 đã có sẵn trong APK | `android-app/app/libs/sherpa-onnx-static-link-onnxruntime-1.13.4.aar` | Đã chứa `SpeakerEmbeddingExtractor`, `SpeakerEmbeddingManager`, Silero/TEN VAD, `OnlineSpeechDenoiser` (GTCRN/DPDFNet) và `KeywordSpotter`. **Không cần thêm dependency**, chỉ cần thêm file model. |

Về độ trễ: trong bench, TTS (Kokoro CPU) chiếm phần lớn TTFA (median 14.6 s).
Stage ASR có median khoảng 361 ms. Nếu speaker-ID đã chắc chắn trước, chỉ cần
chạy **một** ASR thay vì hai, tiết kiệm thêm một lần encoder + RNN-T decode.

## 4. Nút thắt vật lý: mic của TWS dùng chung

- **Chỉ một bud làm mic khi gọi.** Patent của Google ghi "wireless earbuds only
  support one of the buds serving as the active microphone during a phone call"
  [P1] (**Cao**).
- **Bud làm mic có thể đổi bất ngờ.** Qualcomm TrueWireless Mirroring đổi bud
  primary khi bud bị tháo ra, cất vào hộp hoặc gần hết pin. Nó cũng có thể đổi
  nếu link hoặc chất lượng mic của bud secondary tốt hơn hẳn [P2] (**Cao**).
  Mic có thể chuyển sang người kia giữa phiên.
- **JBL Wave Beam:** chưa có tài liệu nói rõ bud nào làm mic. Quick-start guide
  cho thấy dùng mono được với L, R hoặc cả hai [P3] (**Thấp**). Cần đo.
- **Mức giọng người ở xa:** giọng nói to khoảng 76 dB ở 0.3 m [P4], nên ở 1 m
  còn khoảng 66–72 dB. So với nhiễu 85–95 dB thì SNR âm. Ngoài ra, khử nhiễu
  của bud được thiết kế để loại bỏ âm không phải giọng người đeo (**Thấp**).
- **Hệ quả:** người đeo bud có mic được thu tốt. Người kia bị thu kém cho cả
  route lẫn ASR. Không phần mềm nào khôi phục được thông tin đã mất ở mic.

## 5. Đánh giá các hướng

### 5.1 LID độc lập: vì sao sai

- **Thời lượng câu.** Trên VoxLingua107 (dữ liệu sạch), ECAPA-TDNN-LID sai
  11.9% với câu 0–5 s và 6.7% với câu 5–20 s; XLS-R sai 9.1% và 5.0% [L1]
  (**Cao**). Ngay cả bài toán chỉ 2 ngôn ngữ, PPRLM cổ điển vẫn sai 5% ở 10 s và
  2% ở 45 s qua điện thoại [L2] (**Cao**). Câu ≤ 1 s vẫn "far from
  satisfactory" [L3].
- **Accent và kênh truyền.** Model card SpeechBrain VoxLingua107 ghi model này
  "doesn't work well on speech with a foreign accent" [L5]. Apple ghi nhận cặp
  ngôn ngữ tệ nhất chỉ đạt 45.9% với giọng có accent [L6]. HFP CVSD là băng hẹp
  8 kHz [L7]. Thích nghi với dữ liệu đúng miền cải thiện LID 30% [L8] (**TB**).
- **Nhiễu.** Không tìm thấy đường cong độ chính xác theo SNR cho ECAPA, Whisper
  hay MMS LID. Đây là khoảng trống, phải tự đo (**Thấp**).
- **Giới hạn LID 107 ngôn ngữ xuống 2 logit** là cách làm chuẩn: Apple và
  Whisper đều mask posterior [L6][L10]. Huấn luyện riêng cho cặp ngôn ngữ
  (tuplemax) giảm 39% lỗi tương đối [L11]. Tuy nhiên mask lúc inference chỉ sửa
  được lỗi do ngôn ngữ thứ ba thắng, không sửa nhầm lẫn VI↔EN. **Chỉ nên dùng
  log-odds VI–EN của LID như một đặc trưng, không dùng làm quyết định.**

### 5.2 Dual-ASR confidence (đang dùng)

- **Hướng đi có cơ sở.** Thêm tri thức từ vựng/LM giảm tới 50% lỗi LID hai
  ngôn ngữ [L12]. NVIDIA dùng 5 model đơn ngữ chọn theo confidence: trên câu
  3 s đạt 90.6%, XLS-R đạt 93.5%, fusion cả hai đạt **96.0%**. Bộ chọn của họ là
  **logistic regression train trên chỉ 100 câu** [L13]. Với 2 model, nó học
  `a·c₁ + b·c₂ > c`, tức là tự hiệu chỉnh hai model có vocabulary khác nhau.
  So max thô như hiện tại thì không làm được việc đó.
- **Điểm yếu cần lường trước:**
  - ASR đơn ngữ có thể phiên âm tự tin cả tiếng nước ngoài [L14] (**TB**).
  - Internal LM của RNN-T có thể hallucinate, nặng hơn khi audio ngoài miền
    [L15].
  - Max-prob của CTC/RNN-T overconfident: median xác suất của các token sai
    vẫn > 0.9 [L16].
- **Đặc trưng tốt hơn log-prob trung bình mỗi token:**
  - **Log-prob mỗi frame tính cả blank.** Hai encoder có cùng 248 frame, nên
    điểm của hai bên có chung mẫu số.
  - Tỷ lệ frame non-blank, số token mỗi giây.
  - Entropy Tsallis/Rényi (bỏ blank). Phát hiện lỗi tốt hơn max-prob 2–4 lần
    và lọc tới 40% hallucination trên nhiễu [L16].
  - Điểm LM ở mức text: rerank bằng LM/text-LID tăng 8.7 điểm cho MMS và 6.1
    cho Whisper [L17]. Với tiếng Việt có thể kiểm tra âm tiết hợp lệ (chỉ khoảng
    6.500 âm tiết) [L18]; với tiếng Anh, tỷ lệ từ ngoài lexicon.

### 5.3 Đăng ký giọng (speaker enrollment)

**Ý tưởng:** đầu phiên, mỗi người nói 2–3 câu (≥ 3 s mỗi câu), đeo đúng như lúc
sử dụng. Với mỗi utterance, tính embedding rồi so cosine với 2 giọng mẫu.

**Model ứng viên** (tất cả 16 kHz; sherpa-onnx không có bản 8 kHz và tự resample):

| Model | Tham số | File ONNX trong sherpa-onnx | EER VoxCeleb1-O | EER CN-Celeb | Chi phí |
|---|---|---|---|---|---|
| **CAM++** (đề xuất) | 7.2M | `3dspeaker_..._campplus_sv_en_voxceleb_16k` 29.6 MB | 0.65–0.73% | 6.78% | 1.72 GFLOPs, RTF 0.013 (1 luồng CPU desktop) |
| WeSpeaker ResNet34 | 6.6M | 26.5 MB | 0.867% | 6.92–6.97% | 6.84 GFLOPs, RTF 0.032 |
| NeMo TitaNet-S (A/B) | 6.4M | 40.3 MB | 1.08–1.15% | — | Train trên dữ liệu điện thoại, chunk 1.5–3 s |
| ECAPA-TDNN | ~21M | không có sẵn | 0.80% | 7.45–8.01% | 3.96 GFLOPs |
| ERes2NetV2 | 17.8M | 71.4 MB (chỉ zh-cn) | 0.61% | 6.14% | 12.6 GFLOPs, tốt nhất với câu ngắn |

- **Độ trễ trên điện thoại:** chưa có số liệu công bố cho Android. Ngoại suy từ
  RTF desktop: khoảng 40–120 ms cho mỗi utterance 3 s trên big core (**Thấp**).
- **`SpeakerEmbeddingManager.search()` chỉ trả về tên người thắng.** Cần tự tính
  2 cosine trong Kotlin để có margin.
- **Tránh các bản WeSpeaker `_LM` cho câu ngắn:** large-margin fine-tuning chỉ
  có lợi khi câu dài hơn 3 s.
- **Không khử nhiễu trước khi tính embedding:** dùng DeepFilterNet3 trước đó
  làm EER tăng từ 3.3% lên 7.7% ở 0 dB [S8].

**Độ chính xác theo thời lượng và nhiễu:**

- **Thời lượng:** enroll 5 s, test 1 s cho EER 3.9–4.8%. Enroll đầy đủ, test 2 s
  cho EER 1.5–3.3% (ECAPA 1.95%, ERes2NetV2 1.48%) [S5][S6].
- **Nhiễu, với ResNet nhỏ đã train có nhiễu:** babble ở 0/5/10 dB cho EER
  11.6/5.1/3.5%; nhiễu chưa từng thấy ở 0/5/10 dB cho 10.3/6.3/4.3% [S7].
- **Nhiễu, với ECAPA dùng nguyên bản:** babble ở 0/−5 dB cho EER 19.1/34.9% [S8].
- **Chọn 1 trong 2 người dễ hơn verification mở.** Theo giả định Gaussian cùng
  phương sai, xác suất chọn sai giữa 2 người là `Φ(√2·Φ⁻¹(EER))` (**Thấp**,
  suy luận, chưa có công bố):

  | EER | Lỗi khi chọn 1 trong 2 |
  |---|---|
  | 2% | 0.2% |
  | 5% | 1.0% |
  | 10% | 3.5% |
  | 20% | 11.7% |
  | 30% | 22.9% |

**Ngôn ngữ, kênh và khoảng cách:**

- **Bối cảnh này thuận lợi về ngôn ngữ.** Mỗi người enroll và test bằng cùng
  ngôn ngữ của mình, và hai người nói hai ngôn ngữ khác nhau. TidyVoice đo được
  EER 0.88% cho trường hợp này, so với 2.97% khi cả hai nói cùng ngôn ngữ [S9].
- **Model train bằng tiếng Anh vẫn dùng tốt cho tiếng Việt.** Hệ thống tốt nhất
  VLSP 2021 dựa trên model pretrain tiếng Anh, đạt EER 1.755% và 1.95% ở bài
  nhiễu [S10].
- **Enroll đúng vị trí đeo.** Enroll bằng mic gần rồi test bằng mic xa cho EER
  6.3–7.2% [S11]. Embedding mã hóa cả kênh truyền [S12]. Nếu enroll đúng vị trí
  thì khác biệt gần/xa giữa hai người **giúp** phân biệt họ, thay vì gây hại.

**Kỳ vọng độ chính xác** (**Thấp**, phải đo):

- Người đeo bud có mic (câu 1–3 s, SNR 0–10 dB): EER khoảng 4–12%, tức lỗi chọn
  1 trong 2 khoảng **1–4%**.
- Người đeo bud không có mic (SNR ≤ 0 dB): EER khoảng 15–35%, tức lỗi khoảng
  **7–28%**.

**Rủi ro:**

- Nếu bud làm mic bị đổi giữa phiên, cả hai giọng mẫu đều mất hiệu lực.
- Người đứng cạnh nói xen: luật chọn người gần nhất luôn chọn một trong hai. Cần
  thêm ngưỡng cosine tuyệt đối để fail closed.
- Hai giọng cùng giới tính và giống nhau.
- HFP băng hẹp (CVSD) đưa vào model 16 kHz. Nếu link chỉ đạt CVSD, A/B với
  TitaNet-S.

**Cập nhật giọng mẫu trong phiên:**

- Giữ cố định centroid lúc enroll. Trộn thêm running mean của các lượt đã chấp
  nhận, giới hạn tối đa khoảng 50% trọng số.
- Chỉ cập nhật khi speaker-ID và ASR cùng kết luận, câu dài ≥ 1.5–2 s và không
  bị cắt ở mốc 10 s [S13][S14].
- Theo dõi cosine giữa hai centroid; nếu tăng dần thì rollback. Có thể giữ nhiều
  centroid cho mỗi người [S15].

### 5.4 Decision model (fusion)

**Đặc trưng** (phần lớn là hiệu VI − EN):

1. Log-prob trung bình mỗi token (đang có).
2. Log-prob mỗi frame tính cả blank.
3. Tỷ lệ non-blank, số token mỗi giây.
4. Entropy trung bình và nhỏ nhất.
5. Điểm LM/lexicon mỗi từ.
6. Log-odds VI–EN của SpeechFlow, giới hạn còn 2 ngôn ngữ.
7. **Cosine speaker tới 2 giọng mẫu.**
8. Thời lượng, SNR ước tính, codec HFP, và tương tác `score × 1/√duration`
   (cách fusion của BUT/AGNITIO LRE09 [F1]).

**Model:** logistic regression nhị phân có L2, đầu ra là log-likelihood ratio
đã hiệu chỉnh. Đây là cách fusion chuẩn trong NIST LRE [F1][F2][F3]. Weight
lưu trong `gradle.properties`/`BuildConfig`, đúng convention hiện tại. Chỉ cân
nhắc gradient-boosted trees khi có hơn khoảng 5.000 nhãn.

**Prior hội thoại:** để ngoài LR, dùng Markov 2 trạng thái thay cho luật
luân phiên cứng.

- `prior(VI_t) = s·P(EN_{t−1}) + (1−s)·P(VI_{t−1})`, dùng posterior mềm của
  lượt trước. Ước lượng `s` (xác suất đổi người) từ log.
- Sau khi bị cắt ở mốc 10 s, đặt `s ≈ 0.05`.
- Khi khoảng lặng dài, kéo prior dần về 0.5.
- Context prior của Apple nâng độ chính xác từ 92.5% lên 97.0% [L6].

**Từ chối khi không chắc (abstention):** chỉ chấp nhận khi
`|log-odds| ≥ τ`. Chọn `τ` trên tập held-out sao cho cận trên Clopper-Pearson 95%
của tỷ lệ chọn sai hướng trong các lượt được chấp nhận ≤ 1% [F4][F5]. Nếu hiệu
chỉnh hoàn hảo thì `τ ≈ ln 99 ≈ 4.6`, nhưng vẫn phải đặt theo dữ liệu.

**Kế hoạch dữ liệu:**

- **Train:** ≥ 1.000 câu có nhãn cho mỗi ngôn ngữ, từ ≥ 30 người mỗi ngôn ngữ.
  Phân tầng theo 1–3/3–6/6–10 s, yên tĩnh/85/90/95 dBA, và mSBC/CVSD.
- **Test:** dùng người khác với tập train. Để quan sát khoảng 30 lỗi ở mức 1%
  cần 3.000 câu (rule of 30 [F2]). Hoặc 300 câu không có lỗi nào thì chặn được
  tỷ lệ lỗi ở 1% (rule of three [F6]). Số người khác nhau quan trọng hơn số lần
  thử [F7].
- **Lấy nhãn rẻ:** mỗi row `OneVoiceRouting` đã có đủ điểm số, chỉ thiếu nhãn.
  Có thể lấy nhãn từ phiên có kịch bản (biết trước ai nói) và từ thao tác "flip"
  của người dùng (xem mục 6).

**Hiệu quả kỳ vọng:** các kết quả fusion đã công bố giảm 39–60% lỗi tương đối so
với tín hiệu đơn tốt nhất [L6][L11][L12][L13] (**Thấp–TB**). Chưa có số liệu
cho VI/EN ở 85–95 dBA.

### 5.5 Jev

Jev là "System One Model" của TypeSafe AI, early access từ 2026-09-15 [J1][J2].

- **Đầu vào:** text hoặc structured state cùng các câu hỏi có kiểu; trả về lựa
  chọn hoặc điểm kèm xác suất. Chưa hỗ trợ ảnh; không nhắc tới audio.
- **Triển khai:** hosted API (có console, tính tiền theo token). Không có chế độ
  on-device hay offline.
- **Tuyên bố của vendor:** độ trễ 70–500 ms và output "calibrated" nhờ phương
  pháp huấn luyện RLCD. Chưa có kiểm chứng độc lập [J3].
- **Kết luận:** không phù hợp. App phải chạy offline và hiện không có quyền
  `INTERNET`. Một round-trip mạng ở công trường cũng không đảm bảo. Logistic
  regression ở mục 5.4 làm đúng vai trò đó trong vài micro-giây.
- Không tìm thấy model speech/LID nào tên JEV. Nếu ý là JEPA, đó là phương pháp
  pretrain encoder, không phải tầng quyết định [J4].

### 5.6 Tín hiệu vật lý và phần cứng

| # | Phương án | Độ tin cậy | UX với PPE/nhiễu | Chi phí | Công sức |
|---|---|---|---|---|---|
| 1 | Hai transmitter mic không dây, receiver USB-C chế độ split/stereo, mic boom đặt sát miệng; JBL chỉ dùng phát A2DP | Cao | Tốt, rảnh tay | ~170–350 USD + mic | TB |
| 2 | Mỗi người một nút PTT BLE | Rất cao (định danh theo địa chỉ thiết bị) | Dùng được khi đeo găng, nhưng cần một tay | ~33–45 USD/nút | TB |
| 3 | Hai headset công nghiệp (Sena, 3M Peltor) | Cao, nếu app nhận được PTT | Tốt nhất (vừa bảo vệ tai vừa có mic boom) | ~249–420+ USD/cái | Cao |
| 4 | Chạm vào bud JBL của mình trước khi nói | TB | Kém khi đeo găng hoặc chụp tai | 0 | Thấp–TB |
| 5 | Chỉ dùng keyword spotting hoặc dual-ASR | Thấp–TB ở 90 dBA | Rảnh tay | 0 | Thấp |
| 6 | LE Audio thu mic riêng từng bud | Chưa kiểm chứng | — | Mua tai nghe mới | Cao |
| 7 | Cử chỉ đầu | Không khả thi | — | — | — |

**Chạm theo từng bud** (phương án 4):

- App JBL cho gán điều khiển khác nhau cho bud trái và bud phải [H1]. Muốn phân
  biệt hai bud thì hai bên phải gán hành động khác nhau.
- Từ API 26, phím media được gửi tới app có MediaSession phát audio gần nhất
  [H2]. App phát bản dịch qua A2DP nên thường thỏa điều kiện này.
- **Khi SCO đang mở, cú chạm thành lệnh cuộc gọi.** AudioService mở SCO dưới
  dạng virtual voice call [H3]. AOSP xử lý `AT+CHUP` bằng
  `stopScoUsingVirtualVoiceCall()`, tức là ngắt capture. `AT+CKPD` khi không có
  cuộc gọi thì ngắt audio [H4]. Vì vậy chỉ chạm được ở trạng thái **IDLE**, và
  mỗi lượt phải chịu thêm thời gian mở SCO.
- Cú chạm chỉ cố định hướng dịch, không sửa được việc người thứ hai bị thu ở xa.
- Cảm ứng chạm không đáng tin khi đeo găng [H5], và không dùng được dưới chụp
  tai.

**Mic không dây kênh đôi** (phương án 1):

- **RODE Wireless GO Gen 3:** chế độ Split ghi mỗi transmitter vào một kênh
  (L/R), xuất stereo qua USB-C. Có cổng 3.5 mm khóa để cắm mic boom chống ồn.
  Giá khoảng 169–199 USD, pin 7 giờ [H6][H7][H8].
- **DJI Mic 2:** chế độ stereo tách kênh. FAQ của DJI chỉ xác nhận cho iPhone;
  nhà bán lẻ nói "most Android" (**TB**). Có cổng 3.5 mm. DJI Mic 3 không có
  cổng mic ngoài [H9][H10].
- **DJI Mic Mini:** có stereo, khử nhiễu, pin 11.5 giờ, giá 99 USD, nhưng không
  có cổng mic ngoài, nên chỉ hợp với nhiễu vừa [H11].
- **Hollyland Lark M2:** có thể chỉ xuất stereo qua cổng 3.5 mm, nên tránh nếu
  định dùng USB-C (**Thấp**) [H12].
- **Android (theo audio policy AOSP):**
  - Input USB là mặc định cho nguồn MIC, VOICE_RECOGNITION và UNPROCESSED [H13].
  - Output media đi tới thiết bị tháo rời kết nối gần nhất. Nếu receiver có
    output USB, nó có thể giành phát khỏi A2DP. Cần ghim AudioTrack bằng
    `setPreferredDevice(A2DP)` và giữ kiểm tra `getRoutedDevice()` hiện có.
  - Positional channel mask tối đa 2 kênh [H14].
  - Về nguyên tắc, thu USB đồng thời phát A2DP được, và **cách này bỏ hẳn SCO**.
- **Rủi ro:** cổng USB-C bị chiếm nên không sạc được, trừ khi receiver có
  pass-through. Cần thử trên SM8550 xem Samsung có giữ tách L/R không.
- **Cách chọn hướng:** VAD riêng cho từng kênh, rồi so năng lượng giữa hai kênh.

**Hai tai nghe Bluetooth riêng:**

- Android chỉ cho một mic HFP hoạt động tại một thời điểm. AOSP từ chối SCO với
  thiết bị không phải active device (`isScoAcceptable`) [H15]. Điều này khớp với
  probe ngày 2026-08-17 (`docs/probes/bluetooth-routing-probe.md`).
- Khi headset gửi `AT+BVRA` (gọi trợ lý giọng nói), AOSP gọi
  `setActiveDevice(fromDevice)` [H15]. Một lần bấm nút có thể cho biết headset
  nào vừa bấm, nhưng Samsung có thể chuyển lệnh này cho Bixby (chưa kiểm chứng).
- Samsung Dual Audio chỉ phát cùng một âm thanh ra hai thiết bị, không có mic
  [H16].
- Chưa có số đo công bố về thời gian chuyển SCO giữa hai headset trên Android.

**Cử chỉ đầu và keyword:**

- Cử chỉ đầu không có API cho bên thứ ba: AirPods chỉ dùng với Siri; Galaxy
  Buds4 chỉ dùng để nhận/từ chối cuộc gọi; `TYPE_HEAD_TRACKER` "typically not
  available for apps" [H17][H18][H19].
- Keyword spotting (có sẵn `KeywordSpotter` của sherpa-onnx) vẫn bị giới hạn bởi
  SNR của mic. Chỉ nên là tùy chọn bổ sung.

### 5.7 Sản phẩm thương mại làm thế nào

| Sản phẩm | Cách xác định người nói |
|---|---|
| Timekettle | Phần cứng: mỗi người một bud; W4 có cảm biến bone-conduction chỉ bắt giọng người đeo. Touch mode là chạm để nói. Một review ghi nhận Simul mode "occasionally mixed up which person was speaking" [C1][C2]. |
| Google Translate / Pixel Buds | Tự nhận dạng 1 trong 2 ngôn ngữ đã chọn, có nút override thủ công. Không công bố độ chính xác [C3]. |
| Samsung Interpreter | Mỗi nửa màn hình cố định một ngôn ngữ: chạm mic của mình, hoặc tự luân phiên khi người kia nói xong [C4]. |
| Pocketalk / Vasco | Bấm nút theo ngôn ngữ mình sắp nói [C5]. |

Không tìm thấy sản phẩm nào dùng đăng ký giọng để chọn hướng dịch (**TB**). Đây
là một điểm mới có thể dùng cho tiêu chí Innovation (25%) của cuộc thi.

## 6. UX khi người dùng bận tay

- **Mặc định không cần chạm:** app tự chọn hướng bằng speaker-ID + fusion.
- **Khi từ chối một câu:** phát âm báo ngắn kèm câu nhắc trên kênh của từng
  người, "Xin nói lại" ở tai trái (VI) và "Please repeat" ở tai phải (EN).
  Tín hiệu nên to hơn ngưỡng bị che khoảng 13–15 dB (ISO 7731) [U1].
- **Khi chọn sai hướng:** người dùng "flip" bằng thao tác trên tai nghe. App
  dùng lại transcript của ASR kia đã có sẵn, chỉ chạy lại MT + TTS. Mỗi lần flip
  được ghi lại làm nhãn train.
- **Override chủ động:** chạm bud hoặc nút PTT trong trạng thái IDLE để cố định
  hướng cho lượt kế tiếp (enum `RoutingDecisionReason.EXPLICIT` đã có).
- **Đọc lại để xác nhận** (confirmation/back-translation) làm được nhưng tăng độ
  trễ [U2]. Không nên dùng mặc định.

## 7. Lộ trình đề xuất

| Tầng | Việc | Chi phí | Điều kiện chuyển tầng |
|---|---|---|---|
| **0. Đo (go/no-go)** | Thu âm thật qua JBL, đeo đúng cách, phát nhiễu nhà máy 85/90 dBA. Mỗi người khoảng 50 câu (1–3/3–6/6–10 s). Đo SNR từng người, ghi codec HFP. Chạy offline dual-ASR, LID và CAM++ trên cùng bộ dữ liệu. | 1–2 ngày | Xem quy tắc quyết định bên dưới |
| **1. Sửa đầu vào** | Silero/TEN VAD thay VAD năng lượng. Prior Markov mềm; cắt ở 10 s nghĩa là cùng người; quyết định dựa trên prior không được tự commit như bằng chứng. | Thấp | — |
| **2. Speaker enrollment** | CAM++ qua sherpa-onnx, enroll tại chỗ, ngưỡng cosine tuyệt đối, cập nhật centroid có kiểm soát. Khi chắc chắn thì chỉ chạy 1 ASR. Thêm `routingMode=SPEAKER` hoặc `FUSION` để A/B với `DUAL_ASR`. | TB | Tỷ lệ chọn sai trên tập test ≤ ~2% |
| **3. Fusion LR + abstention** | Train LR từ các row `OneVoiceRouting` đã có nhãn; đặt `τ` theo cận Clopper-Pearson. Thêm âm báo, câu nhắc và flip. | Thấp (khi đã có dữ liệu) | Chọn sai ≤ 1% với độ tin cậy 95%, tỷ lệ từ chối chấp nhận được |
| **4. Mỗi người một kênh mic** | Mic không dây split L/R qua USB-C cùng mic boom; JBL chỉ phát A2DP; bỏ SCO. | ~170–350 USD | Khi tầng 0 cho thấy người đeo bud không có mic có SNR quá thấp |

**Quy tắc quyết định sau tầng 0** (đề xuất, chỉnh theo dữ liệu):

- Người đeo bud không có mic có SNR ≥ ~5 dB **và** CAM++ chọn sai ≤ ~2%: đi
  đường phần mềm (tầng 1 → 2 → 3).
- SNR của người này ≤ ~0 dB, hoặc ASR của người này hỏng: phần mềm không cứu
  được, cần tầng 4 (hoặc headset có mic boom cho từng người).

## 8. Câu hỏi mở cần kiểm chứng trên máy

1. JBL Wave Beam dùng mic của bud nào khi mở HFP? Có đổi bud trong phiên không?
2. Link HFP thực tế là mSBC (16 kHz) hay CVSD (8 kHz)?
3. Cú chạm bud ở trạng thái IDLE có tới được `MediaSession` của app không, và
   hai bud có gửi keycode khác nhau sau khi cấu hình trong app JBL không?
4. Samsung SM8550 có giữ tách L/R khi thu stereo USB từ receiver RODE/DJI, và
   A2DP có giữ được route khi receiver đang cắm không?
5. Độ trễ thực tế của CAM++ trên big core SM8550 cho câu 1–10 s.

## 9. Nguồn

**Nút thắt mic TWS**

- [P1] https://patents.google.com/patent/US10952002B2
- [P2] https://assets.qualcomm.com/rs/385-TWS-803/images/whitepaper-the-evolution-of-true-wireless-technology-2020.pdf
- [P3] https://id.jbl.com/on/demandware.static/-/Sites-masterCatalog_Harman/default/dw72f0d5b9/pdfs/JBL_Wave%20Beam_Quick%20Start%20Guide.pdf
- [P4] https://www.engineeringtoolbox.com/voice-level-d_938.html

**LID và ASR confidence**

- [L1] https://arxiv.org/pdf/2210.15781
- [L2] https://www.ll.mit.edu/r-d/publications/comparison-four-approaches-automatic-language-identification-telephone-speech
- [L3] https://arxiv.org/abs/2203.00328v2
- [L4] https://arxiv.org/pdf/1806.00616
- [L5] https://huggingface.co/speechbrain/lang-id-voxlingua107-ecapa
- [L6] https://arxiv.org/pdf/2001.11019
- [L7] https://www.collabora.com/news-and-blog/news-and-events/pipewire-bluetooth-support-status-update.html
- [L8] https://www.ll.mit.edu/r-d/publications/approaches-language-identification-mismatched-environments
- [L10] https://discuss.huggingface.co/t/language-detection-with-whisper/26003
- [L11] https://arxiv.org/pdf/1811.12290
- [L12] https://publications.ri.cmu.edu/lvcsr-based-language-identification
- [L13] https://arxiv.org/html/2306.15824v1
- [L14] https://www.mdpi.com/2076-3417/15/14/7691
- [L15] https://arxiv.org/pdf/2203.02317
- [L16] https://ar5iv.labs.arxiv.org/html/2212.08703
- [L17] https://arxiv.org/html/2409.18428
- [L18] https://arxiv.org/pdf/1904.05569

**Speaker-ID**

- [S1] https://github.com/modelscope/3D-Speaker
- [S2] https://github.com/k2-fsa/sherpa-onnx/releases/tag/speaker-recongition-models
- [S3] https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/kotlin-api/Speaker.kt
- [S4] https://github.com/wenet-e2e/wespeaker/blob/master/docs/pretrained.md
- [S5] https://arxiv.org/html/2601.13999
- [S6] https://arxiv.org/abs/2406.02167
- [S7] https://arxiv.org/pdf/2309.08320
- [S8] https://arxiv.org/pdf/2508.18913
- [S9] https://arxiv.org/html/2601.21960v1
- [S10] https://jcsce.vnu.edu.vn/index.php/jcsce/article/view/333
- [S11] https://arxiv.org/abs/2005.08046
- [S12] https://arxiv.org/pdf/1909.06351
- [S13] https://www.microsoft.com/en-us/research/?p=166284
- [S14] https://arxiv.org/pdf/2109.06483
- [S15] https://arxiv.org/pdf/2211.04768
- CAM++: https://arxiv.org/pdf/2303.00332 · TitaNet: https://arxiv.org/pdf/2110.04410

**Fusion, hiệu chỉnh và thống kê**

- [F1] https://fit.vut.cz/research/group/speech/public/publi/2009/brummer_BUT_AGNITIO_LRE09_SYSD.pdf
- [F2] https://arxiv.org/pdf/1304.2865
- [F3] https://arxiv.org/pdf/1602.01929
- [F4] https://arxiv.org/abs/1705.08500
- [F5] https://arxiv.org/abs/2110.01052v4
- [F6] https://www.johndcook.com/blog/2023/08/30/first-time-seeing-a-rare-event/
- [F7] https://www.nist.gov/document/thieme-presentationpdf

**Jev**

- [J1] https://typesafe.ai/blog/introducing-system-one-models-and-jev
- [J2] https://www.digitalocean.com/resources/articles/what-is-jev
- [J3] https://letsdatascience.com/news/typesafe-ai-launches-jev-decision-model-889a38c0
- [J4] https://arxiv.org/pdf/2507.02915

**Phần cứng và Android**

- [H1] https://mm.jbl.com/app.html
- [H2] https://developer.android.com/media/legacy/media-buttons
- [H3] https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/audio/BtHelper.java
- [H4] https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/android/app/src/com/android/bluetooth/hfp/ (`HeadsetStateMachine.java`, `HeadsetSystemInterface.java`)
- [H5] https://piefed.jeena.net/post/146273
- [H6] https://rode.com/user-guides/wireless-go-gen-3/routing-modes
- [H7] https://rode.com/en-be/user-guides/wireless-go-gen-3/getting-started
- [H8] https://procam.com/products/rode-wireless-go-gen-3-2-person-compact-digital-wireless-microphone-system-recorder-2-4-ghz-white
- [H9] https://www.dji.com/au/mic-2/faq
- [H10] https://www.lensrentals.com/buy/dji-mic-2-transmitter-w-lav-mic
- [H11] https://store.dji.com/product/dji-mic-mini-tx-rx
- [H12] https://tsgdoc.socsci.ru.nl/index.php/Hollyland_Lark_M2
- [H13] https://android.googlesource.com/platform/frameworks/av/+/refs/heads/main/services/audiopolicy/enginedefault/src/Engine.cpp
- [H14] https://source.android.com/docs/automotive/audio/microphone-input
- [H15] https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/android/app/src/com/android/bluetooth/hfp/HeadsetService.java
- [H16] https://www.samsung.com/ae/support/mobile-devices/how-can-i-use-dual-audio-to-share-media-on-multiple-devices/
- [H17] https://support.apple.com/guide/airpods/devb2c431317
- [H18] https://www.samsung.com/uk/support/mobile-devices/how-to-use-head-gestures-on-the-samsung-galaxy-buds4-series/
- [H19] https://developer.android.com/reference/android/hardware/Sensor
- PTT BLE: https://commgearsupply.com/products/mini-bluetooth-radio-push-to-talkptt-button
- Sena Tufftalk Lite: https://quotalavoro.it/en/product/1637 · 3M Peltor WS LiteCom Pro III: https://3m.com/3M/en_US/p/d/b10263723

**Sản phẩm thương mại và UX**

- [C1] https://audioxpress.com/news/timekettle-expands-possibilities-on-ai-powered-language-translation-with-new-w4-ai-interpreter-earbuds
- [C2] https://translators-ranking.co.uk/earbuds/timekettle-wt2-edgew3/
- [C3] https://support.google.com/translate/answer/6142474
- [C4] https://www.samsung.com/uk/support/mobile-devices/how-to-set-up-and-use-the-interpreter-app-on-the-galaxy-s24/
- [C5] https://eftm.com/2018/08/pocketalk-instant-voice-controlled-translations-in-74-languages-51296
- [U1] https://yadda.icm.edu.pl/baztech/element/bwmeta1.element.baztech-2b466ffb-550d-460d-82ac-a90a05ea171b/c/Zera.pdf
- [U2] https://www.isi.edu/results/publications/18308/evaluating-different-confirmation-strategies-for-speech-to-speech-translation-systems
