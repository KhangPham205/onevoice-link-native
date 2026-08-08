# OneVoice Challenge — Outline Technical Spec (nộp T7/2026)

> BTC yêu cầu: đặc tả kỹ thuật + kiến trúc hệ thống + thiết kế kỹ thuật.
> Dàn bài bám rubric: Technical 50% / Innovation 25% / Business 25%.

> **Quyết định đã chốt:**
> - **Thiết bị: app trên điện thoại Android flagship, chip Snapdragon 8 Elite (Gen 4 trở lên).** Đúng "any portable device", BOM thấp (ăn điểm Business). Dev trên phone đang có (8 Gen 2/3 ok) → benchmark final qua Qualcomm AI Hub trên đúng chip mục tiêu. Không dùng dev kit / Snapdragon X Elite (hướng laptop, không hợp thiết bị cầm tay của công nhân).
> - **Ngách: nhà máy FDI Hàn Quốc / Đài Loan tại VN** (Samsung, LG, Foxconn — KCN Bắc Ninh/Bắc Giang/Bình Dương). Khớp đúng 2 cặp khó-nhất-giá-trị-cao-nhất VI↔KO, VI↔ZH; VI↔EN làm demo "ăn chắc".

## 1. Executive Summary (1 trang)
- Bài toán 1 câu + giải pháp 1 câu.
- **Thiết bị mục tiêu: điện thoại Android Snapdragon 8 Elite (Hexagon NPU), chạy 100% on-device.** 3 cặp ngôn ngữ VI↔EN / VI↔ZH / VI↔KO.
- 3 con số "hứa": latency mục tiêu (vd UPL <1.5s), WER/COMET mục tiêu, 100% on-device (không cloud).

## 2. Problem & Target Users *(điểm Business)*
- **Ngách: nhà máy FDI Hàn/Đài tại VN.** Persona: tổ trưởng/kỹ sư người Hàn·Đài ↔ công nhân Việt trên dây chuyền, đeo găng, môi trường ồn.
- Câu chuyện: hiểu nhầm trên dây chuyền → mất an toàn + năng suất. FDI Hàn/Đài là khối FDI lớn nhất VN → thị trường thật, sizing có sẵn.
- Số liệu thị trường: 5% lao động VN giỏi tiếng Anh, 87% FDI yêu cầu ngoại ngữ.
- Demo: VI↔EN mở màn (ổn định nhất) → khoe VI↔KO/ZH làm điểm nhấn khác biệt.

## 3. System Architecture *(lõi điểm Technical)*
- Sơ đồ pipeline cascaded streaming: Mic→VAD→ASR→MT→TTS→Loa, có nhánh PARTIAL (màn hình) / FINAL (giọng).
- Lý do chọn cascaded (vs E2E): khớp Qualcomm AI Hub, thay được từng khối cho low-resource.
- Data flow + buffer/context management (cắt câu theo dấu câu).

## 4. Model Selection
- Chèn bảng stack theo 6 hướng (xem `onevoice_model_comparison.md` §4) + biện minh từng lựa chọn.
- Nguồn model: cái nào từ Qualcomm AI Hub, cái nào tự fine-tune/quantize.

## 5. On-Device Optimization *(khoe cơ bắp kỹ thuật)*
- Lượng tử hóa w8a16, MHA→SHA, linear→conv (pattern Whisper-small-Q của Qualcomm).
- Toolchain: `qai-hub-models` → ONNX Runtime / TFLite / QNN (AI Engine Direct) → Hexagon NPU.
- Budget bộ nhớ/nhiệt khi chạy đồng thời 3 khối.

## 6. Latency & Streaming Strategy *(đối trọng "low latency" trong rubric)*
- Streaming policy AlignAtt / AliBaStr-MT (read/write từ attention alignment).
- Force-finalization: timeout ~1.5s + giới hạn token.
- PARTIAL/FINAL dual-output → phản hồi tức thì.
- Metric: Average Lag (AL) và User-Perceived Latency (UPL).

## 7. Evaluation Plan *(chứng minh "đo được")*
- Metric: WER/CER (ASR), BLEU/COMET (MT), AL/UPL (latency), MOS (TTS).
- Test set: giọng vùng miền + code-switching + nền ồn công nghiệp.
- Đo trên đúng thiết bị Snapdragon, không phải trên máy chủ.

## 8. UX & Innovation *(điểm Innovation 25%)*
- Kích hoạt rảnh tay (wake-word + nút lớn), chống ồn (denoise/voice-isolation trước ASR).
- Hiển thị bản dịch song song cho cả 2 người (giảm hiểu nhầm an toàn).
- Glossary theo ngành nhồi vào LLM.

## 9. Risks & Mitigations
- VI↔KO/ZH MT hiếm → direct vs pivot (kế hoạch đo).
- TTS tiếng Việt (Kokoro thiếu) → Piper/Moonshine.
- Code-switching, ồn, ngân sách NPU.

## 10. Roadmap & Demo Plan
- Map mốc BTC: spec(T7) → prototype(T8–9) → field test(T10) → demo HCM(T11).
- Kịch bản demo cụ thể cho ngách đã chọn.

## Appendix
- Bảng tham số quantization, kết quả benchmark sơ bộ (nếu có), trích dẫn nguồn model.
