# Sự cố QNN `0x36b1`: FastRPC bị chặn bởi Android linker policy

- **Ngày:** 2026-08-08
- **Trạng thái:** Đã khắc phục và xác minh trên thiết bị
- **Thiết bị thực tế:** Samsung SM-F731B (Galaxy Z Flip5), SM8550/kalama,
  Hexagon v73, Android 16/API 36, `user/release-keys`, bootloader khóa
- **ADB serial:** `R5CW72TP4EP` (chỉ là transport serial)
- **Phạm vi đã xác minh:** Khởi tạo QNN/HTP và load đủ 5 context VI→EN

## Tóm tắt

App báo:

```text
contextCreateFromBinary failed (0x36b1)
```

Chuỗi lỗi trong UI trước đây tự kết luận rằng firmware từ chối app và cần
signed Hexagon skeleton. Kết luận đó sai. `0x36b1` là
`QNN_DEVICE_ERROR_INVALID_CONFIG`; mã này chỉ cho biết QNN device layer trả lỗi,
không cho biết nguyên nhân là chữ ký.

Nguyên nhân thật là app target API 36 không khai báo dependency tới vendor
library `libcdsprpc.so`. Android vì thế không đưa thư viện này vào linker
namespace của app. FastRPC host transport không khởi tạo được và QNN quy lỗi
transport thành `14001/0x36b1`.

## Impact

- QNN backend/device không khởi tạo đúng.
- Cả 5 `.bin` đều dừng ở `contextCreateFromBinary` dù đúng SM8550/v73.
- Việc tạo TestSig và thay đổi `ADSP_LIBRARY_PATH` không thể sửa lỗi host linker
  xảy ra trước khi DSP xác minh skeleton.
- Thông báo UI dẫn điều tra sang signed-PD/device policy, làm che mất root cause.

Không có model hoặc dữ liệu người dùng bị hỏng.

## Bằng chứng và chuỗi lỗi

Log đầy đủ trước bản sửa:

```text
libcdsprpc.so unavailable: library "libcdsprpc.so" not found
QnnDsp: loadRemoteSymbols failed with err 4000
Failed to create transport for device, error: 4000
Failed to load skel, error: 4000
Transport layer setup failed: 14001
deviceCreate/contextCreateFromBinary failed: 0x36b1
```

Các bằng chứng bổ sung:

1. `libQnnHtpV73Stub.so` trong chính APK có `DT_NEEDED libcdsprpc.so`.
2. Firmware có `/vendor/lib64/libcdsprpc.so` và liệt kê nó trong
   `/vendor/etc/public.libraries.txt`.
3. App target API 36 nhưng manifest cũ không có `<uses-native-library>`.
4. Skeleton trong APK có SHA-256 trùng tuyệt đối với QAIRT 2.47
   `hexagon-v73/unsigned/libQnnHtpV73Skel.so`.
5. Runtime log ghi `signedPd=false`; lỗi không xuất phát từ signed-PD config.
6. `testsig-0x98031706.so` dùng đúng SoC serial, nhưng không liên quan tới lỗi
   linker và không phải production trust anchor cho điện thoại retail.

## Root cause

Từ Android 12/API 31, app target API 31+ không được truy cập mặc định các
vendor-provided non-NDK shared libraries. App phải yêu cầu từng public vendor
library bằng `<uses-native-library>` trong `<application>`.

Thư viện tồn tại trên `/vendor` và có trong `public.libraries.txt` vẫn chưa đủ;
khai báo manifest là bước làm cho Package Manager đưa library vào app linker
namespace. Xem tài liệu Android chính thức:
[uses-native-library](https://developer.android.com/guide/topics/manifest/uses-native-library-element).

## Cách khắc phục

### 1. Expose FastRPC vendor library cho app

Đã thêm vào
[AndroidManifest.xml](../../android-app/app/src/main/AndroidManifest.xml):

```xml
<application ...>
    <uses-native-library
        android:name="libcdsprpc.so"
        android:required="true" />
</application>
```

`required=true` phù hợp vì target phần cứng của app cố định là SM8550 và app
không thể chạy QNN HTP nếu thiếu cDSP FastRPC transport.

### 2. Thiết lập unsigned-PD trước QNN/FastRPC

Thứ tự bắt buộc trong
[qnn_runtime.cpp](../../native-core/src/qnn_runtime.cpp):

```text
setenv(ADSP_LIBRARY_PATH)
→ dlopen(libcdsprpc.so)
→ remote_session_control(DSPRPC_CONTROL_UNSIGNED_MODULE, cdsp, enable=1)
→ dlopen(libQnnHtp.so)
→ backendCreate/deviceCreate
→ contextCreateFromBinary
```

Unsigned-module control phải chạy trước FastRPC open đầu tiên. Không đặt nó sau
`backendCreate` hoặc sau khi HTP session có thể đã được tạo.

### 3. Không suy luận signature policy từ `0x36b1`

Thông báo native đã được sửa để ghi:

- process-domain mode thực tế;
- kết quả FastRPC unsigned-control;
- các nhóm nguyên nhân cần kiểm tra;
- cảnh báo rằng error code không tự chứng minh lỗi signing.

## Kết quả xác minh

Log sau bản sửa:

```text
remote_session_control(UNSIGNED_MODULE, cdsp, enable=1) = 0
QNN backend ready
loaded asr          1151.3 MB  2138 ms
loaded mt_encoder     95.4 MB   168 ms
loaded mt_decoder    149.3 MB   416 ms
loaded tts_encoder    64.7 MB   656 ms
loaded tts_decoder   109.7 MB   215 ms
```

Tất cả context báo `socModel=43`, đúng SM8550, binary build `v2.45.0`.

## Các policy phải nhớ

| Policy | Hệ quả cho dự án |
|---|---|
| Android linker namespace, target API 31+ | Mọi vendor non-NDK library phải được khai báo bằng `<uses-native-library>`. |
| Qualcomm untrusted APK từ Android R | App bên thứ ba trên retail nên dùng unsigned User PD; signed PD thường dành cho client được OEM whitelist. |
| Hexagon TestSig | Chỉ dành cho test/debug-fused device và quy trình RFSA provisioning; copy vào APK/app data không cài production trust. |
| TestSig và signed skeleton là hai artifact khác nhau | `testsig-0x....so` không phải `libQnnHtpV73Skel.so` và không tự ký skeleton unsigned. |
| `ADSP_LIBRARY_PATH` | Chỉ điều khiển lookup phía DSP; không thay Android linker namespace, SELinux hoặc signature policy. |
| `0x36b1` | Là `QNN_DEVICE_ERROR_INVALID_CONFIG`, không phải bằng chứng độc lập rằng cần signed skeleton. |
| Version compatibility | Backend, V73 stub, V73 skeleton và serialized context phải tương thích QAIRT/HTP ABI. |

### Serial dùng cho TestSig

- ADB serial `R5CW72TP4EP` chỉ chọn thiết bị cho lệnh `adb -s`.
- SoC serial trên thiết bị là `2550339334 = 0x98031706`.
- Tên TestSig đúng là `testsig-0x98031706.so`.

Ngay cả khi tên đúng, TestSig không authenticate production OEM phone nếu
firmware/debug fuse không cho phép test-signing.

## Checklist nếu lỗi tái diễn

1. Đọc các dòng **trước** `0x36b1`, không chỉ dòng cuối.
2. Xác nhận merged manifest chứa `uses-native-library: libcdsprpc.so`.
3. Xác nhận `dlopen("libcdsprpc.so")` thành công.
4. Với unsigned mode, cần thấy `remote_session_control(...)=0` trước QNN backend.
5. Log chính xác `DSP mode`, `ADSP_LIBRARY_PATH` và skeleton được extract.
6. Kiểm tra stub bằng `readelf -d` và đảm bảo mọi `DT_NEEDED` đều resolve.
7. Chỉ điều tra signature khi FastRPC transport đã lên và log có bằng chứng
   authentication/transport status cụ thể.
8. Sau cùng mới kiểm tra HTP arch, `socModel`, QAIRT build và graph manifest.

## Tài liệu liên quan

- [QNN signed skeleton, TestSig và PD modes](../QNN_SIGNED_DSP.md)
- [NPU deployment/troubleshooting](../NPU_DEPLOY.md)
- [Repo operational notes](../../CLAUDE.md)
- Qualcomm Hexagon SDK local:
  `docs/software/system_integration.html` và `docs/tools/sign.html`

> Sau sự cố này, pipeline VI → EN đã được nối đầy đủ. Luồng live dùng generic
> `executeGraph()` cho sáu context QNN; `executeCascade()` cũ chỉ còn là ABI
> legacy và vẫn trả `false` có chủ ý.
