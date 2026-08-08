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

#include <chrono>
#include <cstdlib>
#include <fstream>
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
  #include <QnnTensor.h>
  #include <QnnTypes.h>
  #include <HTP/QnnHtpDevice.h>
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
    };
    std::vector<Loaded> models;
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
                        for (uint32_t k = 0; k < nIn && in; ++k)
                            r.inputs.push_back(toTensorInfo(in[k]));
                        for (uint32_t k = 0; k < nOut && out; ++k)
                            r.outputs.push_back(toTensorInfo(out[k]));
                    }
                    if (wanted) break;
                }
            }
            sys.systemContextFree(sysCtx);
        }
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

    state_->models.push_back({spec.stage, context, graph});
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

void QnnRuntime::releaseModels() {
#if ONEVOICE_HAS_QNN
    if (!state_ || !state_->api) return;
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
    // See the header: the host-glue that would drive these graphs does not
    // exist for Android yet. Returning false keeps the failure honest.
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
