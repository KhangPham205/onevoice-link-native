# Phân tích lỗi dịch / nghe / đọc – bộ mẫu 200 câu (OneVoice Link)

Ngày phân tích: **04/11/2026**. Dữ liệu: lượt chạy `eval-results/2026-11-03_dataset200_vieneu/`
trên SM8550, cấu hình mặc định hiện tại:
- Dual-ASR: GIPFormer VI và Parakeet EN.
- Marian chạy trên HTP.
- Kokoro EN và VieNeu-TTS v3 Nano VI.

Văn bản ASR và MT của lượt này giống hệt lượt Kokoro ngày 02/11; chỉ có giọng Việt khác. Số
liệu tổng nằm trong [báo cáo đo 02/11](P3_ket-qua-do_2026-11-02.md). Tài liệu này đi vào
**từng câu sai**: sai ở đâu, do model nào, và vì sao.

---

## 1. Tóm tắt

| # | Thành phần | Mức độ | Số câu là nguyên nhân chính* | Bằng chứng chính |
|---|---|---|---|---|
| 1 | **Marian en→vi** (opus-mt-en-vi, Tatoeba 2020) | **Nặng nhất** | 25 / 91 câu EN→VI | Bỏ hẳn câu thứ hai trở đi ở 40/44 câu có nhiều câu. Dịch từng chữ với lời nói tự nhiên. Méo số và tên riêng. |
| 2 | **Marian vi→en** (opus-mt-vi-en, Tatoeba 2020) | Nặng | 13 / 100 câu VI→EN | Chọn sai nghĩa từ (giờ → hours, oát → o'clock, giao lưu → traffic). Văn phong phụ đề phim ("Hey, you…"). |
| 3 | **Chính sách định tuyến dual-ASR** | Trung bình | 9 / 100 câu EN | 5 câu bị nhầm sang tiếng Việt vì GIPFormer chỉ nhận ra 1 âm "à". 4 câu dài hơn 10 s bị từ chối. |
| 4 | **GIPFormer (ASR VI)** | Nhẹ về từ, đáng kể về dấu câu | 4 (nhận sai từ) + 8 (thiếu dấu câu) | WER chỉ 1,3%, nhưng xuất chữ thường, không dấu câu: câu hỏi mất "?" và Marian dịch thành câu khẳng định. Lỗi từ đều là nhầm thanh điệu. |
| 5 | **Parakeet (ASR EN)** | Trung bình | 4 | WER 11,5%; khoảng 1/4 là do cách chấm, WER thật khoảng 8,7%. Nghe sai danh từ ở giọng điện thoại hoặc giọng nói nhanh. |
| 6 | **VieNeu Nano (TTS VI)** | Nhẹ | 0 (khoảng 5 câu đọc lệch nhẹ) | Phần lớn "lỗi" là đọc đúng chữ rác do MT tạo ra. Lỗi thật nằm ở từ tiếng Anh xen trong câu Việt. |
| 7 | **Kokoro EN (TTS EN)** | Không đáng kể | 0 | 4 câu bị đánh lỗi đều do cách chấm (earset / ear set, "%"). |

\* Câu có COMET < 0,70 (bản dịch sai rõ) hoặc bị định tuyến sai, gán cho **một** nguyên nhân chính
theo quy tắc ở mục 2.3.

**Ba kết luận quan trọng nhất:**

1. **Nút thắt là MT, không phải ASR.**
   - Kể cả khi đầu vào là câu gốc chuẩn (ASR hoàn hảo), Marian vẫn dịch sai rõ 20/100 câu VI→EN
     và 21/100 câu EN→VI.
   - Nếu ASR hoàn hảo, COMET chỉ tăng từ 0,789 lên 0,818 (VI→EN) và từ 0,761 lên 0,787 (EN→VI).
2. **Một sửa đổi rẻ cho EN→VI đã được cài vào app và đo trên thiết bị:** tách bản ASR thành từng
   câu rồi dịch từng câu.
   - COMET EN→VI trên SM8550 tăng từ **0,761 lên 0,789** (+0,028), bằng mức "ASR hoàn hảo".
   - Riêng nhóm nhiều câu tăng từ 0,713 lên 0,770. chrF++ tăng từ 46,9 lên 52,8.
   - Đổi lại, độ trễ MT p50 tăng khoảng 100 ms. Chi tiết ở mục 5.2.
3. **Marian trên HTP không làm giảm chất lượng.** Cùng đầu vào, bản chạy trên CPU máy tính cho ra
   văn bản giống hệt ở 95/100 câu VI→EN và 83/91 câu EN→VI. COMET 0,784 so với 0,789, và 0,759
   so với 0,761.

---

## 2. Phương pháp

### 2.1 Điểm từng câu

| Cột | Ý nghĩa |
|---|---|
| `asr_wer` | WER của bản ASR so với câu gốc, cùng chuẩn hoá với `score_dataset_eval.py` (NFC, chữ thường, bỏ dấu câu, số đổi thành chữ, ngàn = nghìn) |
| `comet` | wmt22-comet-da cho bản dịch của app; `src` là câu gốc chuẩn |
| `oracle_mt` / `oracle_comet` | Marian **cùng checkpoint** chạy trên máy tính (greedy, tối đa 128 token, tiền tố `>>vie<<` cho en→vi giống app), dịch **câu gốc chuẩn**: tức bản dịch khi ASR hoàn hảo |
| `oracle_plain_comet` | Như trên nhưng câu gốc bị đổi về chữ thường, bỏ dấu câu, đúng dạng mà GIPFormer xuất ra |
| `host_mt_asr_comet` | Marian trên máy tính dịch **bản ASR của thiết bị**, để so CPU với HTP |
| `tts_wer` | Whisper large-v3-turbo nghe lại giọng đọc, so với văn bản app yêu cầu đọc |
| `kokoro_tts_wer` | Như trên, với giọng Kokoro VI của lượt 02/11 (cùng văn bản) |

### 2.2 Phân rã COMET theo nguyên nhân (trung bình trên các câu được dịch)

| Bước | VI→EN | EN→VI |
|---|---|---|
| Marian, câu gốc chuẩn (có dấu câu, chữ hoa) | 0,818 | 0,787 ¹ |
| Marian, câu gốc dạng chữ thường không dấu câu | 0,792 (thiếu dấu câu: −0,026) | 0,787 |
| Marian (máy tính), bản ASR thật | 0,784 (lỗi từ ASR: −0,008) | 0,759 |
| **Thiết bị (HTP)** | **0,789** | **0,761** |
| Máy tính + sửa đổi đã thử (mục 5) | 0,791 (thêm "?") | 0,787 (dịch từng câu) |
| **Thiết bị + dịch từng câu (đo lại 04/11, mục 5.2)** | 0,789 (không đổi) | **0,789** |

¹ Câu gốc tiếng Anh trong bộ mẫu vốn đã ở dạng chữ thường, không dấu câu, số viết bằng chữ, nên hai dòng đầu bằng nhau.

### 2.3 Quy tắc gán nguyên nhân chính

Áp dụng cho câu bị định tuyến sai hoặc có COMET < 0,70, xét theo thứ tự:

1. Định tuyến sai hoặc bị từ chối: **định tuyến**.
2. EN→VI: bản ASR có từ hai câu trở lên và bản dịch có ít câu hơn: **MT bỏ câu**.
3. ASR có lỗi từ và bản dịch từ câu gốc chuẩn cao hơn ít nhất 0,10 điểm: **ASR nhận sai từ**.
4. VI→EN: ASR đúng từng chữ nhưng bản dịch từ câu gốc chuẩn (có dấu câu) cao hơn ít nhất 0,10:
   **ASR thiếu dấu câu**.
5. Còn lại: **MT**.

Ngoài các câu này, có 19 câu VI→EN và 21 câu EN→VI có COMET 0,70–0,80: hiểu được nhưng chưa tự
nhiên. Chúng không được liệt kê riêng.

### 2.4 Kết quả phân loại

| Nguyên nhân chính | VI→EN (100 câu) | EN→VI (100 câu) |
|---|---|---|
| Định tuyến bị từ chối (audio > 10 s) | 0 | 4 |
| Định tuyến nhầm chiều | 0 | 5 |
| ASR nhận sai từ | 4 | 4 |
| ASR thiếu dấu câu / chữ hoa | 8 | — |
| MT bỏ câu | — | 19 |
| MT dịch sai (nghĩa, văn phong, số, tên riêng) | 13 | 6 |
| TTS | 0 | 0 |
| **Tổng câu sai rõ** | **25** | **38** |

---

## 3. Lỗi theo thành phần

### 3.1 Định tuyến ngôn ngữ (9 câu tiếng Anh)

| ID | Kết quả | Điểm VI (token) | Điểm EN (token) | Câu gốc (rút gọn) | GIPFormer nghe được |
|---|---|---|---|---|---|
| EV-2748 | → vi-en | −0,74 (2) | không có (0 token) | your password reset link has been sent… | "à" |
| EV-5935 | → vi-en | −0,0008 (2) | −0,11 (33) | uh uh my name is sarah smith… | "à" |
| EV-6502 | → vi-en | −0,03 | −0,15 | um i would give it longer than that… | "à" |
| EV-7818 | → vi-en | −0,02 | −0,12 | hi my name is miss james johnson… | "à" |
| EV-6960 | → vi-en | −0,56 (2) | −0,85 (13) | am i going to need to bring an umbrella… | "ai cờ" |
| EV-6557, EV-6602, EV-7979, EV-8062 | từ chối | – | – | audio 10,3 / 11,1 / 13,4 / 11,3 s | – |

**Nguyên nhân:**
- **Điểm trung bình mỗi token ưu tiên giả thuyết rất ngắn.**
  - Gặp tiếng Anh, GIPFormer chỉ phát ra âm đệm "à": 2 token, log-prob gần 0.
  - Parakeet phát ra 13–33 token nên điểm trung bình thấp hơn.
  - Chuẩn hoá theo độ dài khử được thiên lệch "chuỗi ngắn", nhưng không khử được trường hợp
    *một token rất tự tin*.
  - EV-5935 dài 8,5 s mà chỉ có 2 token, tức 0,23 token/giây. Lời nói thật có khoảng 3–4 token/giây.
- **EV-2748:** Parakeet không ra token nào, nên điểm EN bị coi là −∞ và VI thắng mặc định.
- **4 câu bị từ chối:** bộ mẫu có câu dài hơn 10 s. Đường feed trong harness đưa nguyên file vào và
  bị frontend từ chối (`NPU ASR accepts 1..160000 samples`). Khi thu trực tiếp, bộ cắt câu dừng ở
  10 s và câu sau sẽ được dịch tiếp, nên đây là lỗi của cách đo nhiều hơn là của app. Dù vậy, người
  nói dài vẫn bị cắt giữa ý.
- **Phụ thuộc vào prior:** có 8 câu EN chỉ đúng nhờ prior lượt nói. Nếu chỉ dựa vào điểm, kết quả là
  83/100 thay vì 91/100.

**Model chưa tốt:** không phải model riêng lẻ mà là **chính sách so điểm** (giữa GIPFormer và
Parakeet). Các ngưỡng mới được fit trên 21 fixture tổng hợp.

### 3.2 GIPFormer – ASR tiếng Việt

WER 1,3% (11/100 câu có lỗi). Có hai loại lỗi.

**a) Nhận sai từ: nhầm thanh điệu hoặc phụ âm đầu gần âm** (4 câu là nguyên nhân chính):

| ID | Câu gốc | GIPFormer | Bản dịch app | COMET (app / ASR hoàn hảo) |
|---|---|---|---|---|
| VE-4326 | Tôi thấy phần vai hơi **chật**. | …hơi **chập** | I feel a little light on my shoulder. | 0,54 / 0,81 |
| VE-6619 | Xe có sẵn ghế **trẻ em** không? | …ghế **cho em** không | Is the car available to you? | 0,59 / 0,75 |
| VE-7082 | …có bàn thay **tã** không? | …thay **tả** không | I'm with the baby. Have a dipping table. | 0,49 / 0,63 |
| VE-9737 | Một phần **đang** ra… | Một phần **đen** ra… | A black part of the rest takes five minutes. | 0,50 / 0,67 |
| VE-8762 | …người **giao** đang đứng nhầm… | …người **dao**… | …the **knife man** is standing… | 0,71 / 0,76 |
| VE-7845 | Dịch vụ **giặt** nhanh… | …**giật** nhanh… | Quick grab service before 8:00 p.m. | 0,71 / 0,54 |
| VE-0371 | **Hạn** cuối là thứ Sáu… | **hẹn** cuối… | Last date was Friday, right? | 0,88 / 0,91 |

Nguyên nhân:
- Các cặp chật/chập, tã/tả, giặt/giật và giao/dao (giọng Nam, gi = d) chỉ khác nhau một thanh
  hoặc một âm vị.
- Một âm sai thành một từ khác nghĩa hẳn, và Marian dịch đúng theo từ sai đó ("knife man"). Lỗi
  ASR nhỏ nhưng làm hỏng nặng bản dịch.
- RNN-T giải mã greedy, không có mô hình ngôn ngữ để chọn "bàn thay **tã**" thay vì "tả".

**b) Thiếu dấu câu và chữ hoa** (8 câu là nguyên nhân chính):
- GIPFormer xuất chữ thường, không dấu câu. Với câu hỏi, Marian mất tín hiệu "?" và thường dịch
  thành câu khẳng định:

| ID | GIPFormer | Bản dịch app | Khi thêm "?" và viết hoa |
|---|---|---|---|
| VE-6311 | xin lỗi tàu này có đi về trung tâm thành phố không | Sorry, this ship is going downtown. | Excuse me, does this train go downtown? (COMET 0,61 → 0,92) |
| VE-3393 | hóa đơn gửi trong bao lâu | billing for how long | How long was the bill in? (0,56 → 0,72) |
| VE-5433 | tôi nên xuống ở trạm nào để quay lại | I should be down at the station. | (luật bỏ sót: từ hỏi "nào" không ở cuối câu) |
| VE-9469 | khoảng ba mươi phút trước sau khi đi bộ nhanh | About thirty minutes before fast walking. | (thiếu dấu phẩy làm lệch nghĩa "trước, sau khi") |

- Trong 35 câu hỏi tiếng Việt, 10 bản dịch không có dấu "?".
- Nếu có dấu câu gốc, COMET nhóm câu hỏi tăng từ 0,807 lên 0,843.

**Model chưa tốt:** GIPFormer **nhận đúng từ** (tốt nhất trong pipeline) nhưng **xuất ra ở dạng
không phù hợp làm đầu vào cho MT**. Marian được huấn luyện trên văn bản có dấu câu.

### 3.3 Parakeet – ASR tiếng Anh

WER 11,5%. Khoảng 1/4 không phải lỗi thật:
- 26 từ đệm (um, uh) có trong câu chuẩn.
- Viết tắt: I'm / i am, don't / do not.
- GMT so với "greenwich mean time", AM so với "in the morning".

Sau khi quy đổi các trường hợp này, **WER thật khoảng 8,7%**. Các lỗi nghe sai thật:

| ID | Câu gốc | Parakeet | Hậu quả |
|---|---|---|---|
| EV-0182 | …but the **storage on my phone** not so wonderful | …the **store drama f not's** so wonderful | "vở kịch của cửa hàng" (COMET 0,59) |
| EV-1173 | …the **snowplows burying** my car… **tuesday** | …a **snow post carrying** my car… **toay** | "bưu điện tuyết" (0,56) |
| EV-5269 | not to any medication but **i am** allergic to peanuts | not any medication **v'm** allergic peanuts | TTS đọc "VM" (0,66) |
| EV-6709 | your **appointment** has been scheduled… | your **appployment**… | "Dự án của anh" (0,68) |
| EV-6340 | **your appointment**… | **A appointment**… thursday eight **AM?** | thêm dấu hỏi sai, câu sau bị bỏ |
| EV-0523 | i am sorry to hear that um **how old was he**… | **what old is he**… | mất nửa đầu (0,71) |
| EV-6218 | the blue **inhaler**… | the blue **paler**… | "Cái **áo** xanh" (0,52) |
| EV-2177 | sure **shame** fair day… **mongey** | **Same**. Fair day… **monkey** | "có khỉ trong bao lâu" (0,39) |

Nguyên nhân:
- Phần lớn là hội thoại điện thoại hoặc tổng đài (giọng nhanh, băng thông hẹp) và từ hiếm
  (snowplows, inhaler, mongey).
- Parakeet-TDT-CTC-110M là model nhỏ, giải mã CTC greedy, không có LM ngoài.
- Một phần lỗi chính tả kiểu "appployment", "sururprising" là đặc trưng của CTC: ký tự bị lặp khi
  ranh giới token mờ.

**Model chưa tốt:** Parakeet ở mức chấp nhận được. Nó **không** phải nguyên nhân chính ở phần lớn
câu EN→VI sai, chỉ ở 4 câu.

### 3.4 Marian vi→en

13 câu sai do MT (8 câu khác xuất phát từ việc thiếu dấu câu, mục 3.2). Có 20/100 câu vẫn sai rõ
(COMET < 0,70) ngay cả khi dịch từ câu gốc chuẩn.

| Loại lỗi | ID | Câu gốc | Bản dịch | Đúng ra |
|---|---|---|---|---|
| Sai nghĩa từ đa nghĩa | VE-6797 | Khoảng sáu **giờ** là vừa, chưa quá đông đâu. | It's not too many in six **hours**. | around six **o'clock**… not too crowded |
| | VE-7355 | Tối đa sáu mươi lăm **oát**. | Maximum sixty-five **o'clock** | sixty-five **watts** |
| | VE-6500 | Diễn giả đang ở khu **giao lưu**. | The speaker is in the **traffic**. | networking area |
| | VE-9048 | **Mẫu** này giảm hai mươi phần trăm. | This **pattern** dropped by 20 percent | this **model** is 20% off |
| | VE-7804 | Tôi vừa **chuyển** xong, anh kiểm tra nhé. | I just **moved** it. You check. | completed the **transfer** |
| | VE-1679 | Anh còn **phiếu bảo hành** không? | You have **warrants**? | warranty card |
| | VE-9740 | **Bên tôi** cần báo giá… | **My party** needs to report a price… | **We** need a quotation |
| Văn phong phụ đề, đại từ xưng hô | VE-3026 | **Anh** chờ tôi một chút. | **Hey, you** give me a second. | Please wait a moment. |
| | VE-2958 | **Anh** thử âm thanh một chút nhé. | **Hey, you know what? I'm gonna** try some sound. | Please test the audio |
| Lặp từ hoặc mất ý | VE-6855 | Tôi tìm thấy rồi, đúng chiếc này không? | I found it. I found it. | …is this the right one? |
| | VE-6066 | Phòng **đang dọn**, khoảng hai mươi phút nữa là xong. | Room's **in the clear** for about 20 minutes. | being cleaned |
| | VE-8787 | Không hủy, người hướng dẫn sẽ đổi tuyến **trong nhà**. | Don't cancel the instructor. …**inside the house**. | indoor route |
| Câu ngắn thiếu ngữ cảnh | VE-9890 | Tôi **nhập** rồi, cửa vẫn chưa mở. | I'm in. The door's not open. | I entered it (the code) |

Nguyên nhân:
- **Dữ liệu huấn luyện:** opus-mt-vi-en là bản Tatoeba-Challenge 2020, huấn luyện trên OPUS. Phần
  Việt–Anh của OPUS chủ yếu là phụ đề phim (OpenSubtitles). Model vì thế quen thoại phim: "Anh …"
  đầu câu thành "Hey, you", có "gonna", câu cụt. Nó lạ với tiếng Việt dịch vụ, du lịch và công sở
  (phiếu bảo hành, báo giá, khu giao lưu).
- **Model nhỏ, greedy, từng câu độc lập:** transformer-base 6 lớp (khoảng 75 triệu tham số), không
  beam search, không ngữ cảnh lượt trước. Câu 4–6 chữ như "Tôi nhập rồi" vốn mơ hồ.
- **Không có từ vựng chuyên ngành:** oát, giao lưu, bảo hành là những từ hiếm trong phụ đề phim.

### 3.5 Marian en→vi

25/29 câu EN→VI sai rõ là do MT. 21/100 câu vẫn sai rõ ngay cả khi dịch từ câu gốc chuẩn.

**a) Bỏ câu: lỗi lớn nhất của toàn pipeline** (19 câu là nguyên nhân chính).

Parakeet xuất văn bản có dấu chấm. Khi đầu vào có từ hai câu trở lên, Marian thường chỉ dịch câu
đầu, đôi khi chỉ câu cuối, rồi kết thúc:

| ID | Parakeet (rút gọn) | Bản dịch app | Dịch từng câu (đã thử) |
|---|---|---|---|
| EV-0894 | Okay, alright… moving on swiftly, sir. **Do you have any allergies at all** | Được rồi, được rồi, và cứ tiếp tục đi, thưa ngài. | …thưa ông. **Anh có bị dị ứng gì không?** (0,66 → 0,76) |
| EV-7675 | Why not? Oh well, I'm ready to eat. No need to call the doctor. If we run… | Tôi đã sẵn sàng ăn rồi. | Tại sao không? À, tôi sẵn sàng ăn rồi. Không cần phải gọi bác sĩ… (0,55 → 0,80) |
| EV-6534 | No, he is. Some bosses will bend the rules… but not my boss. He always follows… | Không, anh ta là sếp. nhưng không phải sếp tôi. | …Đôi khi vài ông chủ sẽ nhượng bộ luật lệ một chút… (0,53 → 0,84) |
| EV-3057 | Did someone else want to throw it together? Actually, I can… | Có ai muốn ném nó cùng nhau không? | (0,49 → 0,63) |
| EV-9209 | In the middle, okay. And any other symptoms…? Have you had any nausea…? | Anh có thấy buồn nôn và nôn không? | chỉ còn câu cuối |
| EV-1829 | No that's all I needed help with. Thank you very much… | Không, đó là tất cả những gì tôi cần. | |

Số liệu:
- 44/91 bản ASR có từ hai câu trở lên; **40/44 bản dịch có ít câu hơn đầu vào**.
- COMET nhóm nhiều câu là **0,713**, nhóm một câu là 0,807.
- Dịch từng câu rồi ghép: nhóm nhiều câu lên **0,767**; nhóm một câu giữ nguyên 0,806.

Nguyên nhân:
- opus-mt-en-vi được huấn luyện và chấm trên các cặp **một câu** (Tatoeba, BLEU 37,2 trên tập câu
  ngắn). Sau dấu chấm đầu tiên, model coi như xong và phát `</s>`.
- Giải mã greedy không có length penalty nên dừng sớm luôn được chấp nhận.
- Đây là lỗi tương tác giữa model và cách dùng: dịch từng lượt nói nhưng model chỉ biết dịch từng câu.

**b) Dịch sai: nghĩa, số, tên riêng, lời nói tự nhiên** (6 câu, cộng với nhiều câu ở nhóm 0,70–0,80):

| Loại | ID | Đầu vào | Bản dịch app | Ghi chú |
|---|---|---|---|---|
| Số và tên riêng | EV-0358 | seven hundred thirty four Main Street Harper Valley, Oregon six seven two nine seven | 730 phố Main Valley, Oregon 6 7 7 9tine 7 | mất "Harper", 734 thành 730, chữ số bị bịa |
| Tên riêng, từ mượn | EV-5674 | …what is going on with **sprint**… | …với **Spraach**… | tên dự án bị phiên âm bừa |
| | EV-2390 | put **walk the dog** on my list to do | đưa **Walk the Dg** vào danh sách | giữ nguyên tiếng Anh, sai chính tả |
| Sai nghĩa | EV-5187 | …pay for the whole **bill** | …trả tiền cả **dự luật** | bill = hoá đơn, không phải dự luật |
| | EV-6218 | the blue inhaler (ASR: paler) | Cái **áo** xanh | lỗi ASR cộng đoán bừa |
| Dịch từng chữ lời nói tự nhiên | EV-7227 | any pain or strong smell or having to **go** more often… | Bất kỳ đau đớn, mùi hương mạnh mẽ hoặc phải **đi** nhiều hơn | thiếu "đi vệ sinh", câu không thành câu hỏi |
| | EV-8727 | …my jawline's just all feeling a bit numb… | …như hàm của tôi **dòng** chỉ nhận được một chút tê cóng | dịch từng chữ (jaw**line** thành "dòng") |
| | EV-7335 | …I'm not going to **spoil** it for | …Tôi sẽ không **làm hỏng** nó cho | spoil = tiết lộ nội dung |

Nguyên nhân:
- **Lệch miền:** bộ mẫu có nhiều hội thoại khám bệnh, tổng đài và trợ lý ảo, có từ đệm và câu
  nói lặp. Tatoeba là câu văn viết ngắn, sạch.
- **Số và tên riêng:** câu gốc đọc số bằng chữ ("seven hundred thirty four"). Marian vừa phải đổi
  ra chữ số vừa phải dịch, nên dễ bịa. Tên riêng như Harper Valley hay sprint không có trong từ điển
  SentencePiece 32k nên bị cắt mảnh rồi phiên âm sai.

### 3.6 TTS

| | VieNeu Nano (VI, mặc định) | Kokoro VI (lượt 02/11) | Kokoro EN |
|---|---|---|---|
| Câu Whisper nghe lại có lệch (WER > 0) | 15 / 91 | 51 / 91 | 4 / 100 |
| Lệch ≥ 10% | 6 | 21 | 4 |
| Lỗi đọc thật (đã xem từng câu) | khoảng 5 | nhiều, có hệ thống (th thành t, sai thanh) | 0 |

**VieNeu**, các câu lệch lớn nhất:

| ID | Văn bản cần đọc | Whisper nghe được | Nguyên nhân |
|---|---|---|---|
| EV-9341 | Bao nhiêu là **Babaom** | …là **33 ohm** | chữ vô nghĩa do ASR/MT ("bada boom") |
| EV-0358 | …6 7 7 **9tine** 7 | …6779-7 | chữ rác do MT |
| EV-5269 | …**v'm** dị ứng đậu phộng | …**VM**… | chữ rác do ASR |
| EV-5674 | …với **Spraach**… | …SpraX… | từ ngoại lai |
| EV-5761 | …một **i watch**… | …iWatch… | chỉ khác cách viết, đọc đúng |
| EV-0333 | …tai tôi đang **rung**… | …đang **zoom**… | lỗi đọc thật: "rung" nghe như "zoom" |
| EV-1173 / EV-3459 | **bưu** điện | **bill** điện / **biểu** điện | lỗi đọc thật: vần "ưu" |
| EV-6047 | …phát triển **vết** sưng | …phát triển **với** sưng | lỗi đọc thật |
| EV-0228 | Tôi **có** nhớ… | Tôi nhớ… | bỏ một từ ngắn |
| EV-1508, EV-6709, EV-6929 | 12 giờ, 8 giờ 30, 2 ngàn 3 trăm… | 12h, 8h30, 2.330 | không phải lỗi: Whisper viết số kiểu khác |

Nguyên nhân:
- Phần lớn "lỗi TTS" là **garbage in, garbage out**: TTS đọc đúng chữ rác mà ASR hoặc MT sinh ra.
- Lỗi thật của VieNeu Nano tập trung ở **từ tiếng Anh hoặc từ ngoại lai** trong câu Việt. sea-g2p
  không có phiên âm cho chúng. Tác giả VieNeu cũng nêu đây là điểm yếu của bản Nano.
- Một vài vần (ưu, ung) bị đọc lệch.

**Kokoro EN:** 4 câu bị đánh lỗi đều do cách chấm ("earset" / "ear set", "Copys" / "Copies",
"Room's" / "Rooms", "percent" / "%"). Không có lỗi đọc thật.

---

## 4. Model nào chưa tốt: xếp hạng

| Hạng | Model | Vì sao | Ảnh hưởng ước tính |
|---|---|---|---|
| 1 | **Marian en→vi** | Bỏ câu khi đầu vào có nhiều câu, lệch miền hội thoại, méo số và tên riêng | 25/38 câu EN sai rõ. Riêng việc bỏ câu làm mất khoảng 0,026 COMET toàn chiều |
| 2 | **Marian vi→en** | Phụ đề phim, nhầm nghĩa từ, câu ngắn mơ hồ | 13 câu sai trực tiếp. Trần chất lượng khi ASR hoàn hảo chỉ 0,818 |
| 3 | **Chính sách dual-ASR** | Ưu tiên giả thuyết 1 token; câu > 10 s | 9% câu EN không được dịch hoặc bị dịch sai chiều |
| 4 | **GIPFormer** (về dạng đầu ra) | Không có dấu câu và chữ hoa | −0,026 COMET VI→EN, 8 câu sai |
| 5 | **Parakeet** | WER thật khoảng 8,7%, yếu với giọng tổng đài và từ hiếm | 4 câu sai do ASR, góp phần vào nhiều câu khác |
| 6 | **GIPFormer** (về nhận dạng) | Nhầm thanh điệu, không có LM | 4 câu, nhưng mỗi lỗi làm hỏng nặng bản dịch |
| 7 | **VieNeu Nano** | Từ ngoại lai, vài vần | khoảng 5 câu đọc lệch nhẹ, không làm hỏng nghĩa |
| 8 | **Kokoro EN** | — | không có lỗi thật |

---

## 5. Đề xuất sửa (theo thứ tự hiệu quả trên chi phí)

### 5.1 Đã đo được hiệu quả

1. **Tách câu trước Marian en→vi, dịch từng câu, ghép lại.** Đã cài vào app và đo trên thiết bị,
   xem mục 5.2.
   - Trên máy tính: COMET EN→VI tăng từ 0,761 lên 0,787 (nhóm nhiều câu từ 0,713 lên 0,767).
   - Nhóm một câu không đổi.
2. **Khôi phục dấu "?" và chữ hoa cho đầu ra GIPFormer** trước Marian vi→en. Mới đo trên máy tính,
   chưa cài vào app.
   - Luật đơn giản (từ hỏi cuối câu, bao lâu / ở đâu…) nâng nhóm câu hỏi từ 0,807 lên **0,829**.
   - Toàn chiều chỉ tăng từ 0,789 lên 0,791, vì luật bắt sót 6/35 câu hỏi và đặt "?" nhầm cho 7 câu.
   - Cần một bộ phục hồi dấu câu tốt hơn (model nhỏ hoặc luật chi tiết hơn) mới lấy được trọn
     +0,026 ở bảng 2.2.

### 5.2 Đo lại trên thiết bị: dịch từng câu (04/11)

**Cài đặt:**
- `core/SentenceSplitter.kt` tách bản ASR tại `. ? !` có khoảng trắng phía sau, nên "3.5" và
  "example.com" không bị cắt.
- `NpuPipeline.translate`, tức Marian trên HTP, gọi encoder và decoder **một lần cho mỗi câu**
  rồi nối kết quả bằng dấu cách. Số token MT được cộng dồn qua các câu.
- Đường khôi phục CPU (`CpuFallbackBranch`) dùng cùng cách tách.
- GIPFormer không xuất dấu câu, nên bản tiếng Việt luôn là một đoạn: VI→EN không bị ảnh hưởng.
- Có unit test `SentenceSplitterTest` (5/5 qua).

**Lượt chạy:** `eval-results/2026-11-04_dataset200_sentsplit/`, 200/200 câu, chế độ máy bay, cùng
APK và cấu hình VieNeu, chỉ khác bước MT.
- Quyết định định tuyến và bản ASR trùng khớp 100% với lượt 03/11, nên đây là phép so sánh A/B
  thuần túy cho bước MT.
- Bản dịch HTP giống hệt mô phỏng trên máy tính ở 82/91 câu.

| Chỉ số (91 câu EN→VI được dịch) | 03/11 (dịch cả đoạn) | 04/11 (dịch từng câu) |
|---|---|---|
| **COMET** | 0,761 | **0,789** |
| COMET nhóm nhiều câu (44) | 0,713 | **0,770** |
| COMET nhóm một câu (47) | 0,807 | 0,807 |
| chrF++ / BLEU | 46,9 / 28,0 | **52,8 / 32,6** |
| Số câu COMET < 0,70 | 29 | **20** |
| Từng câu: tốt lên / kém đi / giữ nguyên (chênh > 0,02) | — | 25 / 9 / 57 |
| WER TTS (Whisper nghe lại VieNeu) | 1,8% | 2,1% |
| MT p50 / p90 | 342 / 591 ms | 443 / 727 ms |
| Độ dài giọng đọc p50 | 3,68 s | 4,06 s |
| Thời gian tới âm thanh đầu tiên p50 / p90 | 3,06 / 5,20 s | 3,56 / 5,73 s |

VI→EN không đổi (COMET 0,789, chrF++ 47,3, bản dịch giống hệt).

**Độ trễ tăng vì sao:**
- **MT tăng khoảng 100 ms ở p50** vì mỗi câu thêm một lần chạy encoder, và câu không còn bị bỏ
  nên có thêm token decoder.
- **TTS mất thêm khoảng 370 ms** vì có nhiều chữ hơn để đọc: trước đây câu bị bỏ thì không phải đọc.
- **Một phần do máy nóng hơn:** lượt này không sạc, CPU bị hạ xung xuống 1,17 GHz (lượt 03/11 là
  1,29–1,40 GHz) và trạng thái nhiệt lên 1. Bằng chứng: VI→EN đọc đúng văn bản như cũ mà TTS vẫn
  chậm hơn 9% (2,48 → 2,71 s) và âm thanh đầu tiên chậm hơn 213 ms.
- Phần tăng thật do thay đổi này vì vậy khoảng 300 ms ở p50 cho EN→VI, đổi lấy việc không còn mất
  nửa câu nói.

**Câu kém đi (9):** đều do mất ngữ cảnh giữa các câu.
- EV-4810: "What are the local branch hours" đứng riêng thì bị dịch thành "Giờ địa phương là bao
  nhiêu?".
- EV-7713: nửa sau "nine hundred wion" (ASR nghe sai) đứng riêng thì bị dịch lệch hẳn.
- Các câu còn lại chỉ đổi dấu phẩy thành dấu chấm, COMET giảm 0,02–0,07.

**Câu tốt lên rõ nhất:** những câu trước đây mất trọn một vế.
- EV-6534: 0,53 → 0,88.
- EV-6340: 0,63 → 0,90.
- EV-5957: 0,62 → 0,88.

**Cách làm tốt hơn nữa:**
- Gộp các mẩu rất ngắn (dưới khoảng 4 từ) vào câu kề bên trước khi dịch, để giữ ngữ cảnh.
- Dịch câu 2..n song song với lúc TTS đọc câu 1, để giấu phần độ trễ tăng thêm.

### 5.3 Chưa đo, nên làm

3. **Định tuyến:**
   - Thêm cổng "token mỗi giây". Ví dụ: loại nhánh có dưới khoảng 1 token/giây audio, vì
     EV-5935 chỉ có 0,23.
   - Chặn trường hợp cả bản dịch chỉ là một âm đệm như "à" hay "ừ".
   - Coi nhánh có 0 token là "không rõ", thay vì để nhánh kia thắng mặc định.
   - Đã liệt kê trong mục 7 của báo cáo 02/11; số liệu ở đây củng cố đề xuất đó.
4. **Thay hoặc fine-tune Marian:**
   - Hai model hiện tại là bản 2020, có trần chất lượng thấp (0,818 và 0,787 ngay cả khi ASR hoàn hảo).
   - Hướng 1: fine-tune trên hội thoại dịch vụ, y tế, du lịch Việt–Anh.
   - Hướng 2: thử model mới hơn cùng cỡ chạy được trên HTP.
   - Hướng 3: thêm beam search (beam 2–4), nhưng mỗi bước decoder chạy lâu hơn.
5. **Bảng thuật ngữ / tên riêng:** giữ nguyên tên riêng và từ khoá (Harper Valley, sprint, GMT)
   qua MT, và đọc số bằng chữ số trước khi dịch.
6. **TTS:** thêm từ điển phiên âm cho từ tiếng Anh hay gặp trước sea-g2p. Kiểm tra vần "ưu" và "ung"
   với VieNeu.

---

## 6. Phụ lục

### 6.1 Danh sách ID theo nhóm

| Nhóm | ID |
|---|---|
| Định tuyến từ chối | EV-6557, EV-6602, EV-7979, EV-8062 |
| Định tuyến nhầm | EV-2748, EV-5935, EV-6502, EV-6960, EV-7818 |
| ASR VI nhận sai từ | VE-4326, VE-6619, VE-7082, VE-9737 |
| ASR VI thiếu dấu câu | VE-3026, VE-3393, VE-5433, VE-6311, VE-6797, VE-8449, VE-9469, VE-9740 |
| MT vi→en | VE-1679, VE-2437, VE-2958, VE-6066, VE-6500, VE-6855, VE-7355, VE-7452, VE-7804, VE-8606, VE-8787, VE-9048, VE-9890 |
| ASR EN nhận sai từ | EV-0182, EV-1173, EV-5269, EV-6709 |
| MT en→vi bỏ câu | EV-0250, EV-0491, EV-0894, EV-1212, EV-1229, EV-1829, EV-2177, EV-2364, EV-2476, EV-3057, EV-5187, EV-5957, EV-6218, EV-6340, EV-6534, EV-7675, EV-7757, EV-9209, EV-9341 |
| MT en→vi dịch sai | EV-0358, EV-2390, EV-5674, EV-7227, EV-7335, EV-8727 |

Một số câu có nhiều lỗi chồng nhau; bảng chỉ ghi nguyên nhân chính. Ví dụ: EV-6218 vừa bị Parakeet
nghe nhầm "inhaler" thành "paler" vừa bị bỏ câu; EV-2177 vừa nghe nhầm vừa bỏ câu.

### 6.2 Dữ liệu và script

Thư mục `eval-results/2026-11-04_error_analysis/` (không đưa vào git, giống các kết quả đo khác):

| File | Nội dung |
|---|---|
| `per_item_analysis.csv` | 200 câu với mọi cột ở mục 2.1 |
| `classes.json` | Nguyên nhân chính của từng câu sai |
| `split_test.csv` | Thử nghiệm dịch từng câu (EN→VI) |
| `punct_test.csv` | Thử nghiệm thêm "?" và chữ hoa (VI→EN) |
| `error_analysis*.py`, `split_test.py`, `punct_test.py` | Script tạo các file trên (chạy trên máy tính, Python 3.12, COMET wmt22-comet-da) |
| `compare_sentsplit.py` | So sánh lượt thiết bị dịch từng câu với lượt 03/11; ghi `comet_compare.json` và `per_item_comet.csv` vào `eval-results/2026-11-04_dataset200_sentsplit/` |

### 6.3 Giới hạn của phân tích

- **COMET câu đơn có nhiễu:** ngưỡng 0,70 chỉ để sàng lọc. Mọi câu trong bảng đều đã được đọc lại
  bằng mắt.
- **"ASR hoàn hảo" là ước lượng:** dùng Marian trên máy tính; nó khớp HTP ở phần lớn câu nhưng
  không phải 100%.
- **Câu gốc tiếng Anh không có dấu câu:** không đo được trực tiếp "ASR đúng từ nhưng có dấu câu"
  cho EN→VI. Thí nghiệm dịch từng câu thay cho phép đo này.
- **TTS chỉ được đánh giá qua Whisper**, tức độ rõ chữ; độ tự nhiên (MOS) chưa được đo.
