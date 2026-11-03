// ============================================================================
// qnn_runtime.cpp — Qualcomm QNN / Hexagon HTP multi-context runtime
// ----------------------------------------------------------------------------
// Compiles against the real QNN SDK when its headers are vendored under
// third_party/qnn_sdk/include/QNN. When they are absent every method degrades
// to a logged stub via __has_include, so the core library still builds and the
// JNI wiring can be exercised on a toolchain without the SDK.
//
// See qnn_runtime.h for why the cascade needs one context per .bin.
// ============================================================================

#include "qnn_runtime.h"

#include <android/log.h>
#include <dlfcn.h>  // dlopen / dlsym

#include <algorithm>
#include <chrono>
#include <cstring>
#include <cstdlib>
#include <fstream>
#include <limits>
#include <mutex>
#include <vector>

#define LOG_TAG "OneVoiceQNN"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#if __has_include(<QnnInterface.h>)
  #define ONEVOICE_HAS_QNN 1
  #include <QnnBackend.h>
  #include <QnnCommon.h>
  #include <QnnContext.h>
  #include <QnnDevice.h>
  #include <QnnGraph.h>
  #include <QnnInterface.h>
  #include <QnnProfile.h>
  #include <QnnTensor.h>
  #include <QnnTypes.h>
  #include <HTP/QnnHtpDevice.h>
  #include <HTP/QnnHtpPerfInfrastructure.h>
  #include <HTP/QnnHtpProfile.h>
  #include <System/QnnSystemContext.h>
  #include <System/QnnSystemInterface.h>
#else
  #define ONEVOICE_HAS_QNN 0
#endif

namespace onevoice {
namespace {

/** Read a whole .bin context binary into memory. */
bool readFileToBuffer(const std::string& path, std::vector<uint8_t>& out) {
    std::ifstream f(path, std::ios::binary | std::ios::ate);
    if (!f) return false;
    const std::streamsize size = f.tellg();
    if (size <= 0) return false;
    f.seekg(0, std::ios::beg);
    out.resize(static_cast<std::size_t>(size));
    return static_cast<bool>(f.read(reinterpret_cast<char*>(out.data()), size));
}

using Clock = std::chrono::steady_clock;
double msSince(Clock::time_point t0) {
    return std::chrono::duration<double, std::milli>(Clock::now() - t0).count();
}

/**
 * dlopen a packaged QNN library.
 *
 * The APK sets `useLegacyPackaging = false`, so the .so files are mmap'd
 * straight out of the APK and an absolute nativeLibraryDir path may not exist
 * on disk. Android's classloader namespace can resolve a packaged uncompressed
 * library by soname, so try that first and retain the absolute-path fallback for
 * builds that extract native libraries.
 */
void* openQnnLib(const std::string& path) {
    const std::size_t slash = path.find_last_of('/');
    const std::string soname = (slash == std::string::npos) ? path : path.substr(slash + 1);

    if (void* h = dlopen(soname.c_str(), RTLD_NOW | RTLD_GLOBAL)) return h;
    // dlerror() clears the error on read, so capture it before the next call.
    const char* e1 = dlerror();
    const std::string sonameErr = e1 ? e1 : "unknown";

    if (void* h = dlopen(path.c_str(), RTLD_NOW | RTLD_GLOBAL)) return h;
    const char* e2 = dlerror();
    LOGE("dlopen failed for both \"%s\" (%s) and \"%s\" (%s)",
         soname.c_str(), sonameErr.c_str(), path.c_str(), e2 ? e2 : "unknown");
    return nullptr;
}

/**
 * Enable unsigned-PD offload to the cDSP for an untrusted app.
 *
 * Qualcomm's FastRPC API requires the client to request unsigned-module
 * offload via remote_session_control() BEFORE the first remote_handle_open().
 * Do this before loading/creating the QNN backend so no earlier QNN operation
 * can create the cDSP session first.
 *
 * This is a process-wide FastRPC setting. Android 12+ also requires the app
 * manifest to request the public vendor library with <uses-native-library>;
 * otherwise dlopen("libcdsprpc.so") fails inside the app linker namespace.
 */
int enableUnsignedPdOffload() {
    void* rpc = dlopen("libcdsprpc.so", RTLD_NOW | RTLD_GLOBAL);
    if (!rpc) {
        LOGW("libcdsprpc.so unavailable: unsigned-PD offload not enabled (%s)",
             dlerror());
        return -1;
    }
    using CtrlFn = int (*)(uint32_t, void*, uint32_t);
    auto ctrl = reinterpret_cast<CtrlFn>(dlsym(rpc, "remote_session_control"));
    if (!ctrl) {
        LOGW("remote_session_control not found: unsigned-PD offload not enabled");
        return -1;
    }
    // Mirrors remote.h (vendored Hexagon SDK); kept local so the core needs no
    // extra include path. Values are stable FastRPC ABI.
    constexpr uint32_t kReqUnsignedModule = 2;  // DSPRPC_CONTROL_UNSIGNED_MODULE
    constexpr int kCdspDomainId = 3;            // CDSP_DOMAIN_ID (HTP lives here)
    struct { int domain; int enable; } cfg = { kCdspDomainId, 1 };
    const int rc = ctrl(kReqUnsignedModule, &cfg, sizeof(cfg));
    // Leave libcdsprpc resident: QNN needs it for deviceCreate next.
    LOGI("remote_session_control(UNSIGNED_MODULE, cdsp, enable=1) = %d (0x%x)",
         rc, rc);
    return rc;
}

#if ONEVOICE_HAS_QNN
/** "QNN_DATATYPE_FLOAT_32" -> "FLOAT_32"; keeps the UI copy short. */
std::string shortDataType(Qnn_DataType_t dt) {
    switch (dt) {
        case QNN_DATATYPE_FLOAT_16:         return "FLOAT_16";
        case QNN_DATATYPE_FLOAT_32:         return "FLOAT_32";
        case QNN_DATATYPE_INT_32:           return "INT_32";
        case QNN_DATATYPE_INT_64:           return "INT_64";
        case QNN_DATATYPE_UINT_32:          return "UINT_32";
        case QNN_DATATYPE_SFIXED_POINT_8:   return "SFIXED_8";
        case QNN_DATATYPE_UFIXED_POINT_8:   return "UFIXED_8";
        case QNN_DATATYPE_SFIXED_POINT_16:  return "SFIXED_16";
        case QNN_DATATYPE_UFIXED_POINT_16:  return "UFIXED_16";
        default:                            return "OTHER";
    }
}

/** Tensor accessors differ per Qnn_Tensor_t version; normalize them here. */
TensorInfo toTensorInfo(const Qnn_Tensor_t& t) {
    TensorInfo out;
    const char*      name = nullptr;
    Qnn_DataType_t   dt   = QNN_DATATYPE_UNDEFINED;
    uint32_t         rank = 0;
    const uint32_t*  dims = nullptr;

    if (t.version == QNN_TENSOR_VERSION_1) {
        name = t.v1.name; dt = t.v1.dataType; rank = t.v1.rank; dims = t.v1.dimensions;
    } else if (t.version == QNN_TENSOR_VERSION_2) {
        name = t.v2.name; dt = t.v2.dataType; rank = t.v2.rank; dims = t.v2.dimensions;
    }
    out.name     = name ? name : "";
    out.dataType = shortDataType(dt);
    for (uint32_t i = 0; i < rank && dims; ++i) out.dims.push_back(dims[i]);
    return out;
}

struct OwnedTensor {
    Qnn_TensorVersion_t version = QNN_TENSOR_VERSION_1;
    uint32_t id = 0;
    std::string name;
    Qnn_TensorType_t type = QNN_TENSOR_TYPE_UNDEFINED;
    Qnn_TensorDataFormat_t dataFormat = QNN_TENSOR_DATA_FORMAT_FLAT_BUFFER;
    Qnn_DataType_t dataType = QNN_DATATYPE_UNDEFINED;
    Qnn_QuantizeParams_t quantizeParams = QNN_QUANTIZE_PARAMS_INIT;
    std::vector<Qnn_ScaleOffset_t> axisScaleOffsets;
    std::vector<uint32_t> dims;
    std::vector<uint8_t> dynamicDims;
    Qnn_SparseParams_t sparseParams = QNN_SPARSE_PARAMS_INIT;
    uint8_t isProduced = 0;
};

bool copyTensor(const Qnn_Tensor_t& src, OwnedTensor& dst, std::string& error) {
    const char* name = nullptr;
    const uint32_t* dims = nullptr;
    const uint8_t* dynamicDims = nullptr;
    uint32_t rank = 0;

    dst.version = src.version;
    if (src.version == QNN_TENSOR_VERSION_1) {
        dst.id = src.v1.id;
        name = src.v1.name;
        dst.type = src.v1.type;
        dst.dataFormat = src.v1.dataFormat;
        dst.dataType = src.v1.dataType;
        dst.quantizeParams = src.v1.quantizeParams;
        rank = src.v1.rank;
        dims = src.v1.dimensions;
    } else if (src.version == QNN_TENSOR_VERSION_2) {
        dst.id = src.v2.id;
        name = src.v2.name;
        dst.type = src.v2.type;
        dst.dataFormat = src.v2.dataFormat;
        dst.dataType = src.v2.dataType;
        dst.quantizeParams = src.v2.quantizeParams;
        rank = src.v2.rank;
        dims = src.v2.dimensions;
        dynamicDims = src.v2.isDynamicDimensions;
        dst.sparseParams = src.v2.sparseParams;
        dst.isProduced = src.v2.isProduced;
    } else {
        error = "unsupported QNN tensor metadata version";
        return false;
    }

    dst.name = name ? name : "";
    if (rank > 0 && !dims) {
        error = "tensor " + dst.name + " has rank but no dimensions";
        return false;
    }
    if (dims) dst.dims.assign(dims, dims + rank);
    if (dynamicDims) dst.dynamicDims.assign(dynamicDims, dynamicDims + rank);

    const auto encoding = dst.quantizeParams.quantizationEncoding;
    if (encoding == QNN_QUANTIZATION_ENCODING_AXIS_SCALE_OFFSET) {
        const auto& axis = dst.quantizeParams.axisScaleOffsetEncoding;
        if (axis.numScaleOffsets > 0 && !axis.scaleOffset) {
            error = "tensor " + dst.name + " has invalid axis quantization metadata";
            return false;
        }
        if (axis.scaleOffset) {
            dst.axisScaleOffsets.assign(axis.scaleOffset,
                                        axis.scaleOffset + axis.numScaleOffsets);
        }
    } else if (encoding != QNN_QUANTIZATION_ENCODING_UNDEFINED &&
               encoding != QNN_QUANTIZATION_ENCODING_SCALE_OFFSET) {
        // The current cascade exposes FLOAT32/INT32 app tensors. Reject future
        // pointer-bearing encodings instead of retaining pointers owned by the
        // soon-to-be-freed QnnSystemContext.
        error = "tensor " + dst.name + " uses unsupported external quantization encoding";
        return false;
    }
    return true;
}

std::size_t elementSize(Qnn_DataType_t type) {
    switch (type) {
        case QNN_DATATYPE_BOOL_8:
        case QNN_DATATYPE_INT_8:
        case QNN_DATATYPE_UINT_8:
        case QNN_DATATYPE_SFIXED_POINT_8:
        case QNN_DATATYPE_UFIXED_POINT_8:
            return 1;
        case QNN_DATATYPE_FLOAT_16:
        case QNN_DATATYPE_INT_16:
        case QNN_DATATYPE_UINT_16:
        case QNN_DATATYPE_SFIXED_POINT_16:
        case QNN_DATATYPE_UFIXED_POINT_16:
            return 2;
        case QNN_DATATYPE_FLOAT_32:
        case QNN_DATATYPE_INT_32:
        case QNN_DATATYPE_UINT_32:
        case QNN_DATATYPE_SFIXED_POINT_32:
        case QNN_DATATYPE_UFIXED_POINT_32:
            return 4;
        case QNN_DATATYPE_FLOAT_64:
        case QNN_DATATYPE_INT_64:
        case QNN_DATATYPE_UINT_64:
            return 8;
        default:
            return 0;
    }
}

bool tensorBytes(const OwnedTensor& tensor, std::size_t& bytes) {
    bytes = elementSize(tensor.dataType);
    if (bytes == 0) return false;
    for (const uint32_t dim : tensor.dims) {
        if (dim == 0 || bytes > std::numeric_limits<std::size_t>::max() / dim) return false;
        bytes *= dim;
    }
    return true;
}

Qnn_Tensor_t executionTensor(OwnedTensor& src, const GraphBuffer& buffer) {
    Qnn_Tensor_t out = QNN_TENSOR_INIT;
    Qnn_ClientBuffer_t client = QNN_CLIENT_BUFFER_INIT;
    client.data = buffer.data;
    client.dataSize = buffer.sizeBytes;

    if (src.quantizeParams.quantizationEncoding ==
        QNN_QUANTIZATION_ENCODING_AXIS_SCALE_OFFSET) {
        src.quantizeParams.axisScaleOffsetEncoding.scaleOffset =
            src.axisScaleOffsets.empty() ? nullptr : src.axisScaleOffsets.data();
    }

    out.version = src.version;
    if (src.version == QNN_TENSOR_VERSION_2) {
        out.v2.id = src.id;
        out.v2.name = src.name.c_str();
        out.v2.type = src.type;
        out.v2.dataFormat = src.dataFormat;
        out.v2.dataType = src.dataType;
        out.v2.quantizeParams = src.quantizeParams;
        out.v2.rank = static_cast<uint32_t>(src.dims.size());
        out.v2.dimensions = src.dims.empty() ? nullptr : src.dims.data();
        out.v2.memType = QNN_TENSORMEMTYPE_RAW;
        out.v2.clientBuf = client;
        out.v2.isDynamicDimensions =
            src.dynamicDims.empty() ? nullptr : src.dynamicDims.data();
        out.v2.sparseParams = src.sparseParams;
        out.v2.isProduced = src.isProduced;
    } else {
        out.v1.id = src.id;
        out.v1.name = src.name.c_str();
        out.v1.type = src.type;
        out.v1.dataFormat = src.dataFormat;
        out.v1.dataType = src.dataType;
        out.v1.quantizeParams = src.quantizeParams;
        out.v1.rank = static_cast<uint32_t>(src.dims.size());
        out.v1.dimensions = src.dims.empty() ? nullptr : src.dims.data();
        out.v1.memType = QNN_TENSORMEMTYPE_RAW;
        out.v1.clientBuf = client;
    }
    return out;
}
#endif  // ONEVOICE_HAS_QNN

}  // namespace

// ---------------------------------------------------------------------------
// State — every SDK handle lives here (pimpl).
// ---------------------------------------------------------------------------
struct QnnRuntime::State {
#if ONEVOICE_HAS_QNN
    void*                 backendLib = nullptr;   // libQnnHtp.so
    void*                 systemLib  = nullptr;   // libQnnSystem.so
    QnnInterface_t        interface{};
    const QnnInterface_t* api = nullptr;
    QnnSystemInterface_t        sysInterface{};
    const QnnSystemInterface_t* sysApi = nullptr;

    Qnn_BackendHandle_t backend = nullptr;
    Qnn_DeviceHandle_t  device  = nullptr;
    bool signedProcessDomain = false;
    int  unsignedPdControlResult = -1;

    // One context per cascade stage; graphs are owned by their context.
    struct Loaded {
        std::string         stage;
        Qnn_ContextHandle_t context = nullptr;
        Qnn_GraphHandle_t   graph   = nullptr;
        std::vector<OwnedTensor> inputs;
        std::vector<OwnedTensor> outputs;
    };
    std::vector<Loaded> models;
    std::mutex executionMutex;

    // ---- Benchmarking state ----------------------------------------------
    // One handle reused for every execute. QNN overwrites a handle's events on
    // the next execute that references it, and the six Hybrid contexts run
    // strictly sequentially under executionMutex, so the events are drained
    // immediately after each graphExecute while they still belong to that
    // stage. Sharing the handle is safe only because of that serialization.
    Qnn_ProfileHandle_t profile = nullptr;
    bool profilingEnabled = false;
    bool profileSchemaLogged = false;
    std::vector<ProfileSample> profileSamples;
    std::mutex profileMutex;
    std::string powerMode = "unset";
    uint32_t perfPowerConfigId = 0;
    bool perfInfraReady = false;
#else
    char placeholder;
#endif
};

QnnRuntime::QnnRuntime() : state_(std::make_unique<State>()) {}
QnnRuntime::~QnnRuntime() { shutdown(); }

std::size_t QnnRuntime::modelCount() const noexcept {
#if ONEVOICE_HAS_QNN
    return state_ ? state_->models.size() : 0;
#else
    return 0;
#endif
}

// ===========================================================================
// initBackend() — dlopen HTP + System, resolve interfaces, create backend+device.
// ===========================================================================
bool QnnRuntime::initBackend(const std::string& backendLibPath,
                             const std::string& systemLibPath,
                             const std::string& adspLibraryPath,
                             bool useSignedProcessDomain) {
#if ONEVOICE_HAS_QNN
    if (ready_.load(std::memory_order_acquire)) return true;  // idempotent
    if (!state_) state_ = std::make_unique<State>();

    // ---- 0. Tell FastRPC where to find the Hexagon-side skel ---------------
    // The HTP backend runs half of itself ON the DSP, loaded from
    // libQnnHtpV73Skel.so. The path may select either an app-extracted skeleton
    // or an OEM production-signed one. Keep the mode and artifact aligned: an
    // unsigned skel belongs to an unsigned PD; a signed skel needs signed PD.
    //
    // Must be set BEFORE libQnnHtp.so is dlopen'd: FastRPC reads it at load.
    if (!adspLibraryPath.empty()) {
        if (setenv("ADSP_LIBRARY_PATH", adspLibraryPath.c_str(), /*overwrite=*/1) != 0) {
            LOGE("setenv(ADSP_LIBRARY_PATH) failed");
            return false;
        }
        LOGI("ADSP_LIBRARY_PATH=%s", adspLibraryPath.c_str());
    }

    state_->signedProcessDomain = useSignedProcessDomain;
    if (!useSignedProcessDomain) {
        state_->unsignedPdControlResult = enableUnsignedPdOffload();
    }

    // ---- 1. HTP backend interface -----------------------------------------
    state_->backendLib = openQnnLib(backendLibPath);
    if (!state_->backendLib) return false;
    using GetProvidersFn = Qnn_ErrorHandle_t (*)(const QnnInterface_t***, uint32_t*);
    auto getProviders = reinterpret_cast<GetProvidersFn>(
        dlsym(state_->backendLib, "QnnInterface_getProviders"));
    if (!getProviders) {
        LOGE("dlsym(QnnInterface_getProviders) failed: %s", dlerror());
        return false;
    }
    const QnnInterface_t** providers = nullptr;
    uint32_t numProviders = 0;
    if (QNN_SUCCESS != getProviders(&providers, &numProviders) ||
        !providers || numProviders == 0) {
        LOGE("QnnInterface_getProviders returned no providers");
        return false;
    }
    const QnnInterface_t* chosen = nullptr;
    for (uint32_t i = 0; i < numProviders; ++i) {
        const Qnn_Version_t& v = providers[i]->apiVersion.coreApiVersion;
        // Same major = ABI-compatible; minor is additive, so >= is safe.
        if (v.major == QNN_API_VERSION_MAJOR && v.minor >= QNN_API_VERSION_MINOR) {
            chosen = providers[i];
            break;
        }
    }
    if (!chosen) {
        LOGE("No QNN provider matches core API v%d.%d",
             QNN_API_VERSION_MAJOR, QNN_API_VERSION_MINOR);
        return false;
    }
    state_->interface = *chosen;   // copy: don't depend on provider-list storage
    state_->api       = &state_->interface;

    // ---- 2. System interface (reads binary info without touching hardware) --
    state_->systemLib = openQnnLib(systemLibPath);
    if (!state_->systemLib) {
        // Non-fatal: we lose pre-flight graph/tensor introspection but can still
        // create contexts. Already logged by openQnnLib.
        LOGW("binary introspection disabled (libQnnSystem.so unavailable)");
    } else {
        using GetSysProvidersFn =
            Qnn_ErrorHandle_t (*)(const QnnSystemInterface_t***, uint32_t*);
        auto getSysProviders = reinterpret_cast<GetSysProvidersFn>(
            dlsym(state_->systemLib, "QnnSystemInterface_getProviders"));
        const QnnSystemInterface_t** sysProviders = nullptr;
        uint32_t numSys = 0;
        if (getSysProviders &&
            QNN_SUCCESS == getSysProviders(&sysProviders, &numSys) &&
            sysProviders && numSys > 0) {
            for (uint32_t i = 0; i < numSys; ++i) {
                const Qnn_Version_t& v = sysProviders[i]->systemApiVersion;
                if (v.major == QNN_SYSTEM_API_VERSION_MAJOR) {
                    state_->sysInterface = *sysProviders[i];
                    state_->sysApi       = &state_->sysInterface;
                    break;
                }
            }
        }
        if (!state_->sysApi) LOGW("No compatible QnnSystemInterface provider");
    }

    // ---- 3. Backend + device ----------------------------------------------
    const auto& qnn = state_->api->QNN_INTERFACE_VER_NAME;
    if (QNN_SUCCESS != qnn.backendCreate(/*logger=*/nullptr,
                                         /*backendConfig=*/nullptr,
                                         &state_->backend)) {
        LOGE("backendCreate failed");
        return false;
    }

    // deviceCreate(logger, config, &device) — there is NO backend parameter.
    // For HTP the signed-PD switch is a DEVICE custom option (not a context
    // option and not QnnDspBackend's similarly-named backend option).
    //
    // Optional by design: on the HTP backend a device handle is only needed to
    // target a specific accelerator, and some builds reject a null config with
    // QNN_DEVICE_ERROR_UNSUPPORTED_FEATURE. contextCreateFromBinary accepts a
    // null device and picks the default one, so a failure here is a warning,
    // not a dead end — the .bin still loads.
    QnnHtpDevice_CustomConfig_t htpConfig{};
    QnnDevice_Config_t deviceConfig = QNN_DEVICE_CONFIG_INIT;
    const QnnDevice_Config_t* deviceConfigs[] = {&deviceConfig, nullptr};
    const QnnDevice_Config_t** requestedConfigs = nullptr;
    if (useSignedProcessDomain) {
        htpConfig.option = QNN_HTP_DEVICE_CONFIG_OPTION_SIGNEDPD;
        htpConfig.useSignedProcessDomain.deviceId = 0;
        htpConfig.useSignedProcessDomain.useSignedProcessDomain = true;
        deviceConfig.option = QNN_DEVICE_CONFIG_OPTION_CUSTOM;
        deviceConfig.customConfig = &htpConfig;
        requestedConfigs = deviceConfigs;
    }

    const Qnn_ErrorHandle_t devErr = qnn.deviceCreate(/*logger=*/nullptr,
                                                      requestedConfigs,
                                                      &state_->device);
    if (devErr != QNN_SUCCESS) {
        LOGW("deviceCreate failed (0x%llx, signedPd=%s)%s",
             static_cast<unsigned long long>(devErr),
             useSignedProcessDomain ? "true" : "false",
             useSignedProcessDomain ? "" : " — continuing with the default device");
        state_->device = nullptr;
        // Falling back to a null device silently reverts to the default
        // unsigned PD. That is useful for old unsigned runtimes, but would
        // defeat an explicit production-signed configuration.
        if (useSignedProcessDomain) return false;
    }

    ready_.store(true, std::memory_order_release);
    LOGI("QNN backend ready (htp=%s system=%s)",
         backendLibPath.c_str(), state_->sysApi ? "yes" : "no");
    return true;
#else
    (void)backendLibPath; (void)systemLibPath; (void)adspLibraryPath;
    (void)useSignedProcessDomain;
    LOGE("QNN SDK headers not vendored; initBackend is a stub.");
    return false;
#endif
}

// ===========================================================================
// loadModel() — introspect the blob, place it on the HTP, retrieve the graph.
// ===========================================================================
ModelLoadResult QnnRuntime::loadModel(const ModelSpec& spec) {
    ModelLoadResult r;
    r.stage = spec.stage;

#if ONEVOICE_HAS_QNN
    const auto t0 = Clock::now();

    if (!ready_.load(std::memory_order_acquire)) {
        r.error = "QNN backend not initialized";
        return r;
    }

    std::vector<uint8_t> blob;
    if (!readFileToBuffer(spec.contextPath, blob)) {
        r.error = "cannot read " + spec.contextPath;
        return r;
    }
    r.sizeBytes = blob.size();

    std::vector<OwnedTensor> ownedInputs;
    std::vector<OwnedTensor> ownedOutputs;

    // ---- Step 1: parse the blob (no hardware involved) ---------------------
    // Also the only place the .bin tells us which SoC it was compiled for, so a
    // wrong-chip artifact is named as such instead of failing opaquely later.
    if (state_->sysApi) {
        const auto& sys = state_->sysApi->QNN_SYSTEM_INTERFACE_VER_NAME;
        QnnSystemContext_Handle_t sysCtx = nullptr;
        if (QNN_SUCCESS == sys.systemContextCreate(&sysCtx) && sysCtx) {
            const QnnSystemContext_BinaryInfo_t* info = nullptr;
            Qnn_ContextBinarySize_t infoSize = 0;
            if (QNN_SUCCESS == sys.systemContextGetBinaryInfo(
                    sysCtx, blob.data(), blob.size(), &info, &infoSize) && info) {
                const QnnSystemContext_GraphInfo_t* graphs = nullptr;
                uint32_t numGraphs = 0;
                if (info->version == QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_3) {
                    graphs      = info->contextBinaryInfoV3.graphs;
                    numGraphs   = info->contextBinaryInfoV3.numGraphs;
                    r.socModel  = info->contextBinaryInfoV3.socModel;
                    r.buildId   = info->contextBinaryInfoV3.buildId
                                    ? info->contextBinaryInfoV3.buildId : "";
                } else if (info->version == QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_2) {
                    graphs     = info->contextBinaryInfoV2.graphs;
                    numGraphs  = info->contextBinaryInfoV2.numGraphs;
                    r.buildId  = info->contextBinaryInfoV2.buildId
                                    ? info->contextBinaryInfoV2.buildId : "";
                } else if (info->version == QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_1) {
                    graphs     = info->contextBinaryInfoV1.graphs;
                    numGraphs  = info->contextBinaryInfoV1.numGraphs;
                    r.buildId  = info->contextBinaryInfoV1.buildId
                                    ? info->contextBinaryInfoV1.buildId : "";
                }
                // Record the contract of the graph we were asked for. Falls back
                // to the first graph when the name does not match, so the error
                // message below can say what the binary actually contains.
                for (uint32_t i = 0; i < numGraphs && graphs; ++i) {
                    const auto& g = graphs[i];
                    const char*          gname = nullptr;
                    uint32_t             nIn = 0, nOut = 0;
                    const Qnn_Tensor_t*  in = nullptr;
                    const Qnn_Tensor_t*  out = nullptr;
                    if (g.version == QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_3) {
                        gname = g.graphInfoV3.graphName;
                        nIn = g.graphInfoV3.numGraphInputs;   in  = g.graphInfoV3.graphInputs;
                        nOut = g.graphInfoV3.numGraphOutputs; out = g.graphInfoV3.graphOutputs;
                    } else if (g.version == QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_2) {
                        gname = g.graphInfoV2.graphName;
                        nIn = g.graphInfoV2.numGraphInputs;   in  = g.graphInfoV2.graphInputs;
                        nOut = g.graphInfoV2.numGraphOutputs; out = g.graphInfoV2.graphOutputs;
                    } else if (g.version == QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_1) {
                        gname = g.graphInfoV1.graphName;
                        nIn = g.graphInfoV1.numGraphInputs;   in  = g.graphInfoV1.graphInputs;
                        nOut = g.graphInfoV1.numGraphOutputs; out = g.graphInfoV1.graphOutputs;
                    }
                    const std::string found = gname ? gname : "";
                    const bool wanted = (found == spec.graphName);
                    if (wanted || r.graphName.empty()) {
                        r.graphName = found;
                        r.inputs.clear();
                        r.outputs.clear();
                        ownedInputs.clear();
                        ownedOutputs.clear();
                        std::string tensorError;
                        for (uint32_t k = 0; k < nIn && in; ++k) {
                            r.inputs.push_back(toTensorInfo(in[k]));
                            ownedInputs.emplace_back();
                            if (!copyTensor(in[k], ownedInputs.back(), tensorError)) break;
                        }
                        if (tensorError.empty()) {
                            for (uint32_t k = 0; k < nOut && out; ++k) {
                                r.outputs.push_back(toTensorInfo(out[k]));
                                ownedOutputs.emplace_back();
                                if (!copyTensor(out[k], ownedOutputs.back(), tensorError)) break;
                            }
                        }
                        if (!tensorError.empty()) {
                            r.error = tensorError;
                            ownedInputs.clear();
                            ownedOutputs.clear();
                        }
                    }
                    if (wanted) break;
                }
            }
            sys.systemContextFree(sysCtx);
        }
    }

    if (!r.error.empty()) return r;
    if (ownedInputs.empty() || ownedOutputs.empty()) {
        r.error = "context binary did not expose executable tensor metadata";
        return r;
    }

    // ---- Step 2: place the graphs on the HTP -------------------------------
    const auto& qnn = state_->api->QNN_INTERFACE_VER_NAME;
    Qnn_ContextHandle_t context = nullptr;
    const Qnn_ErrorHandle_t ctxErr =
        qnn.contextCreateFromBinary(state_->backend,
                                    state_->device,
                                    /*contextConfig=*/nullptr,
                                    blob.data(), blob.size(),
                                    &context, /*profile=*/nullptr);
    if (ctxErr != QNN_SUCCESS) {
        // A DEVICE-domain error tells us which API component rejected the call,
        // not why. In particular 0x36b1 is QNN_DEVICE_ERROR_INVALID_CONFIG; it
        // does not by itself prove a signature-policy failure.
        const bool deviceDomain = ctxErr >= 14000 && ctxErr < 15000;
        char code[32];
        snprintf(code, sizeof(code), "0x%llx", static_cast<unsigned long long>(ctxErr));
        if (deviceDomain) {
            r.error = std::string("contextCreateFromBinary failed (") + code +
                      ", QNN device config rejected; mode=" +
                      (state_->signedProcessDomain ? "signed" : "unsigned");
            if (!state_->signedProcessDomain) {
                r.error += ", FastRPC unsigned-control rc=" +
                           std::to_string(state_->unsignedPdControlResult);
            }
            r.error += ". Check libcdsprpc visibility, FastRPC logs, the extracted "
                       "skel/testsig, and QNN stub-skel version compatibility. "
                       "This code alone does not prove a signing-policy failure.";
        } else {
            r.error = std::string("contextCreateFromBinary failed (") + code +
                      ") — verify context-binary HTP arch and QAIRT runtime version";
        }
        return r;
    }

    // ---- Step 3: confirm graph identity ------------------------------------
    Qnn_GraphHandle_t graph = nullptr;
    const Qnn_ErrorHandle_t graphErr =
        qnn.graphRetrieve(context, spec.graphName.c_str(), &graph);
    if (graphErr != QNN_SUCCESS) {
        char code[32];
        snprintf(code, sizeof(code), "0x%llx", static_cast<unsigned long long>(graphErr));
        r.error = "graphRetrieve(\"" + spec.graphName + "\") failed (" + code + ")" +
                  (r.graphName.empty() ? "" : "; binary contains \"" + r.graphName + "\"");
        qnn.contextFree(context, /*profile=*/nullptr);
        return r;
    }

    {
        std::lock_guard<std::mutex> lock(state_->executionMutex);
        state_->models.push_back(
            {spec.stage, context, graph, std::move(ownedInputs), std::move(ownedOutputs)});
    }
    r.ok        = true;
    r.graphName = spec.graphName;
    r.loadMs    = msSince(t0);
    LOGI("loaded %s: graph=%s %.1f MB in %.0f ms (soc=%u build=%s)",
         spec.stage.c_str(), spec.graphName.c_str(),
         static_cast<double>(r.sizeBytes) / (1024.0 * 1024.0), r.loadMs,
         r.socModel, r.buildId.c_str());
    return r;
#else
    (void)spec;
    r.error = "built without the QNN SDK";
    return r;
#endif
}

#if ONEVOICE_HAS_QNN
namespace {

uint64_t monotonicUs() {
    // CLOCK_MONOTONIC, not wall clock: NTP steps and suspend would otherwise
    // show up as negative or absurd stage latencies.
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<uint64_t>(ts.tv_sec) * 1000000ull +
           static_cast<uint64_t>(ts.tv_nsec) / 1000ull;
}

const char* profileEventName(uint32_t type) {
    switch (type) {
        case QNN_PROFILE_EVENTTYPE_EXECUTE:                return "EXECUTE";
        case QNN_PROFILE_EVENTTYPE_EXECUTE_QUEUE_WAIT:     return "EXECUTE_QUEUE_WAIT";
        case QNN_PROFILE_EVENTTYPE_EXECUTE_PREPROCESS:     return "EXECUTE_PREPROCESS";
        case QNN_PROFILE_EVENTTYPE_EXECUTE_DEVICE:         return "EXECUTE_DEVICE";
        case QNN_PROFILE_EVENTTYPE_EXECUTE_POSTPROCESS:    return "EXECUTE_POSTPROCESS";
        case QNN_HTP_PROFILE_EVENTTYPE_GRAPH_EXECUTE_HOST_RPC_TIME_MICROSEC:
            return "HTP_HOST_RPC_US";
        case QNN_HTP_PROFILE_EVENTTYPE_GRAPH_EXECUTE_HTP_RPC_TIME_MICROSEC:
            return "HTP_RPC_US";
        case QNN_HTP_PROFILE_EVENTTYPE_GRAPH_EXECUTE_ACCEL_TIME_CYCLE:
            return "HTP_ACCEL_CYCLES";
        case QNN_HTP_PROFILE_EVENTTYPE_GRAPH_EXECUTE_ACCEL_TIME_MICROSEC:
            return "HTP_ACCEL_US";
        case QNN_HTP_PROFILE_EVENTTYPE_GRAPH_EXECUTE_ACCEL_EXCL_WAIT_TIME_MICROSEC:
            return "HTP_ACCEL_EXCL_WAIT_US";
        default: return "OTHER";
    }
}

const char* profileUnitName(uint32_t unit) {
    switch (unit) {
        case QNN_PROFILE_EVENTUNIT_MICROSEC: return "us";
        case QNN_PROFILE_EVENTUNIT_BYTES:    return "bytes";
        case QNN_PROFILE_EVENTUNIT_CYCLES:   return "cycles";
        case QNN_PROFILE_EVENTUNIT_COUNT:    return "count";
        case QNN_PROFILE_EVENTUNIT_OBJECT:   return "object";
        case QNN_PROFILE_EVENTUNIT_NONE:     return "none";
        default: return "?";
    }
}

}  // namespace
#endif

#if ONEVOICE_HAS_QNN
/**
 * Fold one event (and its sub-events) into the sample being built.
 *
 * The interesting numbers are nested: EXECUTE (400) is the top-level event and
 * the accelerator time hangs off it as a sub-event, so a flat pass over
 * getEvents() alone reports nothing useful. Both the generic
 * EXECUTE_DEVICE (407) and the HTP-specific ACCEL_TIME_MICROSEC (3004) are
 * accepted because which one a given backend build emits is not guaranteed;
 * 3004 wins when both are present since it is the vendor's own measurement.
 *
 * `logSchema` dumps identifier/type/unit once per process so the raw contract
 * can be checked against the header instead of trusted.
 */
void QnnRuntime::foldProfileEvent(uint64_t eventId, ProfileSample& sample,
                                  bool logSchema, int depth) {
    const auto& qnn = state_->api->QNN_INTERFACE_VER_NAME;
    QnnProfile_EventData_t data = QNN_PROFILE_EVENT_DATA_INIT;
    if (qnn.profileGetEventData(eventId, &data) != QNN_SUCCESS) return;

    if (logSchema) {
        LOGI("profile[%d] type=%u(%s) unit=%u(%s) value=%llu id=%s",
             depth, data.type, profileEventName(data.type),
             data.unit, profileUnitName(data.unit),
             static_cast<unsigned long long>(data.value),
             data.identifier ? data.identifier : "(null)");
    }

    // Only microsecond events are folded into timing fields; cycle counts need
    // a clock rate that DVFS makes unknowable from here.
    if (data.unit == QNN_PROFILE_EVENTUNIT_MICROSEC) {
        switch (data.type) {
            case QNN_PROFILE_EVENTTYPE_EXECUTE:
                sample.executeUs = data.value;
                break;
            case QNN_PROFILE_EVENTTYPE_EXECUTE_QUEUE_WAIT:
                sample.queueWaitUs = data.value;
                break;
            case QNN_PROFILE_EVENTTYPE_EXECUTE_DEVICE:
                if (sample.acceleratorUs == 0) sample.acceleratorUs = data.value;
                break;
            case QNN_HTP_PROFILE_EVENTTYPE_GRAPH_EXECUTE_ACCEL_TIME_MICROSEC:
                sample.acceleratorUs = data.value;  // vendor value wins
                break;
            default:
                break;
        }
    }

    if (depth >= 2) return;  // EXECUTE -> sub-event is the only nesting we need
    const QnnProfile_EventId_t* subIds = nullptr;
    uint32_t subCount = 0;
    if (qnn.profileGetSubEvents(eventId, &subIds, &subCount) != QNN_SUCCESS ||
        subIds == nullptr) {
        return;
    }
    for (uint32_t i = 0; i < subCount; ++i) {
        foldProfileEvent(subIds[i], sample, logSchema, depth + 1);
    }
}

void QnnRuntime::collectProfile(const std::string& stage, uint64_t wallUs) {
    const auto& qnn = state_->api->QNN_INTERFACE_VER_NAME;
    ProfileSample sample;
    sample.stage  = stage;
    sample.wallUs = wallUs;

    const QnnProfile_EventId_t* eventIds = nullptr;
    uint32_t eventCount = 0;
    if (qnn.profileGetEvents(state_->profile, &eventIds, &eventCount) == QNN_SUCCESS &&
        eventIds != nullptr) {
        const bool logSchema = !state_->profileSchemaLogged;
        if (logSchema) {
            LOGI("profile schema dump for stage=%s (%u top-level events)",
                 stage.c_str(), eventCount);
        }
        for (uint32_t i = 0; i < eventCount; ++i) {
            foldProfileEvent(eventIds[i], sample, logSchema, 0);
        }
        state_->profileSchemaLogged = true;
        sample.profiled = true;
    }

    std::lock_guard<std::mutex> lock(state_->profileMutex);
    // Bounded so a forgotten drain cannot grow without limit during a soak.
    if (state_->profileSamples.size() < 4096) {
        state_->profileSamples.push_back(std::move(sample));
    }
}
#endif

#if !ONEVOICE_HAS_QNN
// Same symbols, no-op bodies: the class layout and link surface must not depend
// on whether the SDK was present at compile time.
void QnnRuntime::collectProfile(const std::string&, uint64_t) {}
void QnnRuntime::foldProfileEvent(uint64_t, ProfileSample&, bool, int) {}
#endif

bool QnnRuntime::setProfilingEnabled(bool enabled) {
#if ONEVOICE_HAS_QNN
    if (!state_ || !state_->api || !state_->backend) return false;
    const auto& qnn = state_->api->QNN_INTERFACE_VER_NAME;
    if (enabled == state_->profilingEnabled) return true;
    if (enabled) {
        // BASIC only. DETAILED emits a per-node event stream whose collection
        // cost distorts the very latency it is supposed to explain, so it is
        // reserved for one-off diagnosis runs, never for gate numbers.
        Qnn_ProfileHandle_t handle = nullptr;
        const auto err = qnn.profileCreate(state_->backend,
                                           QNN_PROFILE_LEVEL_BASIC, &handle);
        if (err != QNN_SUCCESS || handle == nullptr) {
            LOGW("profileCreate failed (0x%llx); profiling stays off",
                 static_cast<unsigned long long>(err));
            return false;
        }
        state_->profile = handle;
        state_->profilingEnabled = true;
        LOGI("QNN profiling enabled (BASIC)");
    } else {
        if (state_->profile) qnn.profileFree(state_->profile);
        state_->profile = nullptr;
        state_->profilingEnabled = false;
        LOGI("QNN profiling disabled");
    }
    return true;
#else
    (void)enabled;
    return false;
#endif
}

bool QnnRuntime::profilingEnabled() const noexcept {
#if ONEVOICE_HAS_QNN
    return state_ && state_->profilingEnabled;
#else
    return false;
#endif
}

std::vector<QnnRuntime::ProfileSample> QnnRuntime::drainProfile() {
#if ONEVOICE_HAS_QNN
    if (!state_) return {};
    std::lock_guard<std::mutex> lock(state_->profileMutex);
    std::vector<ProfileSample> out;
    out.swap(state_->profileSamples);
    return out;
#else
    return {};
#endif
}

bool QnnRuntime::applyPowerConfig(const PowerConfig& config, std::string& error) {
    error.clear();
#if ONEVOICE_HAS_QNN
    if (!ready_.load(std::memory_order_acquire) || !state_ || !state_->api) {
        error = "backend is not ready";
        return false;
    }
    const auto& qnn = state_->api->QNN_INTERFACE_VER_NAME;

    QnnDevice_Infrastructure_t infra = nullptr;
    const auto infraErr = qnn.deviceGetInfrastructure(&infra);
    if (infraErr != QNN_SUCCESS || infra == nullptr) {
        error = "deviceGetInfrastructure failed";
        return false;
    }
    auto* htpInfra = static_cast<QnnHtpDevice_Infrastructure_t*>(infra);
    if (htpInfra->infraType != QNN_HTP_DEVICE_INFRASTRUCTURE_TYPE_PERF) {
        error = "device infrastructure is not the perf type";
        return false;
    }
    auto& perf = htpInfra->perfInfra;
    if (!perf.createPowerConfigId || !perf.setPowerConfig) {
        error = "perf infrastructure entry points are missing";
        return false;
    }

    if (!state_->perfInfraReady) {
        // deviceId/coreId 0: this SoC exposes a single NSP, and the manifest
        // already pins the target to SM8550.
        const auto idErr = perf.createPowerConfigId(0, 0, &state_->perfPowerConfigId);
        if (idErr != QNN_SUCCESS) {
            error = "createPowerConfigId failed";
            return false;
        }
        state_->perfInfraReady = true;
    }

    // Voltage corner target per requested mode. "burst" pins the core to TURBO
    // and disables sleep so DVFS cannot ramp mid-measurement; without this the
    // first executes of a run are systematically slower than the rest and the
    // p95 reflects the governor rather than the model.
    QnnHtpPerfInfrastructure_VoltageCorner_t corner = DCVS_VOLTAGE_VCORNER_TURBO;
    uint32_t dcvsEnable = 0;
    if (config.mode == "burst") {
        corner = DCVS_VOLTAGE_VCORNER_TURBO;
        dcvsEnable = 0;                    // hold the vote, do not scale
    } else if (config.mode == "sustained_high_performance") {
        corner = DCVS_VOLTAGE_VCORNER_NOM_PLUS;
        dcvsEnable = 1;
    } else if (config.mode == "balanced") {
        corner = DCVS_VOLTAGE_VCORNER_SVS_PLUS;
        dcvsEnable = 1;
    } else {
        error = "unknown power mode: " + config.mode;
        return false;
    }

    QnnHtpPerfInfrastructure_PowerConfig_t dcvs{};
    dcvs.option = QNN_HTP_PERF_INFRASTRUCTURE_POWER_CONFIGOPTION_DCVS_V3;
    dcvs.dcvsV3Config.contextId = state_->perfPowerConfigId;
    dcvs.dcvsV3Config.setDcvsEnable = 1;
    dcvs.dcvsV3Config.dcvsEnable = dcvsEnable;
    dcvs.dcvsV3Config.powerMode =
        QNN_HTP_PERF_INFRASTRUCTURE_POWERMODE_PERFORMANCE_MODE;
    dcvs.dcvsV3Config.setSleepDisable = 1;
    dcvs.dcvsV3Config.sleepDisable = (config.mode == "burst") ? 1 : 0;
    dcvs.dcvsV3Config.setSleepLatency = 1;
    dcvs.dcvsV3Config.sleepLatency = config.rpcControlLatencyUs;
    dcvs.dcvsV3Config.setBusParams = 1;
    dcvs.dcvsV3Config.busVoltageCornerMin = corner;
    dcvs.dcvsV3Config.busVoltageCornerTarget = corner;
    dcvs.dcvsV3Config.busVoltageCornerMax = corner;
    dcvs.dcvsV3Config.setCoreParams = 1;
    dcvs.dcvsV3Config.coreVoltageCornerMin = corner;
    dcvs.dcvsV3Config.coreVoltageCornerTarget = corner;
    dcvs.dcvsV3Config.coreVoltageCornerMax = corner;

    QnnHtpPerfInfrastructure_PowerConfig_t latency{};
    latency.option =
        QNN_HTP_PERF_INFRASTRUCTURE_POWER_CONFIGOPTION_RPC_CONTROL_LATENCY;
    latency.rpcControlLatencyConfig = config.rpcControlLatencyUs;

    QnnHtpPerfInfrastructure_PowerConfig_t polling{};
    polling.option =
        QNN_HTP_PERF_INFRASTRUCTURE_POWER_CONFIGOPTION_RPC_POLLING_TIME;
    polling.rpcPollingTimeConfig = config.rpcPollingTimeUs;

    const QnnHtpPerfInfrastructure_PowerConfig_t* configs[] = {
        &dcvs, &latency, &polling, nullptr,
    };
    const auto setErr = perf.setPowerConfig(state_->perfPowerConfigId, configs);
    if (setErr != QNN_SUCCESS) {
        char code[32];
        snprintf(code, sizeof(code), "0x%llx",
                 static_cast<unsigned long long>(setErr));
        error = std::string("setPowerConfig failed (") + code + ")";
        return false;
    }

    state_->powerMode = config.mode;
    LOGI("HTP power config applied mode=%s rpcControlLatencyUs=%u rpcPollingUs=%u",
         config.mode.c_str(), config.rpcControlLatencyUs, config.rpcPollingTimeUs);
    return true;
#else
    (void)config;
    error = "built without the QNN SDK";
    return false;
#endif
}

const std::string& QnnRuntime::activePowerMode() const noexcept {
    static const std::string kUnavailable = "unavailable";
#if ONEVOICE_HAS_QNN
    return state_ ? state_->powerMode : kUnavailable;
#else
    return kUnavailable;
#endif
}

bool QnnRuntime::executeGraph(const std::string& stage,
                              const std::vector<GraphBuffer>& inputs,
                              const std::vector<GraphBuffer>& outputs,
                              std::string& error) {
    error.clear();
#if ONEVOICE_HAS_QNN
    if (!ready_.load(std::memory_order_acquire) || !state_ || !state_->api) {
        error = "QNN runtime is not initialized";
        return false;
    }
    std::lock_guard<std::mutex> lock(state_->executionMutex);
    auto it = std::find_if(state_->models.begin(), state_->models.end(),
                           [&](const State::Loaded& loaded) { return loaded.stage == stage; });
    if (it == state_->models.end()) {
        error = "QNN stage is not loaded: " + stage;
        return false;
    }
    if (inputs.size() != it->inputs.size() || outputs.size() != it->outputs.size()) {
        error = "tensor count mismatch for " + stage + ": got " +
                std::to_string(inputs.size()) + "/" + std::to_string(outputs.size()) +
                ", expected " + std::to_string(it->inputs.size()) + "/" +
                std::to_string(it->outputs.size());
        return false;
    }

    std::vector<Qnn_Tensor_t> qnnInputs;
    std::vector<Qnn_Tensor_t> qnnOutputs;
    qnnInputs.reserve(inputs.size());
    qnnOutputs.reserve(outputs.size());
    auto bind = [&](std::vector<OwnedTensor>& metadata,
                    const std::vector<GraphBuffer>& buffers,
                    std::vector<Qnn_Tensor_t>& tensors,
                    const char* kind) -> bool {
        for (std::size_t i = 0; i < metadata.size(); ++i) {
            std::size_t expected = 0;
            if (!tensorBytes(metadata[i], expected)) {
                error = std::string("unsupported ") + kind + " tensor type/shape: " +
                        metadata[i].name;
                return false;
            }
            if (!buffers[i].data || buffers[i].sizeBytes < expected) {
                error = std::string(kind) + " buffer " + metadata[i].name + " has " +
                        std::to_string(buffers[i].sizeBytes) + " bytes, needs " +
                        std::to_string(expected);
                return false;
            }
            GraphBuffer exact{buffers[i].data, expected};
            tensors.push_back(executionTensor(metadata[i], exact));
        }
        return true;
    };
    if (!bind(it->inputs, inputs, qnnInputs, "input") ||
        !bind(it->outputs, outputs, qnnOutputs, "output")) return false;

    const auto& qnn = state_->api->QNN_INTERFACE_VER_NAME;
    // Passing the handle costs measurable time inside graphExecute, which is
    // why the latency baseline cohort runs with profiling never enabled.
    Qnn_ProfileHandle_t profileHandle =
        state_->profilingEnabled ? state_->profile : nullptr;
    const uint64_t startUs = profileHandle ? monotonicUs() : 0;
    const Qnn_ErrorHandle_t executeError = qnn.graphExecute(
        it->graph,
        qnnInputs.data(), static_cast<uint32_t>(qnnInputs.size()),
        qnnOutputs.data(), static_cast<uint32_t>(qnnOutputs.size()),
        profileHandle, /*signal=*/nullptr);
    if (profileHandle && executeError == QNN_SUCCESS) {
        // Drain immediately: the next graphExecute on this handle overwrites
        // the events, and the six Hybrid contexts execute back to back.
        collectProfile(stage, monotonicUs() - startUs);
    }
    if (executeError != QNN_SUCCESS) {
        char code[32];
        snprintf(code, sizeof(code), "0x%llx",
                 static_cast<unsigned long long>(executeError));
        error = "graphExecute(" + stage + ") failed (" + code + ")";
        return false;
    }
    return true;
#else
    (void)stage; (void)inputs; (void)outputs;
    error = "built without the QNN SDK";
    return false;
#endif
}

void QnnRuntime::releaseModels() {
#if ONEVOICE_HAS_QNN
    if (!state_ || !state_->api) return;
    std::lock_guard<std::mutex> lock(state_->executionMutex);
    const auto& qnn = state_->api->QNN_INTERFACE_VER_NAME;
    for (auto& m : state_->models) {
        if (m.context) qnn.contextFree(m.context, /*profile=*/nullptr);
    }
    state_->models.clear();
#endif
}

bool QnnRuntime::executeCascade(const int16_t* input, std::size_t sampleCount,
                                std::vector<int16_t>& output) {
    (void)input;
    (void)sampleCount;
    output.clear();
    // The live Kotlin pipeline uses executeGraph() stage-by-stage. Keep this
    // obsolete single-call ABI honest instead of pretending to run it.
    return false;
}

// ---------------------------------------------------------------------------
// shutdown() — release in reverse order of creation. Idempotent.
// ---------------------------------------------------------------------------
void QnnRuntime::shutdown() {
    ready_.store(false, std::memory_order_release);
#if ONEVOICE_HAS_QNN
    if (state_) {
        releaseModels();
        if (state_->api) {
            const auto& qnn = state_->api->QNN_INTERFACE_VER_NAME;
            if (state_->device)  qnn.deviceFree(state_->device);
            if (state_->backend) qnn.backendFree(state_->backend);
        }
        if (state_->systemLib)  dlclose(state_->systemLib);
        if (state_->backendLib) dlclose(state_->backendLib);
    }
#endif
    state_.reset();
}

}  // namespace onevoice
