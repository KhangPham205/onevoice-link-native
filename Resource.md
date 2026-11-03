Dưới đây là nguồn cho từng nhóm claim, kèm ghi chú rõ **cái nào tôi đọc được nội dung** và **cái nào chỉ có URL**.

## Qualcomm — QNN / QAIRT

| Nội dung | URL | Tình trạng |
|---|---|---|
| Trang chủ tài liệu QAIRT/QNN | `https://docs.qualcomm.com/nav/home/index_QNN.html?product=1601111740009302` | mục lục có "Generating Profiling Reports", "Detailed Reports", "Performance Reports" |
| Overview QAIRT | `https://docs.qualcomm.com/bundle/publicresource/topics/80-63442-10/general_overview.html` | đọc được |
| **API ref `QnnProfile.h`** | `https://docs.qualcomm.com/bundle/publicresource/topics/80-63442-50/api-rst_file_include_QNN_QnnProfileh.html` | ⚠️ chỉ ra boilerplate |
| **API ref `QnnHtpProfile.h`** | `https://docs.qualcomm.com/bundle/publicresource/topics/80-63442-50/api-rst_file_include_QNN_HTP_QnnHtpProfileh.html` | ⚠️ chỉ ra boilerplate |
| Program listing `QnnHtpProfile.h` | `https://docs.qualcomm.com/bundle/publicresource/topics/80-63442-50/api-rst_program_listing_file_include_QNN_HTP_QnnHtpProfile_h.html` | ⚠️ tôi fetch trực tiếp, chỉ trả về HTML rỗng |
| HTP backend | `https://docs.qualcomm.com/bundle/publicresource/topics/80-63442-10/htp_backend.html` | ⚠️ chỉ ra boilerplate |
| Profile your model (QAIRT, Ubuntu workflow) | `https://docs.qualcomm.com/doc/80-90441-15/topic/profile-your-model.html` | snippet xác nhận có hình mô tả các execution profiling event chính của HTP và cách đo; và có lint profiling cho per-operation cycle count — trang chặn bot khi fetch |

**Nói thẳng: tôi không xác minh được các event ID 3001/3002/3003/3004/3012 từ nguồn công khai.** `docs.qualcomm.com` render bằng JS và chặn fetch; các trang API ref chỉ trả về boilerplate cookie. Nguồn có thẩm quyền nhất chính là header trong repo của bạn:

```bash
grep -rn "EVENTTYPE\|EVENTUNIT\|LEVEL_" \
  native-core/third_party/qnn_sdk/include/QNN/QnnProfile.h \
  native-core/third_party/qnn_sdk/include/QNN/HTP/QnnHtpProfile.h
```

Header trong SDK **thắng** mọi tài liệu online, vì giá trị enum có thể đổi giữa các bản.

**Nguồn phụ hữu ích** (không phải Qualcomm nhưng là code sản xuất chạy trên QNN, đọc được đầy đủ):
- ONNX Runtime QNN EP profiling — `https://onnxruntime.ai/docs/execution-providers/QNN-ExecutionProvider.html`. Có mục "QNN EP Profiling", "Optrace-Level Profiling", và để dùng QNN profiling chỉ cần đặt EP option `profiling_level` là `basic`, `detailed` hoặc `optrace`. Đây là bằng chứng gián tiếp mạnh cho ba mức profiling. Code C++ trong `onnxruntime/core/providers/qnn/` cho bạn thấy họ parse event ra sao — đáng đọc trước khi tự viết.
- `https://github.com/quic/ai-engine-direct-helper` (repo chính chủ Qualcomm) — `QNNConfig` cấu hình profiling level, `PerfProfile` set HTP perf profile. Đây là nguồn cho phần `htp_perf_mode` tôi khuyên bạn pin.

## Android — Energy

| Claim | Nguồn |
|---|---|
| ODPM chỉ Pixel 6+ | `https://developer.android.com/studio/profile/power-profiler` — Power Profiler đọc từ ODPM, chỉ có trên Pixel 6 và các Pixel sau đó chạy Android 10 (API 29) trở lên; một số power rail có thể chỉ có trên một số thiết bị triển khai ODPM |
| Rail phụ thuộc OEM | `https://perfetto.dev/docs/data-sources/battery-counters` — sự hiện diện và độ phân giải của power rail counter phụ thuộc nhà sản xuất; ở tầng platform dữ liệu lấy từ IPowerStats HAL |
| Vấn đề cắm USB khi đo | cùng trang Perfetto — nếu cắm cáp USB bạn sẽ thấy dòng dương và tổng charge tăng, khiến đo trong lab thành vấn đề; workaround là USB hub chuyên dụng ngắt điện, hoặc trên máy đã root dùng driver PMIC — tính năng đặc thù SoC, không tài liệu hoá, không expose qua HAL nào |
| Charge counter / current API | `https://developer.android.com/reference/android/os/BatteryManager` — `BATTERY_PROPERTY_CHARGE_COUNTER` là dung lượng pin tính bằng microampere-giờ; `CURRENT_NOW` là dòng tức thời tính bằng microampere, giá trị dương = dòng vào pin, âm = xả |
| Batterystats / dumpsys | `https://developer.android.com/tools/dumpsys` |

Lưu ý: doc Android **định nghĩa** đơn vị là µA và dấu âm = xả. Nhưng đó là hợp đồng API, còn `/sys/class/power_supply/battery/current_now` là sysfs thô do vendor viết và Samsung nổi tiếng lệch. Vẫn phải hiệu chuẩn — cứ dùng `BatteryManager` qua API thay vì đọc sysfs nếu được.

## Android — Macrobenchmark

| Claim | Nguồn |
|---|---|
| PowerMetric giới hạn Pixel 6+ | `https://developer.android.com/topic/performance/benchmarking/macrobenchmark-metrics` — các metric này đo mức tiêu thụ toàn hệ thống, không phải per-app, và giới hạn ở Pixel 6, Pixel 6 Pro và các máy sau đó |
| API kiểm tra khả năng | `https://developer.android.com/jetpack/androidx/releases/benchmark` — `PowerMetric.deviceSupportsHighPrecisionTracking()` (đổi tên từ `deviceSupportsPowerEnergy`) và `deviceBatteryHasMinimumCharge()` để đổi hành vi hoặc skip benchmark theo khả năng đo của thiết bị |
| Fallback | cùng nguồn — nếu `deviceSupportsHighPrecisionTracking()` thì dùng `Type.Energy()`, ngược lại rơi về `Type.Battery()` là tracking kém chính xác hơn |
| **Xung đột debuggable ↔ profileable** | `https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview` — cấu hình app được benchmark càng gần bản release càng tốt: đặt non-debuggable và tốt nhất có minification |
| Lỗi cụ thể bạn sẽ gặp | `https://developer.android.com/topic/performance/benchmarking/macrobenchmark-instrumentation-args` — lỗi `DEBUGGABLE` báo target package chạy với `debuggable=true`, làm giảm mạnh hiệu năng runtime; và target chạy thiếu `<profileable shell=true>` — profileable cần thiết để Macrobenchmark bắt trace chi tiết từ tiến trình target |

Đây chính là xung đột tôi nêu ở turn trước: `CLAUDE.md` của bạn nói push model "needs a debuggable build for `run-as`", còn Macrobenchmark báo lỗi trên build debuggable. Hai trang trên là bằng chứng.

## Android — Thermal & Memory

| Claim | Nguồn |
|---|---|
| Thermal API (Java) | `https://developer.android.com/games/optimize/adpf/thermal` — khuyến cáo không chỉ dựa vào `GetCurrentThermalStatus()` mà nên kiểm chứng thêm bằng `GetThermalHeadroom()` |
| Thermal API (NDK, dùng từ C++) | `https://developer.android.com/ndk/reference/group/thermal` — `AThermal_acquireManager`, `AThermal_getCurrentThermalStatus`, `AThermal_registerThermalStatusListener`, `AThermal_releaseManager` |
| Status code | `https://source.android.com/docs/core/power/thermal-mitigation` — status code dịch sang mức throttling cụ thể, dùng được để thu thập dữ liệu |
| `dumpsys meminfo` | `https://developer.android.com/tools/dumpsys` — kiểm tra memory usage theo thời gian bằng `procstats` hoặc tại một thời điểm bằng `meminfo` |

Vì core của bạn là C++ và inference chạy ngoài JVM, **dùng bản NDK `AThermal_*`** để sample nhiệt ngay trong worker thread thay vì bounce qua JNI.

---

## Ba điểm tôi không có nguồn chính thức

1. **Event ID cụ thể của HTP profiling** — phải grep header của bạn.
2. **Bộ nhớ DSP không nằm trong PSS** — tôi suy ra từ cơ chế FastRPC/dmabuf, không tìm được trang Qualcomm hay Android nói thẳng. Bạn tự chứng minh bằng thực nghiệm: chụp `dumpsys meminfo -d` trước/sau khi load 6 context, so delta với tổng kích thước `.bin`.
3. **Hành vi `current_now` trên Samsung SM8550** — đặc thù thiết bị, không doc nào bảo đảm. Phải hiệu chuẩn trên chính R5CW72TP4EP.