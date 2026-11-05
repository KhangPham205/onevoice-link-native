# Tóm tắt Phân tích Lỗi Dịch / Nghe / Đọc – Bộ mẫu 200 câu (OneVoice Link)

> **Tài liệu gốc:** [P3_phan-tich-loi_2026-11-04.md](P3_phan-tich-loi_2026-11-04.md)  
> **Ngày phân tích:** 04/11/2026  
> **Dữ liệu thực nghiệm:** `eval-results/2026-11-03_dataset200_vieneu/` chạy trên **Snapdragon SM8550 (HTP NPU)**  
> **Cấu hình pipeline:**
> - Dual-ASR: GIPFormer (VI) & Parakeet-TDT-CTC-110M (EN)
> - Machine Translation (MT): Marian (`opus-mt-en-vi`, `opus-mt-vi-en`, Tatoeba 2020) chạy trên HTP
> - Text-to-Speech (TTS): Kokoro EN & VieNeu-TTS v3 Nano VI

---

## 1. Ba Kết luận Cốt lõi

1. **Nút thắt chất lượng nằm ở MT, không phải ASR:**
   - Kể cả khi đầu vào là câu gốc chuẩn ("ASR hoàn hảo"), Marian vẫn dịch sai rõ ở **20/100 câu VI→EN** và **21/100 câu EN→VI**.
   - Nếu ASR đạt 100% độ chính xác, điểm COMET toàn pipeline chỉ tăng nhẹ: từ 0,789 lên 0,818 (VI→EN) và từ 0,761 lên 0,787 (EN→VI).
2. **Lỗi nghiêm trọng nhất của EN→VI đã có giải pháp rẻ và hiệu quả cao:**
   - Marian en→vi bỏ câu thứ hai trở đi ở **40/44** câu nhiều câu (do train trên câu đơn Tatoeba và decode greedy ngắt ở dấu chấm đầu tiên).
   - Giải pháp: **Tách bản ASR thành từng câu rồi dịch từng câu** giúp COMET EN→VI tăng trực tiếp từ **0,761 lên 0,787** (+0,026, tương đương mức "ASR hoàn hảo"); riêng nhóm nhiều câu tăng từ 0,713 lên 0,767.
3. **NPU HTP giữ nguyên chất lượng so với CPU:**
   - Cùng đầu vào, bản chạy trên HTP cho văn bản giống hệt CPU máy tính ở 95/100 câu VI→EN và 83/91 câu EN→VI (chênh lệch COMET không quá 0,005).

---

## 2. Bảng Xếp hạng & Tác động của Từng Thành phần

| Hạng | Thành phần | Mức độ | Số câu lỗi chính* | Tác động chính & Biểu hiện |
|:---:|---|:---:|:---:|---|
| **1** | **Marian en→vi** | **Nặng nhất** | 25 / 91 câu | Bỏ hẳn câu thứ 2 trở đi (40/44 câu nhiều câu); dịch word-by-word; méo số viết chữ ("734" $\rightarrow$ "730") và biến dạng tên riêng. Làm mất ~0,026 COMET toàn chiều. |
| **2** | **Marian vi→en** | **Nặng** | 13 / 100 câu | Dữ liệu Tatoeba nhiễm văn phong phụ đề phim (*"Hey, you..."*); chọn sai nghĩa từ đa nghĩa (*"giờ"* $\rightarrow$ *hours*, *"giao lưu"* $\rightarrow$ *traffic*); thiếu từ vựng dịch vụ/công sở. |
| **3** | **Định tuyến Dual-ASR** | **Trung bình** | 9 / 100 câu | 5 câu EN bị nhận nhầm sang VI vì GIPFormer nghe ra âm đệm "à" (1–2 token log-prob gần 0 kéo điểm trung bình token lên cao); 4 câu $>10\text{ s}$ bị frontend reject. |
| **4** | **GIPFormer (ASR VI)** | **Nhẹ về từ, lỗi định dạng** | 4 (từ) + 8 (dấu câu) | WER từ chỉ 1,3%, nhưng **xuất chữ thường không dấu câu** $\rightarrow$ mất dấu `?` khiến Marian dịch câu hỏi thành câu khẳng định (-0,026 COMET VI→EN). Lỗi từ chủ yếu nhầm thanh điệu/âm gần (*chật/chập*, *tã/tả*). |
| **5** | **Parakeet (ASR EN)** | **Trung bình** | 4 câu | WER thật khoảng **8,7%** (sau chuẩn hóa từ đệm/viết tắt). Nghe sai danh từ/từ hiếm ở giọng nhanh hoặc thoại tổng đài. |
| **6** | **VieNeu Nano (TTS VI)** | **Nhẹ** | 0 câu lỗi chính (~5 câu đọc lệch nhẹ) | Đa số là "rác vào, rác ra" (đọc đúng chữ rác từ MT/ASR). Lỗi thật nằm ở từ tiếng Anh xen kẽ (thiếu G2P ngoại lai) và lệch nhẹ vần *ưu/ung*. |
| **7** | **Kokoro EN (TTS EN)** | **Không đáng kể** | 0 câu | Hoạt động hoàn hảo, không có lỗi đọc thực tế. |

*\* Câu có COMET < 0,70 hoặc bị định tuyến sai.*

---

## 3. Phân rã Điểm COMET Theo Nguyên nhân

| Bước kiểm thử | VI→EN | EN→VI |
|---|:---:|:---:|
| Marian, câu gốc chuẩn (đầy đủ hoa, dấu câu) | 0,818 | 0,787 |
| Marian, câu gốc dạng thường không dấu câu | 0,792 *(thiếu dấu: -0,026)* | 0,787 |
| Marian (CPU máy tính), bản ASR thực tế | 0,784 *(lỗi từ ASR: -0,008)* | 0,759 |
| **Thiết bị thực tế (SM8550 HTP)** | **0,789** | **0,761** |
| **Thiết bị + Tối ưu hóa thực nghiệm** | **0,791** *(thêm "?")* | **0,787** *(dịch từng câu)* |

---

## 4. Các Ví dụ Lỗi Điển hình

* **Marian en→vi bỏ câu (EV-0894):**
  * *Đầu vào:* `"Okay, alright… moving on swiftly, sir. Do you have any allergies at all"`
  * *Bản dịch app:* `"Được rồi, được rồi, và cứ tiếp tục đi, thưa ngài."` *(bỏ hoàn toàn câu hỏi dị ứng phía sau)*.
* **Marian vi→en chọn sai nghĩa (VE-7355, VE-6500):**
  * `"sáu mươi lăm oát"` $\rightarrow$ `"sixty-five o'clock"` *(đúng ra: watts)*.
  * `"khu giao lưu"` $\rightarrow$ `"in the traffic"` *(đúng ra: networking area)*.
* **ASR VI mất dấu câu (VE-6311):**
  * *GIPFormer:* `"xin lỗi tàu này có đi về trung tâm thành phố không"`
  * *Marian vi→en dịch:* `"Sorry, this ship is going downtown."` *(câu hỏi thành câu khẳng định, COMET 0,61; khi có dấu `?` COMET đạt 0,92)*.
* **Định tuyến nhầm do âm đệm (EV-5935):**
  * Câu EN 8,5 s Parakeet nghe được 33 token, nhưng GIPFormer nghe ra `"à"` (2 token, log-prob gần 0) $\rightarrow$ điểm trung bình token của nhánh VI cao hơn $\rightarrow$ chuyển sai sang nhánh dịch `vi→en`.

---

## 5. Đề xuất Khắc phục & Lộ trình

### 5.1 Giải pháp ngắn hạn (Đã đo kiểm chứng – Chi phí thấp, hiệu quả cao)
1. **Tách câu trước Marian en→vi:** Chia chuỗi đầu vào theo dấu câu, dịch từng câu rồi ghép lại. Khắc phục triệt để lỗi bỏ câu, nâng COMET EN→VI từ 0,761 lên **0,787**.
2. **Khôi phục dấu hỏi (`?`) và viết hoa cho GIPFormer:** Dùng bộ quy tắc từ hỏi đơn giản (hoặc mô hình phục hồi dấu câu nhẹ) trước khi đưa vào Marian vi→en. Giúp nhóm câu hỏi tăng điểm từ 0,807 lên **0,829**.

### 5.2 Giải pháp trung và dài hạn
1. **Cải tiến logic định tuyến Dual-ASR:**
   - Thêm bộ lọc tốc độ token: loại nhánh có mật độ $< 1\text{ token/giây}$ audio (loại bỏ âm đệm "à", "ừ").
   - Nhánh ra 0 token phải coi là "không rõ", không để nhánh còn lại tự động thắng.
2. **Nâng cấp mô hình MT:**
   - Fine-tune Marian trên dữ liệu hội thoại thực tế (y tế, dịch vụ, du lịch).
   - Thử nghiệm các mô hình MT nhỏ thế hệ mới hơn trên HTP; cân nhắc giải mã Beam Search (beam 2–4).
3. **Bảo toàn thực thể & Chuẩn hóa:** Giữ nguyên tên riêng/từ mượn qua MT; tiền xử lý chuyển số dạng chữ về dạng chữ số.
4. **Từ điển phiên âm cho TTS:** Bổ sung từ điển phát âm cho các từ mượn tiếng Anh thường gặp trước khi đưa qua `sea-g2p`.

