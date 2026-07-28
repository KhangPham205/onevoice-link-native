# OneVoice Challenge — Bảng so sánh model theo cặp ngôn ngữ

> Nguồn nghiên cứu: tổng hợp SOTA edge speech-to-speech translation (06/2026). Xem mục Nguồn cuối file.

3 cặp × 2 chiều = **6 hướng dịch**. Mỗi hướng = ASR(ngôn ngữ nguồn) + MT + TTS(ngôn ngữ đích).

## 1. ASR — chọn theo ngôn ngữ NÓI (input)

| Ngôn ngữ input | Model SOTA edge | Ghi chú | Snapdragon-fit |
|---|---|---|---|
| **Tiếng Việt** | **PhoWhisper** (VinAI) | SOTA WER tiếng Việt, 844h/26K giọng. Tự quantize w8a16. | ✅ (tự compile) |
| | Whisper-small-Q (Qualcomm) | Đã pre-optimized sẵn — fallback nhanh | ✅✅ |
| **Tiếng Anh** | **Whisper-small-Q (Qualcomm)** | Có sẵn AI Hub, mạnh nhất, dễ nhất | ✅✅ |
| | Moonshine / Parakeet-TDT | Footprint nhỏ nhất, streaming, ultra-low-latency | ✅✅ |
| **Tiếng Trung** | **Paraformer-zh** (FunASR, 220M) | Non-autoregressive → nhanh hơn Whisper; có `paraformer-zh-streaming` (chunk 600ms), chạy GGUF/CPU edge | ✅✅ |
| | SenseVoice-Small | 15x nhanh hơn Whisper, đa ngữ — *lưu ý: bị đánh dấu sắp ngừng* | ✅ |
| **Tiếng Hàn** | **EZWhisper-KR** (ENERZAi) | Bản 484MB giảm nửa CER vs Whisper-large 3GB; có bản low-bit 13MB | ✅✅ (thiết kế cho edge) |
| | Whisper-large-v3 fine-tuned KR | Chính xác nhưng nặng | ⚠️ |

→ FunASR hỗ trợ runtime GGUF/llama.cpp, chạy như 1 binary trên CPU/edge không cần Python — rất hợp deploy thiết bị.

## 2. MT — chọn theo chất lượng + tính khả thi streaming

| Model | VI↔EN | VI↔ZH | VI↔KO | Đặc điểm |
|---|---|---|---|---|
| **Qwen3-4B (Q)** | ✅ Tốt | ✅ Mạnh ZH | ✅ Mạnh KR | LLM nhỏ mạnh nhất cho ZH/KR + ĐNÁ; nhồi glossary qua prompt; 4B-quantized chạy Hexagon mới |
| **Gemma 3 (1–4B)** | ✅ | ⚠️ Khá | ⚠️ Khá | Hiệu quả/đa dạng kích cỡ (270M–4B), tốt EN-centric |
| **NLLB-200** | ✅✅ Chuyên dịch | ✅ Phủ đủ | ✅ Phủ đủ | NMT chuyên dụng, nhẹ, trễ thấp — kém linh hoạt ngữ cảnh, yếu domain y tế VI-EN |
| **SeamlessM4T-v2** | ⚠️ | ⚠️ | ⚠️ | Phủ đủ nhưng yếu domain chuyên, nặng cho edge |

**Cặp low-resource (VI↔ZH, VI↔KO):**
- **Direct**: Qwen3 dịch thẳng — ít chặng, trễ thấp hơn.
- **Pivot qua EN** (VI→EN→KO): chất lượng có thể cao hơn nhưng +trễ +tích lũy lỗi.
- ⚠️ Phải đo COMET/BLEU thực tế cả hai trước khi chốt — không đoán.
- Streaming policy (mọi MT): AlignAtt / AliBaStr-MT.

## 3. TTS — chọn theo ngôn ngữ ĐÍCH (output)

| Ngôn ngữ output | Model | Chất lượng | Cảnh báo |
|---|---|---|---|
| **Tiếng Anh** | **Kokoro-82M** | Cao, ~100x realtime | ✅ lý tưởng |
| **Tiếng Trung** | **Kokoro-82M** (hỗ trợ ZH) | Cao | ✅ |
| **Tiếng Hàn** | **Kokoro-82M** (hỗ trợ KO) | Cao | ✅ |
| **Tiếng Việt** | **Piper** (giọng VI cộng đồng) hoặc **Moonshine TTS** (có VI) | TB–khá | ❌ Kokoro KHÔNG có tiếng Việt — bắt buộc dùng Piper/Moonshine cho chiều *→VI |

→ Kiến trúc TTS lai: Kokoro cho EN/ZH/KO, Piper (hoặc Moonshine) cho VI.

## 4. ⭐ Stack khuyến nghị theo từng hướng

| Hướng | ASR | MT | TTS | Độ khó |
|---|---|---|---|---|
| **VI → EN** | PhoWhisper | Qwen3-4B / NLLB | Kokoro (EN) | 🟢 Dễ |
| **EN → VI** | Whisper-small-Q | Qwen3-4B / NLLB | Piper/Moonshine (VI) | 🟢 Dễ |
| **VI → ZH** | PhoWhisper | Qwen3-4B (direct) | Kokoro (ZH) | 🟡 TB |
| **ZH → VI** | Paraformer-zh-streaming | Qwen3-4B | Piper/Moonshine (VI) | 🟡 TB |
| **VI → KO** | PhoWhisper | Qwen3-4B (test direct vs pivot-EN) | Kokoro (KO) | 🔴 Khó nhất |
| **KO → VI** | EZWhisper-KR | Qwen3-4B | Piper/Moonshine (VI) | 🔴 Khó nhất |

**Chiến lược:** Nhắm ngách nhà máy FDI Hàn/Đài ở VN → hai hướng khó nhất (VI↔KO, VI↔ZH) lại là giá trị thương mại lớn nhất, đầu tư đo đạc kỹ. VI↔EN dễ nhất, dùng demo "ăn chắc".

## Nguồn
- ASR: [PhoWhisper (arXiv 2406.02555)](https://arxiv.org/abs/2406.02555) · [Whisper-small-Q (Qualcomm AI Hub)](https://aihub.qualcomm.com/models/whisper_small_quantized) · [FunASR/Paraformer-zh](https://github.com/modelscope/FunASR) · [SenseVoice](https://github.com/FunAudioLLM/SenseVoice) · [EZWhisper-KR](https://www.edge-ai-vision.com/2025/11/small-models-big-heat-conquering-korean-asr-with-low-bit-whisper/) · [Moonshine](https://github.com/moonshine-ai/moonshine)
- MT: [NLLB-200](https://ai.meta.com/blog/nllb-200-high-quality-machine-translation/) · [SeamlessM4T-v2](https://huggingface.co/facebook/seamless-m4t-v2-large) · [On-Device LLMs 2026](https://v-chandra.github.io/on-device-llms/) · [VI–ZH low-resource MT (arXiv 2501.19314)](https://arxiv.org/pdf/2501.19314)
- Streaming: [AliBaStr-MT (arXiv 2508.13358)](https://arxiv.org/html/2508.13358v1) · [AlignAtt / CUNI IWSLT 2025 (arXiv 2506.17077)](https://arxiv.org/html/2506.17077)
- TTS: [Kokoro](https://kokorottsai.com/) · [Best On-Device TTS 2026](https://getstream.io/blog/best-on-device-tts-models/)
- Qualcomm: [AI Hub Models](https://aihub.qualcomm.com/models) · [qai-hub-models (GitHub)](https://github.com/qualcomm/ai-hub-models)
