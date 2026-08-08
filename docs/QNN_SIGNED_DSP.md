# QNN signed Hexagon skeleton on Android

> Sự cố `0x36b1` trên thiết bị SM8550 đã được xác định là Android linker
> namespace không expose `libcdsprpc.so`, không phải thiếu signed skeleton.
> Xem [postmortem đầy đủ](incidents/2026-08-08-qnn-0x36b1-fastrpc-linker-policy.md).

Tài liệu này mô tả đúng hai cơ chế thường bị gọi chung là “testsig”:

1. **Production-signed skeleton**: `libQnnHtpV73Skel.so` đã được
   Qualcomm/OEM ký bằng chain mà firmware retail tin cậy. Đây là phương án cho
   ứng dụng phát hành trên máy khóa bootloader.
2. **Test signature**: trust artifact riêng cho **một thiết bị phát triển**, do
   Hexagon SDK tạo từ SoC hardware serial. Artifact thường có tên
   `testsig-0x....so`, không phải một file `.testsig` chung có thể nhúng vào APK.
   Việc provision testsig vào RFSA/firmware cần quyền OEM/root và firmware cho
   phép test key; app thường trên máy retail không thể tự làm bước này.

ADB serial `R5CW72TP4EP` chỉ là USB/ADB serial, **không phải** SoC serial dùng
để tạo testsig.

## Artifact nào nằm ở đâu

| Artifact | Kiến trúc | Vị trí |
|---|---|---|
| `libQnnHtp.so`, `libQnnSystem.so`, `libQnnHtpPrepare.so` | ARM64 | APK `lib/arm64-v8a/` |
| `libQnnHtpV73Stub.so` | ARM64/FastRPC stub | APK `lib/arm64-v8a/` |
| `libcdsprpc.so` | ARM64/FastRPC transport của vendor | `/vendor/lib64`; khai báo bằng `<uses-native-library>` |
| `libQnnHtpV73Skel.so` | Hexagon DSP6, không phải ARM64 | asset rồi extract ra filesystem, hoặc RFSA của OEM |
| `testsig-0x....so` | DSP trust artifact theo thiết bị | OEM/test-device provisioning; không dùng như Android JNI lib |

Không đặt skeleton vào `jniLibs/arm64-v8a`: Android linker sẽ hiểu sai nó là
ARM64 ELF. FastRPC tìm skeleton qua `ADSP_LIBRARY_PATH`.

## Tích hợp production-signed skeleton vào project

Không commit binary có license. Truyền file từ một thư mục bảo mật lúc build:

```bash
cd android-app
./gradlew :app:assembleDebug \
  -PqnnSignedSkel=/secure/qairt-2.47/v73/libQnnHtpV73Skel.so
```

Task `prepareQnnSignedDspAssets` đổi tên canonical và đưa file vào generated
asset `dsp/signed/libQnnHtpV73Skel.so`. `NpuEngine` sẽ:

1. extract asset ra `<filesDir>/dsp/signed/libQnnHtpV73Skel.so`;
2. đưa thư mục đó lên đầu `ADSP_LIBRARY_PATH`;
3. gọi native runtime với `useSignedProcessDomain=true`.

Có thể ép mode cho bring-up (mặc định `auto`):

```bash
# Chẩn đoán riêng unsigned-PD trên engineering/firmware cho phép
./gradlew :app:assembleDebug -PqnnPdMode=unsigned

# Không cho phép fallback unsigned
./gradlew :app:assembleDebug -PqnnPdMode=signed \
  -PqnnSignedSkel=/secure/path/libQnnHtpV73Skel.so
```

`auto` dùng unsigned skeleton đúng bộ QAIRT cho APK thông thường. Signed-PD chỉ
được chọn khi build cung cấp rõ ràng một signed skeleton tương thích. Trên thiết
bị đang kiểm tra, candidate OEM tồn tại tại:

```text
/vendor/lib/rfsa/adsp/snap/libQnnHtpV73Skel.so
```

Skeleton, stub và backend phải cùng HTP arch và tương thích QAIRT ABI/build.
Tên đúng nhưng phiên bản không khớp vẫn có thể làm FastRPC/device creation lỗi.

## Android linker namespace và `libcdsprpc.so`

`libQnnHtpV73Stub.so` có `DT_NEEDED libcdsprpc.so`. Samsung công bố thư viện
này trong `/vendor/etc/public.libraries.txt`, nhưng app target API 31+ vẫn phải
yêu cầu rõ trong `<application>`:

```xml
<uses-native-library
    android:name="libcdsprpc.so"
    android:required="true" />
```

Nếu thiếu tag này, `dlopen("libcdsprpc.so")` báo `not found`, FastRPC transport
không được tạo và QNN có thể quy lỗi thành `0x36b1`. Đây là nguyên nhân đã được
xác nhận trên thiết bị này; DSP chưa tới bước kiểm tra chữ ký skeleton.

## `ADSP_LIBRARY_PATH`

Android FastRPC examples dùng danh sách thư mục phân cách bằng dấu chấm phẩy.
Đường dẫn của app hiện là:

```text
<app-signed-dir nếu có>;
/vendor/lib/rfsa/adsp/snap;
/vendor/dsp/cdsp;
/vendor/lib/rfsa/adsp;
/system/lib/rfsa/adsp;
/dsp
```

Biến môi trường phải được đặt **trước lần nạp đầu tiên của `libQnnHtp.so`**.
Vì vậy `libonevoice_core.so` không còn link trực tiếp QNN (không còn QNN
`DT_NEEDED`); QNN chỉ được `dlopen()` sau khi path đã được công bố.

`ADSP_LIBRARY_PATH` chỉ điều khiển lookup phía DSP. Nó không:

- thay Android `LD_LIBRARY_PATH`/linker namespace cho thư viện ARM64;
- cấp thêm SELinux hoặc `/dev/adsprpc-smd` permission;
- bỏ qua signature validation;
- biến unsigned skeleton thành signed skeleton.

## QNN HTP signed-PD option

Với **HTP backend**, signed-PD là `QnnDevice` custom config. Không dùng
`QnnDspBackend_CustomConfig_t` (đó là API của DSP backend khác), và không truyền
nó vào `QnnContext_createFromBinary`.

```cpp
#include <cstdlib>
#include <QnnDevice.h>
#include <HTP/QnnHtpDevice.h>

bool createSignedHtpDevice(const QnnInterface_t* api,
                           const char* adspPath,
                           Qnn_DeviceHandle_t* device) {
    if (setenv("ADSP_LIBRARY_PATH", adspPath, 1) != 0) return false;

    QnnHtpDevice_CustomConfig_t htp{};
    htp.option = QNN_HTP_DEVICE_CONFIG_OPTION_SIGNEDPD;
    htp.useSignedProcessDomain.deviceId = 0;
    htp.useSignedProcessDomain.useSignedProcessDomain = true;

    QnnDevice_Config_t dev = QNN_DEVICE_CONFIG_INIT;
    dev.option = QNN_DEVICE_CONFIG_OPTION_CUSTOM;
    dev.customConfig = &htp;

    const QnnDevice_Config_t* configs[] = {&dev, nullptr};
    const auto& qnn = api->QNN_INTERFACE_VER_NAME;
    return qnn.deviceCreate(nullptr, configs, device) == QNN_SUCCESS;
}
```

Sau đó context binary vẫn được tạo với context config riêng (có thể `nullptr`):

```cpp
Qnn_ContextHandle_t context = nullptr;
const Qnn_ErrorHandle_t err = qnn.contextCreateFromBinary(
    backend,
    device,
    nullptr,                 // QnnContext_Config_t**, không phải signed-PD
    binary.data(),
    binary.size(),
    &context,
    nullptr);
```

Nếu signed `deviceCreate` lỗi, không fallback sang `device=nullptr`: fallback đó
sẽ quay lại default unsigned PD và che mất lỗi cấu hình thật.

## Set path từ Kotlin hoặc Java

Kotlin:

```kotlin
import android.system.Os

val adspPath = listOf(
    filesDir.resolve("dsp/signed").absolutePath,
    "/vendor/lib/rfsa/adsp/snap",
    "/vendor/dsp/cdsp",
    "/vendor/lib/rfsa/adsp",
    "/system/lib/rfsa/adsp",
    "/dsp",
).joinToString(";")

Os.setenv("ADSP_LIBRARY_PATH", adspPath, true)
System.loadLibrary("onevoice_core") // phải sau Os.setenv
OneVoiceNative.nativeInitRuntime(backendPath, systemPath, adspPath, true)
```

Java:

```java
import android.system.Os;

String adspPath = appSignedDir
        + ";/vendor/lib/rfsa/adsp/snap"
        + ";/vendor/dsp/cdsp"
        + ";/vendor/lib/rfsa/adsp"
        + ";/system/lib/rfsa/adsp"
        + ";/dsp";

Os.setenv("ADSP_LIBRARY_PATH", adspPath, true);
System.loadLibrary("onevoice_core");
boolean ok = OneVoiceNative.nativeInitRuntime(
        backendPath, systemPath, adspPath, true);
```

`System.setProperty("ADSP_LIBRARY_PATH", ...)` không thay process environment và
không có tác dụng với FastRPC.

## Khi nào dùng testsig

Chỉ dùng nhánh testsig khi có đủ cả ba điều kiện:

- thiết bị là engineering/userdebug hoặc OEM đã bật test-signing flow;
- testsig được tạo từ đúng **SoC hardware serial** và được provision vào RFSA
  theo Hexagon SDK/OEM procedure;
- skeleton được ký bằng test key tương ứng và runtime bật signed PD.

Trên bootloader-locked `user/release-keys` device, `adb push` testsig vào app
assets hoặc `filesDir` không provision trust chain. TestSig của Hexagon SDK yêu
cầu test/debug device có debug fuse; app thường trên OEM retail nên dùng
unsigned PD. Không root/remount, app cũng không thể cài testsig vào RFSA.

## Build/install và log trên thiết bị này

```bash
cd android-app
./gradlew :app:assembleDebug
adb -s R5CW72TP4EP install -r -t app/build/outputs/apk/debug/app-debug.apk
adb -s R5CW72TP4EP logcat -s OneVoiceNpu:* OneVoiceQNN:* OneVoiceCore:*
```

Với APK thông thường trên thiết bị này, log tốt cần thấy `DSP mode=unsigned`,
`remote_session_control(...)=0`, `QNN backend ready`, rồi từng context được
load. Chỉ kỳ vọng `DSP mode=signed` khi OEM đã whitelist app và cung cấp signed
v73 skeleton đúng bộ; biến môi trường không thể sửa trust/mismatch đó.
