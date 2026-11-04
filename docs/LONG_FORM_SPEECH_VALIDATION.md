# Kiểm chứng lượt nói dài (long-form speech)

Ngày: 2026-11-04. Nhánh `feat/long-turn`. Đặc tả và tiêu chí nghiệm thu:
[`LONG_FORM_SPEECH_IMPLEMENTATION.md`](LONG_FORM_SPEECH_IMPLEMENTATION.md).
Kiến trúc: [`ARCHITECTURE.md`](ARCHITECTURE.md), mục "Long turns".

**Kết luận ngắn.** Chế độ `ADAPTIVE` đã chạy end-to-end trên điện thoại thật
qua đúng đường capture production (replay file thay micro). Lượt dài không còn
bị cắt mất phần sau 10 giây. Độ trễ từ lúc hệ thống xác nhận người nói dừng tới
mẫu bản dịch đầu tiên giảm từ p50 5,5 s (B1) xuống 40 ms (B3), vì phần lớn bản
dịch đã được tổng hợp sẵn trong lúc người nói còn nói. Lượt ngắn không chậm
hơn (p50 −11%, p95 −9% so với B0) và WER lượt ngắn chênh +0,46 điểm. Toàn bộ số
đo dùng **audio tổng hợp từ dataset** hoặc **item dataset đơn lẻ**; chưa có bản
ghi giọng thật nói dài, chưa test micro/Bluetooth live, chưa soak 20–30 phút
bằng micro. Vì vậy `LEGACY` vẫn là mặc định; `ADAPTIVE` bật bằng
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
  `softSplit` ở §3.5.

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

## 4. Kiểm thử

### 4.1. JVM (119 test, PASS)

`./gradlew :app:testDebugUnitTest`:
- `TurnSegmenterTest`: lượt ngắn giống hệt mẫu của `UtteranceSegmenter` legacy;
  speech liên tục 35 s cắt ép đúng trần với overlap căn lưới 80 ms; cắt ở khoảng
  nghỉ có ngữ cảnh trái cố định; soft split rồi im lặng không sinh cửa sổ rỗng;
  trần lượt kết thúc đúng một lần; đọc mẫu lẻ (1–320 mẫu ngẫu nhiên) vẫn phủ kín;
  detector reset một lần mỗi lượt; cửa sổ đầu dưới floor bị bỏ và tiếp tục nghe;
  chính sách FIXED; cắt ép ưu tiên khe ngắn; `finish()`. Archive được kiểm từng
  mẫu đánh dấu: không mất, không trùng.
- `TranscriptAssemblerTest`: ghép theo core; từ trùng và từ bị cả hai cửa sổ bỏ
  ở điểm giữa; từ lặp thật ("có có", "rất rất") giữ nguyên; ngữ cảnh trái không
  commit hai lần; flush phần đuôi.
- `TranslationUnitBuilderTest`: câu EN, khoảng nghỉ VI, lượt một cửa sổ đúng một
  unit, chia theo budget không mất/lặp từ, ưu tiên dấu phẩy, budget tính theo câu.
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
| Long-turn held-out giọng thật | NOT RUN | Chờ bản ghi theo `LONG_FORM_RECORDING_SCRIPT.md` |
| Soak 20–30 phút | PARTIAL | ~20 phút replay liên tục; chưa có micro live |
| Tuning B4 (thread/power/prebuffer) | NOT RUN | |
| Lint | PASS cho file đã sửa | 20 lỗi còn lại có từ trước, ở `BluetoothRoutingProbeActivity` (debug) và `DeviceVitals` |

## 5. Lỗi còn lại và đánh đổi

1. **Route nhầm khi cùng người nói tiếp sau ≥ 1 s im lặng.** Soft prior giả định
   hai người luân phiên. Một bài nói dài có khoảng ngắt ≥ 1 s bị chia lượt và có
   thể bị đổi ngôn ngữ ở lượt sau nếu điểm hai nhánh sát nhau. Cần hiệu chỉnh
   margin/prior bằng log người nói thật; không đổi ngưỡng dựa trên fixture tổng hợp.
2. **Cửa sổ cuối quá ngắn** sau một lần cắt ở khoảng nghỉ có thể làm sai từ cuối
   (EV-7496). Ngữ cảnh trái 960 ms không đủ cho trường hợp đó; soft split
   8500 ms tránh được nó nhưng chưa chứng minh tốt hơn tổng thể (§3.5). Hướng
   tiếp: ghép lại cửa sổ cuối với cửa sổ trước khi tổng ≤ 10 s và chưa phát.
   Prior lan giữa các item cũng làm tăng nhiễu đo; một bộ chấm tách riêng
   "score-only routing" sẽ giúp so cấu hình.
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
