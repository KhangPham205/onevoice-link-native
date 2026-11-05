# Kiểm chứng lượt nói dài (long-form speech)

Ngày: 2026-11-04. Nhánh `feat/long-turn`. Đặc tả và tiêu chí nghiệm thu:
[`LONG_FORM_SPEECH_IMPLEMENTATION.md`](LONG_FORM_SPEECH_IMPLEMENTATION.md).
Kiến trúc: [`ARCHITECTURE.md`](ARCHITECTURE.md), mục "Long turns".

**Kết luận ngắn.** Chế độ `ADAPTIVE` đã chạy end-to-end trên điện thoại thật
qua đúng đường capture production (replay file thay micro). Lượt dài không còn
bị cắt mất phần sau 10 giây. Độ trễ từ lúc hệ thống xác nhận người nói dừng tới
mẫu bản dịch đầu tiên giảm từ p50 5,5 s (B1) xuống 40 ms (B3), vì phần lớn bản
dịch đã được tổng hợp sẵn trong lúc người nói còn nói. Lượt ngắn không chậm
hơn (p50 −11%, p95 −9% so với B0) và WER lượt ngắn chênh +0,46 điểm. §3.1–3.6
dùng **audio tổng hợp từ dataset** hoặc **item dataset đơn lẻ**. §3.7 dùng **12
bản ghi giọng thật tiếng Việt** (30–134 s, một người nói): sau khi sửa cách chia
đơn vị dịch, audio đầu ra p50 66 ms, p95 130 ms sau endpoint, WER 3,3%, không
starvation. Chưa có bản ghi tiếng Anh, chưa test micro/Bluetooth live, chưa soak
20–30 phút bằng micro. Vì vậy `LEGACY` vẫn là mặc định; `ADAPTIVE` bật bằng
`-PlongTurnMode=ADAPTIVE`.

## 1. Môi trường

| Mục | Giá trị |
|---|---|
| Thiết bị | Samsung SM-F731B, SoC SM8550 (Snapdragon 8 Gen 2), Android 16, build `BP4A.251205.006.F731BXXS7GZI1` |
| Nguồn | không sạc trong lúc đo (USB không cấp nguồn); `screen_off_timeout` tạm đặt 30 phút |
| Code | `feat/long-turn` @ `0ec5d37` (APK debug, `longTurnMode=LEGACY` mặc định; mọi baseline chạy bằng `baseline=` của lệnh replay) |
| Model | `assets/npu_manifest.json` sha256 `57af7554…`; 6 QNN context như `CLAUDE.md`; VI TTS VieNeu Nano (16 steps, CFG 3.0, seed 1234, 4 threads), EN Kokoro 4 threads |
| Routing | `DUAL_ASR`, margin 0,15, floor −5,0, EN offset −0,08, prior 0,15 |
| Long-turn | soft split 7000 ms, micro-pause 160 ms, overlap 960 ms, ngữ cảnh trái sau pause 960 ms, trần 120 s, hàng đợi TTS 16 MiB, budget PCM 32 MiB |

## 2. Baseline

| Mã | Ý nghĩa | Cách chạy |
|---|---|---|
| B0 | Legacy: 1 cửa sổ 10 s mỗi lượt, mic đóng khi xử lý | `baseline=B0` |
| B1 | Thu liên tục, cửa sổ cố định 10 s + overlap 960 ms, MT/TTS sau khi hết lượt | `baseline=B1` |
| B2 | Như B1 nhưng cắt cửa sổ theo khoảng nghỉ | `baseline=B2` (ASR giống hệt B3; chưa đo riêng) |
| B3 | B2 + MT/TTS nền theo đơn vị nghĩa (ứng viên sản phẩm) | `baseline=B3` |
| B4 | B3 + tune thread/power/prebuffer | **NOT RUN** |

## 3. Kết quả

### 3.1. Lượt dài, chế độ paced (đồng hồ thực)

Dữ liệu: 6 fixture ghép từ dataset (VI/EN × 30/60/120 s;
`tools/eval/longturn/make_synthetic_fixtures.py`, seed 20261104), mỗi fixture
chạy B1 và B3 xen kẽ. Run `eval-results/longturn-paced-2/`.

| Chỉ số | B1 | B3 |
|---|---:|---:|
| Lượt > 10 s (mẫu) | 9 | 9 |
| Endpoint → mẫu dịch đầu, p50 | 5527 ms | **40 ms** |
| Endpoint → mẫu dịch đầu, p95 | 8597 ms | **61 ms** |
| Dừng nói (VAD) → mẫu dịch đầu, khoảng | 4600–9806 ms | 1031–1063 ms |
| Unit đầu sẵn sàng so với endpoint | +3,6…+8,8 s | −64…−2 s (trước khi dừng nói) |
| Cửa sổ / cắt ép | 37 / 25 | 45 / 5 |
| Starvation giữa các unit | 0 ms | 0 ms |
| Lượt lỗi | 0 | 0 |
| Backlog ASR tối đa | 1,0 s | 4,9 s (< một bước cửa sổ) |
| Hàng đợi TTS cao nhất | 3,9 MB | 5,8 MB (budget 16 MiB) |

"Endpoint" là lúc hệ thống xác nhận hết lượt (tiếng nói cuối + 1 s hangover).
"Mẫu dịch đầu" là lúc ghi mẫu nội dung đầu tiên vào AudioTrack, chưa phải lúc
âm thanh tới tai (cần đo loopback riêng).

WER ở chế độ paced không dùng để so ASR: fixture tổng hợp có vài khoảng lặng ≥
1 s nên bị tách thành nhiều lượt, và audio phát ra trong lúc đang phát bản dịch
bị bỏ qua như khi micro đóng. Phần bị bỏ khác nhau giữa B1 và B3.

Cùng điều kiện đó, B0 chỉ nhận 22,3/61 s (VI) và 30,0/64 s (EN) audio, WER
tổng 59% (`eval-results/longturn-paced-1/`). Đây chính là lỗi mất lời mà
chế độ mới sửa.

### 3.2. Lượt dài, chế độ fast (không bỏ audio)

Run `eval-results/longturn-fast-b3/` (trước khi thêm ngữ cảnh trái):

| Fixture | Lượt | Audio nhận / file | WER | Coverage từ |
|---|---:|---:|---:|---:|
| syn_vi_30s | 4 | 31,0 / 30,0 s | 4,3% | 0,98 |
| syn_vi_120s | 8 | 122,1 / 121,9 s | 3,5% | 0,99 |
| syn_en_30s (1 lượt, 4 cửa sổ) | 1 | 34,2 / 33,2 s | 10,6% | 0,96 |
| syn_en_120s | 3 | 120,6 / 120,5 s | 28,0% | 0,80 |

Phần lỗi lớn của syn_en_120s là **một lượt bị route nhầm sang VI→EN**. Điểm VI
là −0,36, EN sau offset là −0,21, chênh đúng 0,15 = margin, và soft prior "đổi
người nói" quyết định. Lượt 84 s trước đó (12 cửa sổ, 2 cắt ép) cho transcript
mạch lạc. Đây là giới hạn của chính sách routing khi cùng một người nói tiếp
sau khoảng lặng ≥ 1 s; LEGACY có cùng hành vi. Không phải lỗi ghép cửa sổ.

So sánh sạch B1 và B3 ở chế độ fast: xem §3.4.

### 3.3. Hồi quy lượt ngắn (B0 và B3)

16 item dataset đơn lẻ (8 VI, 8 EN, 2,4–11,3 s), paced, xen kẽ thứ tự. Run
`eval-results/shortturn-paced-2/`:

| Chỉ số | B0 | B3 | Δ |
|---|---:|---:|---:|
| WER tổng | 27,65% | 28,11% | +0,46 điểm |
| Mẫu dịch đầu p50 | 2609 ms | 2331 ms | −11% |
| Mẫu dịch đầu p95 | 3874 ms | 3543 ms | −9% |
| Lỗi | 0 | 0 | |

Run lặp `eval-results/shortturn-paced/` (trước khi thêm ngữ cảnh trái) cho
cùng xu hướng: p50 2285 → 1876 ms, WER 29,0 → 29,5%.

Hai item 7–10 s bị cắt ở khoảng nghỉ sau 7 s:
- EV-7738: câu đầu được dịch sớm, mẫu dịch đầu 975 ms (B0: 2513 ms).
- EV-7496: từ cuối "known" rơi vào cửa sổ chỉ 0,96 s và bị nhận thành "no",
  có hoặc không có ngữ cảnh trái 960 ms. Đây là cái giá của việc cắt sớm; sweep
  `softSplit` ở §3.5. Đã sửa bằng ngữ cảnh trái lấp đầy cửa sổ (§3.9).

WER tuyệt đối ~28% của bộ 16 item chủ yếu đến từ các item khó có sẵn (ví dụ
EV-8062 sai ở cả hai baseline). Dataset eval 200 câu đi qua `cmd=feed`, tức
đường legacy, nên không đổi khi bật ADAPTIVE.

### 3.4. B1 và B3, chế độ fast

Cùng 6 fixture, không bỏ audio, nên đây là so sánh ASR của chiến lược cắt.
Run `eval-results/longturn-fast-b1b3/` (đã có ngữ cảnh trái). Độ trễ ở chế độ
fast không có nghĩa và không báo.

| Fixture | WER B1 | WER B3 | Cửa sổ B1 / B3 |
|---|---:|---:|---:|
| syn_vi_30s | 4,3% | 4,3% | 4 / 5 |
| syn_vi_60s | 3,7% | 4,2% | 8 / 9 |
| syn_vi_120s | 2,4% | 3,0% | 17 / 18 |
| syn_en_30s | 12,4% | 13,3% | 4 / 5 |
| syn_en_60s | 11,1% | 11,6% | 8 / 9 |
| syn_en_120s | 32,7% | 29,8% | 15 / 17 |
| **Tổng** | **13,42%** | **12,97%** | |

Lượt route nhầm của syn_en_120s (§3.2) xảy ra ở **cả hai** baseline, cùng lý do
`prior`. Chênh lệch theo từng fixture nhỏ hơn mức nhiễu giữa các lần chạy (§3.5);
trên bộ này, cắt theo khoảng nghỉ không làm ASR tệ hơn cắt cố định có overlap.

### 3.5. Sweep soft split (lượt ngắn, B3) và nhiễu giữa các lần chạy

| Cấu hình | WER tổng | p50 | p95 | Run |
|---|---:|---:|---:|---|
| B0, lần 1 | 29,03% | 2285 ms | 3254 ms | `shortturn-paced` |
| B0, lần 2 | 27,65% | 2609 ms | 3874 ms | `shortturn-paced-2` |
| B3, soft split 7000 ms | 28,11% | 2331 ms | 3543 ms | `shortturn-paced-2` |
| B3, soft split 8500 ms | 29,03% | 2065 ms | 4050 ms | `shortturn-split8500` |

Cùng cấu hình B0 chạy hai lần lệch 1,4 điểm WER. Nguyên nhân: prior routing
lan từ item trước sang item sau, và thứ tự chạy khác nhau giữa các lần. Với 16
item, chưa tách được 7000 với 8500 ms. Ở 8500 ms, EV-7496 không còn bị cắt và
sai lại như B0 (0,167). **Giữ 7000 ms là giá trị tạm thời**; chọn lại trên bản
ghi giọng thật, với tập tuning/held-out tách theo file và nhiều lần lặp.

### 3.6. Bộ nhớ và nhiệt (phiên replay ~20 phút liên tục)

12 lần replay liên tiếp (`eval-results/longturn-paced-2/runs.json`): PSS app
trước mỗi run tăng từ 1 470 442 kB lên 1 503 942 kB (+33 MB qua 12 run), sau run
cao nhất 1 555 351 kB; thermal status 0, tới run cuối là 1. Archive cố định
3,85 MB tái sử dụng giữa các lượt. Mức tăng 33 MB chưa đủ dữ liệu để phân biệt
heap Java chưa GC với rò rỉ; cần soak dài hơn với `dumpsys meminfo` theo thời
gian (NOT RUN). PSS không gồm bộ nhớ riêng của DSP.

### 3.7. Giọng thật tiếng Việt (12 bản ghi, paced)

Bản ghi theo [`LONG_FORM_RECORDING_SCRIPT.md`](LONG_FORM_RECORDING_SCRIPT.md),
thu bằng Samsung Voice Recorder trên chính máy test (AAC 48 kHz, chuyển 16 kHz
mono bằng `recording_scripts.py prepare`), một người nói, 30–134 s/bài. Mức tín
hiệu: đỉnh −10 đến −0,4 dBFS, không clip, chênh tiếng nói/nền ~15–19 dB. Kết
quả: `eval-results/real-vi-paced` (B1, B3 trước khi sửa),
`eval-results/real-vi-paced-vi09b1` (chạy lại vi09/B1: lần đầu cảnh báo pin yếu
của SystemUI mở màn hình Battery, activity bị pause và engine dừng ở giây 35),
`eval-results/real-vi-paced-gap` (B3 sau khi sửa).

**Lỗi tìm thấy.** Giọng thật nói liền mạch gần như không cho Silero một khoảng
non-speech ≥ 160 ms giữa lượt: 12 bài chỉ có 0–1 lần cắt ở khoảng nghỉ, còn lại
toàn cắt ép ở 10 s. GIPFormer không có dấu câu, nên đơn vị dịch VI chỉ được chia
khi chạm budget 96 token (~95 âm tiết, ~22–25 s lời nói). Kokoro EN cần ~20 s cho
một đơn vị như vậy (RTF ~0,76 trên audio ra), nên với bài 30–45 s, đơn vị đầu
xong sau khi người nói đã dừng: 4/12 bài chờ 6,4–13,9 s dù đang ở B3. Fixture
tổng hợp không lộ lỗi này vì các câu được ghép với khoảng lặng 150–550 ms.

**Sửa.** Khi không có dấu câu, lúc văn bản đang mở vượt 24 token (đơn vị đầu
của lượt) hoặc 40 token (các đơn vị sau), `TranslationUnitBuilder` nhả một đơn
vị tại khoảng cách lớn nhất giữa hai thời điểm bắt đầu từ liên tiếp, trong các
ranh giới cho đơn vị từ một nửa tới đủ kích thước đó (`reason: word_gap`, log
`boundaryGapMs`). Lượt một cửa sổ vẫn đúng một đơn vị; EN không đổi. 24/40 được
chọn từ tốc độ nói đo được (~4,3 âm tiết/s: ~5,5 s và ~9 s, khoảng một cửa sổ),
không quét tham số; replay có thể ghi đè bằng `--unit-tokens 24,40`.

| B3, 12 bài | Trước (budget 96) | Sau (`word_gap` 24/40) |
|---|---|---|
| Endpoint → mẫu bản dịch đầu, p50 / p95 / max | 78 ms / 12 742 ms / 13 870 ms | **66 ms / 130 ms / 196 ms** |
| Ngừng nói thật → mẫu đầu, p50 / max | 1 069 / 14 870 ms | **1 060 / 1 196 ms** |
| WER gộp (VI) | 3,36% | 3,33% (ASR không đổi) |
| Số đơn vị / lượt | 2–5 | 3–16 |
| Starvation, underrun trước drain, lỗi | 0 / 0 / 0 | 0 / 0 / 0 |
| Backlog max, TTS queue high-water | 6,7 s, 6,8 MB | 6,7 s, 9,0 MB |
| Từ EN / âm tiết VI (tỉ lệ thô, không phải điểm MT) | 0,70 (0,38–0,85) | 0,78 (0,60–0,89) |

- Held-out (`vi04`, `vi06`, `vi08`, `vi12`, không dùng để chọn tham số): mẫu đầu
  55 / 41 / 60 / 76 ms, WER 0,9 / 3,4 / 1,0 / 1,3%.
- Khoảng cách tại chỗ chia (94 lần): min 240 ms, p50 440 ms, max 1 840 ms; so
  với ~230 ms mỗi âm tiết khi nói liền, phần lớn lần chia rơi vào một chỗ ngừng
  thật.
- B1 trên cùng bộ (trước khi sửa, có lượt chạy lại vi09): mẫu đầu p50 15,5 s,
  max 23 s; starvation 1,8 s và 1,25 s ở hai bài.
- `vi10` dài 133,7 s: lượt dừng đúng ở trần 120 s (`turn_cap`), WER 10,8% là
  phần nói sau trần, không phải lỗi nhận dạng.
- **Chất lượng dịch chưa được chấm** (chưa có bản dịch tham chiếu). Marian bỏ
  bớt nội dung với đầu vào VI dài không dấu câu: trước khi sửa, `vi04` chỉ còn
  0,38 từ EN/âm tiết. Đơn vị nhỏ giữ được nhiều nội dung hơn ở 10/12 bài, nhưng
  không đều: `vi02` giảm 0,80 → 0,63 (một đơn vị 29 âm tiết mất địa chỉ và giờ
  mở cửa). Đây là hành vi của Marian trên văn bản không dấu câu, có ở cả hai
  cách chia; cần bản dịch tham chiếu để chấm chrF/BLEU trước khi chỉnh 24/40.

### 3.8. Tiếng Anh bằng TTS nhiều accent (12 bài, paced, B3)

Chưa có người nói tiếng Anh thật; 12 kịch bản `en01`–`en12` được đọc bằng Edge
neural TTS, mỗi bài một accent (`tools/eval/longturn/make_tts_recordings.py`):
Mỹ, Anh, Ấn Độ, Úc, Singapore, Philippines, Nigeria, Nam Phi, Hồng Kông và
`vi-VN-NamMinhNeural` đọc tiếng Anh (accent Việt; `en02`, `en06`, `en11`). Điều
kiện được mô phỏng: nhanh +20%, nhỏ −15 dB, ồn hồng SNR 10 dB, liền mạch
(khoảng ngừng ≤ 150 ms). Bài dài chỉ 71–85 s vì TTS đọc nhanh hơn người. Kết
quả: `eval-results/tts-en-paced-2`.

Lần chạy đầu (`eval-results/tts-en-paced`) bị loại: Edge chèn 0,8–1,1 s im lặng
số ở cuối câu, đủ để kết thúc lượt (hangover 1 s), nên 6/12 bài bị tách lượt và
mất 23–73% audio (paced bỏ audio khi micro đóng). Bộ dùng để chấm giới hạn mọi
khoảng ngừng ở 600 ms; đây là đặc điểm của TTS, không phải của người nói.

| | Kết quả |
|---|---|
| Lượt / bài | 1 cho cả 12 bài; `speaker_done` |
| Endpoint → mẫu đầu, p50 / p95 | 47 ms / 67 ms (ngừng nói thật → mẫu đầu 1,03–1,07 s) |
| WER, 9 accent không phải Việt | 5,96% (en01 0%, nhanh 8,3%, nhỏ 3,6%, ồn 9,0% / 5,2%, liền mạch 5,2%) |
| WER held-out `en04`, `en08`, `en12` | 4,98% |
| Starvation / lỗi | 0 / 0 |
| Accent Việt (`NamMinh`) | **Hỏng**: `en02`, `en06` route sang VI (WER 93%, 97%); `en11` đúng EN nhưng WER 52% |

**Accent Việt.** Với giọng này, Parakeet chấm gần ngang GIPFormer (EN −0,58 /
−0,41 so với VI −0,54 / −0,44; margin 0,12 và 0,06 < 0,15), nên soft prior quyết
định. Prior mong ngôn ngữ ngược với lượt trước, mà lượt trước trong replay cũng
là EN, nên chọn VI. Khi đã route đúng (`en11`), Parakeet vẫn nghe sai nhiều ("My
name is Chiang Men Jk… Houmen City"). Giọng TTS này áp âm vị tiếng Việt lên chữ
tiếng Anh, nặng hơn nhiều người Việt nói tiếng Anh thật, nên chưa đủ để kết
luận về người dùng thật; nhưng nó cho thấy rủi ro: với accent nặng, routing dựa
vào prior luân phiên, và prior sai khi cùng một người nói nhiều lượt liền. Cần
bản ghi người Việt nói tiếng Anh thật trước khi chỉnh margin/offset.

### 3.9. Lấp đầy ngữ cảnh trái của cửa sổ (EV-7496)

Trước đây, cửa sổ sau một lần cắt ở khoảng nghỉ chỉ đọc 960 ms ngữ cảnh trái.
EV-7496 (8,94 s) bị cắt ở 7,98 s sau "or uh", nên cửa sổ cuối chỉ có 1,9 s
("uh known") và Parakeet đọc thành "No". Graph HTP luôn chạy đủ 10 s, nên giờ mỗi
cửa sổ từ thứ hai trở đi, khi được cắt ra, đọc ngữ cảnh trái tới mức lấp đầy
10 s (bước 80 ms tính từ đầu core, cùng lưới với cửa sổ trước;
`LongTurnConfig.fillLeftContext`). Từ trong ngữ cảnh vẫn bị luật core loại, nên
không có từ nào được ghi nhận hai lần. Cửa sổ cắt ép đã đủ 10 s thì không đổi.
Cách này tốt hơn soft split 8,5 s (chỉ dời lỗi sang lượt dài hơn) và hơn việc
ghép cửa sổ cuối vào cửa sổ trước (phải rút lại những từ đã dịch/đã tổng hợp
trong B3).

A/B xen kẽ trên cùng bản build (`B3-nofill` là ngữ cảnh 960 ms cũ):

| 16 lượt ngắn, paced (`eval-results/shortturn-fill`) | B0 | B3-nofill | B3 |
|---|---:|---:|---:|
| WER tổng | 29,03% | 29,03% | **27,19%** |
| EV-7496 WER | 0,167 | 0,222 ("or no") | **0,167** ("or known") |
| Mẫu dịch đầu p50 / p95 | 2968 / 4193 ms | 2534 / 4126 ms | 2870 / 4108 ms |

- 14/16 item chỉ có một cửa sổ, nên giống hệt nhau ở hai cấu hình B3; chênh p50
  giữa hai cột là nhiễu giữa các lần chạy (cùng cấu hình, VE-9570 lệch 750 ms).
- Phần giảm WER thêm đến từ EV-7496 và EV-8062. EV-8062 bị tách thành hai lượt
  một cửa sổ do khác biệt khi capture, không phải do thay đổi này.
- ASR cửa sổ cuối của EV-7496 (EN): 134 → 138 ms. Với Parakeet CTC, ngữ cảnh
  thêm gần như không tốn gì.

12 bản ghi VI giọng thật, B3 (`eval-results/real-vi-paced-fill`, so với
`real-vi-paced-gap`): WER 3,33% → 3,23% (`vi07` 1,8% → 0,9%, cửa sổ cuối cũ chỉ
0,94 s); mẫu dịch đầu p50 / p95 62 / 74 ms; starvation 0. **Chi phí phía VI:**
bộ giải mã RNN-T trên CPU chạy hết các frame hợp lệ, nên ASR cửa sổ cuối tăng
từ 380–765 ms (cửa sổ 0,9–8,4 s) lên 617–915 ms (cửa sổ ~10 s), khoảng +250 ms
trung vị. Ở lượt dài, việc này không ảnh hưởng mẫu dịch đầu (đã sẵn từ các đơn
vị trước). Ở lượt VI 7–10 s có cắt ở khoảng nghỉ, nó nằm trên đường găng của
đơn vị cuối; trường hợp này chưa có trong bộ đo. Nếu cần, có thể giới hạn ngữ
cảnh thêm cho VI (ví dụ 3–4 s).

## 4. Kiểm thử

### 4.1. JVM (128 test, PASS)

`./gradlew :app:testDebugUnitTest`:
- `TurnSegmenterTest`: lượt ngắn giống hệt mẫu của `UtteranceSegmenter` legacy;
  speech liên tục 35 s cắt ép đúng trần với overlap căn lưới 80 ms; cắt ở khoảng
  nghỉ có ngữ cảnh trái cố định; soft split rồi im lặng không sinh cửa sổ rỗng;
  trần lượt kết thúc đúng một lần; đọc mẫu lẻ (1–320 mẫu ngẫu nhiên) vẫn phủ kín;
  detector reset một lần mỗi lượt; cửa sổ đầu dưới floor bị bỏ và tiếp tục nghe;
  chính sách FIXED; cắt ép ưu tiên khe ngắn; `finish()`; ngữ cảnh trái lấp đầy
  cửa sổ (cửa sổ cuối ngắn đọc từ đầu lượt, không vượt 10 s, cửa sổ cắt ép đầy
  giữ nguyên, tắt được bằng `fillLeftContext=false`). Archive được kiểm từng
  mẫu đánh dấu: không mất, không trùng.
- `TranscriptAssemblerTest`: ghép theo core; từ trùng và từ bị cả hai cửa sổ bỏ
  ở điểm giữa; từ lặp thật ("có có", "rất rất") giữ nguyên; ngữ cảnh trái không
  commit hai lần; flush phần đuôi.
- `TranslationUnitBuilderTest`: câu EN, khoảng nghỉ VI, lượt một cửa sổ đúng một
  unit, chia theo budget không mất/lặp từ, ưu tiên dấu phẩy, budget tính theo câu;
  `word_gap`: chưa vượt target thì chờ, chọn khoảng cách lớn nhất trong khoảng
  cho phép, chuỗi nhiều cửa sổ không mất/lặp từ, EN bỏ qua target, lượt VI một
  cửa sổ vẫn một unit.
- `LongTurnCoordinatorTest`: route một lần và tái dùng hypothesis; từ chối route
  fail-closed và yêu cầu dừng thu; chế độ hoãn (B1/B2) không dịch khi đang nói;
  lỗi NPU trước khi phát dựng lại toàn lượt trên CPU (revision mới, không phát
  audio cũ); lỗi sau khi đã phát là `partial_failure`; bản dịch chạm trần 128
  token được chia và dịch lại; không cho phát sau khi recovery bắt đầu.
- `PreparedAudioQueueTest`, `CaptureSourceTest`: budget byte, thứ tự, kết thúc,
  hủy; replay paced bỏ audio khi không có lượt nào đang thu; WAV 16 kHz mono.

### 4.2. Ma trận nghiệm thu

| Hạng mục | Trạng thái | Bằng chứng / ghi chú |
|---|---|---|
| Lượt > 10 s thu liên tục, không dừng mic ở ranh giới cửa sổ | PASS (replay) | §3.1, §3.2; micro live NOT RUN |
| Soft split rồi im lặng không treo, không mất đuôi | PASS | JVM; replay |
| Hợp đồng model ≤ 160 000 mẫu, budget token, confidence routing giữ nguyên | PASS | JVM; `require` trong NpuPipeline; điểm routing chỉ từ cửa sổ đầu, công thức không đổi |
| Không nhầm cửa sổ với lượt; prior commit một lần; khóa ngôn ngữ | PASS | JVM; log |
| MT/TTS nền theo unit, không phát cũ/lặp/mất unit | PASS (replay) | revision + thứ tự; playback outcome `complete` 27/27 lượt |
| Queue/bộ nhớ có giới hạn | PASS (theo thiết kế), soak NOT RUN | §3.6 |
| Cancel/restart/recovery không race, không phát lại | PASS (JVM) | Fault injection trên máy NOT RUN |
| Phone/BT route thật, stereo, half-duplex | PARTIAL | Replay dùng route loa và chứng minh route; micro Phone và TWS live NOT RUN |
| Hồi quy lượt ngắn | PASS | §3.3 |
| Long-turn held-out giọng thật | PARTIAL | VI: 12 bài, 4 held-out PASS về độ trễ/WER (§3.7); EN chỉ có TTS nhiều accent (§3.8): PASS trừ accent Việt; chất lượng MT chưa chấm |
| Soak 20–30 phút | PARTIAL | ~20 phút replay liên tục; chưa có micro live |
| Tuning B4 (thread/power/prebuffer) | NOT RUN | |
| Lint | PASS cho file đã sửa | 20 lỗi còn lại có từ trước, ở `BluetoothRoutingProbeActivity` (debug) và `DeviceVitals` |

## 5. Lỗi còn lại và đánh đổi

1. **Route nhầm khi cùng người nói tiếp sau ≥ 1 s im lặng.** Soft prior giả định
   hai người luân phiên. Một bài nói dài có khoảng ngắt ≥ 1 s bị chia lượt và có
   thể bị đổi ngôn ngữ ở lượt sau nếu điểm hai nhánh sát nhau. Cần hiệu chỉnh
   margin/prior bằng log người nói thật; không đổi ngưỡng dựa trên fixture tổng hợp.
2. **Cửa sổ cuối quá ngắn** (EV-7496): đã sửa bằng ngữ cảnh trái lấp đầy cửa
   sổ (§3.9). Còn lại: chi phí ~+250 ms cho ASR cửa sổ cuối phía VI. Prior lan
   giữa các item cũng làm tăng nhiễu đo; một bộ chấm tách riêng "score-only
   routing" sẽ giúp so cấu hình.
3. **Underrun ~1/lượt** ở cả B1 và B3 trong khi starvation = 0: đó là lúc track
   cạn ở cuối stream. Đã thêm `underrunsBeforeDrain` để tách khỏi gap thật; số
   liệu ở §3 được đo trước thay đổi này.
4. **Overrun micro không đo được bằng replay** (`captureReadLagMaxMs` = 0 khi
   không có AudioRecord). Cần chạy micro live với ADAPTIVE để xác nhận buffer 2 s
   và ưu tiên URGENT_AUDIO đủ khi TTS chạy song song.
5. **CPU recovery cho lượt dài** chỉ được kiểm bằng JVM với fake. Chưa ép lỗi
   NPU trên máy; timestamps từ sherpa CPU chưa được kiểm thực tế.
6. **Thời gian tới tai người nghe** chưa đo (cần loopback). Số liệu là thời điểm
   ghi mẫu vào AudioTrack.
7. **Marian bỏ nội dung với VI không dấu câu** (§3.7), ở cả đơn vị dài và
   ngắn. Chưa có điểm MT; cần bản dịch tham chiếu cho 12 bài VI. Hướng có thể
   thử: khôi phục dấu câu cho VI trước MT, hoặc đặt ranh giới đơn vị tại khoảng
   ngừng lớn hơn (target 32/48) rồi so chrF.
8. **Kokoro EN là nút cổ chai thông lượng** (RTF ~0,76 trên audio ra, tức
   ~0,9 thời gian nói). Lượt 120 s vẫn theo kịp, nhưng backlog 6,7 s; trên máy
   nóng hơn có thể starvation. B4 (thread/power) chưa chạy.
9. **Tiếng Anh accent Việt nặng route sang VI** (§3.8): điểm hai nhánh gần
   ngang, soft prior quyết định. Cần bản ghi người thật để hiệu chỉnh.

## 6. Tái lập

```bash
cd android-app && JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew --no-daemon --no-parallel \
  --max-workers=1 :app:testDebugUnitTest :app:assembleDebug && ./gradlew :app:installDebug
cd .. && PY=<python có openpyxl>
$PY tools/eval/longturn/make_synthetic_fixtures.py --dataset "Vie-Eng dataset" --out eval-results/longturn-fixtures --per-length 1
$PY tools/eval/longturn/make_synthetic_fixtures.py --dataset "Vie-Eng dataset" --out eval-results/shortturn-fixtures --single 8
$PY tools/eval/longturn/run_long_replay.py --serial <serial> --fixtures eval-results/longturn-fixtures \
  --out eval-results/longturn-paced-2 --baselines B1 B3 --mode paced --init --run-id l2
$PY tools/eval/longturn/run_long_replay.py --serial <serial> --fixtures eval-results/shortturn-fixtures \
  --out eval-results/shortturn-paced-2 --baselines B0 B3 --mode paced --run-id s2
$PY tools/eval/longturn/score_long_replay.py eval-results/longturn-paced-2
# Giọng thật (§3.7): bản ghi vi01.m4a... trong eval-results/recordings-raw
$PY tools/eval/longturn/recording_scripts.py prepare --recordings eval-results/recordings-raw \
  --out eval-results/real-vi --speaker spk1
$PY tools/eval/longturn/run_long_replay.py --serial <serial> --fixtures eval-results/real-vi \
  --out eval-results/real-vi-paced-gap --baselines B3 --mode paced --init
$PY tools/eval/longturn/score_long_replay.py eval-results/real-vi-paced-gap
# Tiếng Anh TTS (§3.8), cần edge-tts và mạng
$PY tools/eval/longturn/make_tts_recordings.py --out eval-results/recordings-tts-en
$PY tools/eval/longturn/recording_scripts.py prepare --recordings eval-results/recordings-tts-en \
  --out eval-results/tts-en --speaker edge_tts
$PY tools/eval/longturn/run_long_replay.py --serial <serial> --fixtures eval-results/tts-en \
  --out eval-results/tts-en-paced-2 --baselines B3 --mode paced --init
```

Dữ liệu thô của mỗi run nằm trong thư mục kết quả: `runs.json`, `turns.jsonl`
(một dòng cho mỗi lượt, `schema: 2`), `raw/<tag>.log` (logcat của các tag
OneVoice), `per_run.csv`, `summary.json`. Thư mục `eval-results/` bị git ignore.

## 7. Kiểm thử thực tế cần làm (Phone và TWS)

Build `-PlongTurnMode=ADAPTIVE`, cài, load pipeline, bấm Start:

1. **Phone:** route Phone, đặt điện thoại giữa hai người. Người A nói liên tục
   khoảng 30 s, rồi 60 s, rồi 120 s (đọc các bài `vi05`, `vi09` hoặc tương tự),
   rồi dừng. Kiểm tra: transcript trên màn hình tăng dần trong lúc nói; bản dịch
   bắt đầu khoảng 1 s sau khi dừng; nghe đủ phần đầu/giữa/cuối; không có tiếng
   của bản dịch lọt lại vào lượt sau (echo guard).
2. **TWS:** route Bluetooth với cặp JBL. Lặp lại bước 1; kiểm tra bản dịch tiếng
   Việt ở bên trái, tiếng Anh ở bên phải, micro SCO đóng trước khi phát (log
   `scoCloseMs` rồi `a2dpRestoreMs`).
3. **Trần 120 s:** nói liên tục quá 2 phút. Kỳ vọng: lượt kết thúc với thông báo
   giới hạn, phần đã nói được dịch đủ, lượt sau giữ cùng ngôn ngữ.
4. **Dừng giữa chừng:** bấm Stop khi đang nói và khi đang phát. Kỳ vọng: dừng ngay,
   không phát lại khi Start.
5. Gửi `adb logcat -s OneVoiceRouting:* OneVoiceLongTurn:* OneVoiceHalfDuplex:*`
   và file session export để đối chiếu `captureReadLagMaxMs` và
   `captureOverrunSuspected`.
