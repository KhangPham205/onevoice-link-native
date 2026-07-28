// ============================================================================
// qnn_inference.cpp — Qualcomm QNN / Hexagon HTP execution
// ----------------------------------------------------------------------------
// Compiles against the real QNN SDK when its headers are vendored under
// third_party/qnn_sdk/include/QNN. When they are absent, every method degrades
// to a logged stub via __has_include — so the core library still builds and the
// JNI/wiring can be exercised on a desktop toolchain before HTP bring-up.
//
// The concrete steps (backendCreate -> deviceCreate -> contextCreateFromBinary
// -> graphRetrieve -> graphExecute) follow the canonical QNN C API; tensor
// mapping and QnnMem zero-copy are left as clearly-marked placeholders because
// their exact shapes/quantization are model-specific (owned by the AI team).
// ============================================================================

#include "qnn_inference.h"

#include <android/log.h>
#include <dlfcn.h>           // dlopen / dlsym

#include <fstream>
#include <vector>

#define LOG_TAG "OneVoiceQNN"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ---------------------------------------------------------------------------
// Conditionally pull in the real QNN headers. ONEVOICE_HAS_QNN gates every
// SDK-specific block below so the file is always compilable.
// ---------------------------------------------------------------------------
#if __has_include(<QnnInterface.h>)
  #define ONEVOICE_HAS_QNN 1
  #include <QnnBackend.h>
  #include <QnnCommon.h>
  #include <QnnContext.h>
  #include <QnnDevice.h>
  #include <QnnGraph.h>
  #include <QnnInterface.h>
  #include <QnnMem.h>
  #include <QnnTensor.h>
  #include <QnnTypes.h>
#else
  #define ONEVOICE_HAS_QNN 0
#endif

namespace onevoice {

namespace {
// Read a whole file (the .bin context binary) into memory.
bool readFileToBuffer(const std::string& path, std::vector<uint8_t>& out) {
    std::ifstream f(path, std::ios::binary | std::ios::ate);
    if (!f) return false;
    const std::streamsize size = f.tellg();
    if (size <= 0) return false;
    f.seekg(0, std::ios::beg);
    out.resize(static_cast<std::size_t>(size));
    return static_cast<bool>(f.read(reinterpret_cast<char*>(out.data()), size));
}
}  // namespace

// ---------------------------------------------------------------------------
// QnnState — all SDK-specific handles live here (pimpl).
// ---------------------------------------------------------------------------
struct QnnInference::QnnState {
#if ONEVOICE_HAS_QNN
    void*                  backendLib  = nullptr;   // dlopen handle (libQnnHtp.so)
    QnnInterface_t         interface   {};          // versioned container
    const QnnInterface_t*  api         = nullptr;   // -> function table (QNN_INTERFACE_VER_NAME)
    Qnn_BackendHandle_t    backend     = nullptr;
    Qnn_DeviceHandle_t     device      = nullptr;
    Qnn_ContextHandle_t    context     = nullptr;
    Qnn_GraphHandle_t      graph       = nullptr;

    std::vector<Qnn_Tensor_t> inputTensors;         // populated in mapTensors_()
    std::vector<Qnn_Tensor_t> outputTensors;

    Qnn_MemHandle_t        inputMem    = nullptr;   // zero-copy ION handle
    Qnn_MemHandle_t        outputMem   = nullptr;
#else
    char placeholder;   // keep the struct non-empty so sizeof is well-defined
#endif
};

QnnInference::QnnInference() : state_(std::make_unique<QnnState>()) {}
QnnInference::~QnnInference() { shutdown(); }

// ===========================================================================
// init() — ordered setup. Any failure tears down what was built so far.
// ===========================================================================
bool QnnInference::init(const InferenceConfig& cfg) {
    cfg_ = cfg;

    if (!loadBackend_())              { LOGE("loadBackend failed");              return false; }
    if (!createContextFromBinary_())  { LOGE("createContextFromBinary failed");  shutdown(); return false; }
    if (!retrieveGraph_())            { LOGE("retrieveGraph failed");            shutdown(); return false; }
    if (!mapTensors_())               { LOGE("mapTensors failed");               shutdown(); return false; }
    if (!registerZeroCopyMem_())      { LOGE("registerZeroCopyMem failed");      shutdown(); return false; }

    ready_.store(true, std::memory_order_release);
    LOGI("QNN inference ready: backend=%s graph=%s chunk=%d samples",
         cfg_.backendLibPath.c_str(), cfg_.graphName.c_str(),
         static_cast<int>(cfg_.inputSampleCount));
    return true;
}

// ---------------------------------------------------------------------------
// loadBackend_() — dlopen the HTP backend and retrieve the function table.
// ---------------------------------------------------------------------------
bool QnnInference::loadBackend_() {
#if ONEVOICE_HAS_QNN
    state_->backendLib = dlopen(cfg_.backendLibPath.c_str(), RTLD_NOW | RTLD_GLOBAL);
    if (!state_->backendLib) {
        LOGE("dlopen(\"%s\") failed: %s", cfg_.backendLibPath.c_str(), dlerror());
        return false;
    }
    // The backend exports QnnInterface_getProviders(), which returns a *list* of
    // interface providers. We must pick the one whose core API version matches
    // the headers we compiled against, because that is the union member
    // (QNN_INTERFACE_VER_NAME) we read the function table from.
    using GetProvidersFn =
        Qnn_ErrorHandle_t (*)(const QnnInterface_t***, uint32_t*);
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
        const Qnn_Version_t& ver = providers[i]->apiVersion.coreApiVersion;
        // Same major = ABI-compatible; minor is additive, so >= is safe.
        if (ver.major == QNN_API_VERSION_MAJOR &&
            ver.minor >= QNN_API_VERSION_MINOR) {
            chosen = providers[i];
            break;
        }
    }
    if (!chosen) {
        LOGE("No QNN provider matches core API v%d.%d",
             QNN_API_VERSION_MAJOR, QNN_API_VERSION_MINOR);
        return false;
    }
    // Copy the selected interface so our api pointer does not depend on the
    // provider-list storage remaining valid.
    state_->interface = *chosen;
    state_->api = &state_->interface;

    if (QNN_SUCCESS != state_->api->QNN_INTERFACE_VER_NAME.backendCreate(
            /*logHandle=*/nullptr,
            /*backendConfig=*/nullptr,
            &state_->backend)) {
        LOGE("backendCreate failed");
        return false;
    }
    return true;
#else
    LOGE("QNN SDK headers not vendored; loadBackend_ is a stub. "
         "Place headers under third_party/qnn_sdk/include/QNN to enable HTP.");
    return false;
#endif
}

// ---------------------------------------------------------------------------
// createContextFromBinary_() — bring up the device, then bake the .bin context.
// This is the step that loads the pre-compiled graphs straight onto the HTP,
// skipping on-device compilation (zero warm-up cost after first load).
// ---------------------------------------------------------------------------
bool QnnInference::createContextFromBinary_() {
#if ONEVOICE_HAS_QNN
    // deviceCreate(logger, config, &device) — there is NO backend parameter;
    // passing state_->backend here (as the original code did) silently aliased
    // the backend handle into the logger slot (both are void*).
    if (QNN_SUCCESS != state_->api->QNN_INTERFACE_VER_NAME.deviceCreate(
            /*logger=*/nullptr, /*deviceConfig=*/nullptr, &state_->device)) {
        LOGE("deviceCreate failed");
        return false;
    }

    std::vector<uint8_t> blob;
    if (!readFileToBuffer(cfg_.contextBinaryPath, blob)) {
        LOGE("Failed to read context binary: %s", cfg_.contextBinaryPath.c_str());
        return false;
    }

    // contextCreateFromBinary(backend, device, config, binary, size, context, profile).
    // The original code shifted the arguments: it passed device into the backend
    // slot and nullptr into the device slot (all handles are void*, so it compiled).
    if (QNN_SUCCESS != state_->api->QNN_INTERFACE_VER_NAME.contextCreateFromBinary(
            state_->backend,
            state_->device,
            /*contextConfig=*/nullptr,
            blob.data(),
            blob.size(),
            &state_->context,
            /*profile=*/nullptr)) {
        LOGE("contextCreateFromBinary failed");
        return false;
    }
    return true;
#else
    (void)cfg_;
    return false;
#endif
}

// ---------------------------------------------------------------------------
// retrieveGraph_() — get the handle to the named graph inside the context.
// ---------------------------------------------------------------------------
bool QnnInference::retrieveGraph_() {
#if ONEVOICE_HAS_QNN
    if (QNN_SUCCESS != state_->api->QNN_INTERFACE_VER_NAME.graphRetrieve(
            state_->context, cfg_.graphName.c_str(), &state_->graph)) {
        LOGE("graphRetrieve(\"%s\") failed", cfg_.graphName.c_str());
        return false;
    }
    return true;
#else
    (void)cfg_;
    return false;
#endif
}

// ---------------------------------------------------------------------------
// mapTensors_() — bind .dlc/.bin tensor names to static shapes.
// PLACEHOLDER: the AI team owns quantization (INT8) and bakes padding +
// attention mask into the model, so only the raw-audio input and the
// synthesized-PCM output need binding here.
// ---------------------------------------------------------------------------
bool QnnInference::mapTensors_() {
#if ONEVOICE_HAS_QNN
    // TODO(model): query graph tensor metadata and build Qnn_Tensor_t entries.
    //
    // Intended flow:
    //   QnnGraph_getGraphInfo(state_->graph, ...)  -> per-tensor names, dims,
    //                                                quantizeParams (scale/offset)
    //   For each input tensor:
    //     Qnn_Tensor_t t{};
    //     t.v1.name           = "audio_input";          // must match .dlc name
    //     t.v1.type           = QNN_TENSOR_TYPE_APP_WRITE;
    //     t.v1.dataFormat     = QNN_TENSOR_DATA_FORMAT_FLAT_BUFFER;
    //     t.v1.dataType       = QNN_DATATYPE_UFIXED_POINT_8;  // INT8 quant
    //     t.v1.quantizeParams = { ...from .dlc... };
    //     t.v1.dimensions     = {1, cfg_.inputSampleCount};  // static, no padding
    //     state_->inputTensors.push_back(t);
    //   For each output tensor: type = QNN_TENSOR_TYPE_APP_READ, ...
    //
    // Until wired, leave the vectors empty; execute() will short-circuit below.
    LOGW("mapTensors_() is a placeholder — bind real .dlc/.bin tensor names.");
    return true;
#else
    return false;
#endif
}

// ---------------------------------------------------------------------------
// registerZeroCopyMem_() — register ION/FastRPC buffers with QnnMem.
// PLACEHOLDER: this is the zero-copy optimization. When wired, the HTP reads
// input tensor data directly out of the registered ION buffer (DMA), avoiding
// a memcpy per execute().
// ---------------------------------------------------------------------------
bool QnnInference::registerZeroCopyMem_() {
#if ONEVOICE_HAS_QNN
    // TODO(perf): allocate ION/FastRPC memory (via libcdsprpc / ion allocator),
    // wrap it in Qnn_MemDescriptor_t and register:
    //
    //   Qnn_MemDescriptor_t desc{};
    //   desc.memShape   = {1, cfg_.inputSampleCount};
    //   desc.dataFormat = QNN_TENSOR_DATA_FORMAT_FLAT_BUFFER;
    //   desc.dataType   = QNN_DATATYPE_UFIXED_POINT_8;
    //   desc.memType    = QNN_MEM_TYPE_ION;        // FastRPC-backed
    //   desc.ionInfo.ionFd = <ion_fd>;
    //   if (QNN_SUCCESS != state_->api->memRegister(
    //         state_->device, &desc, 1, &state_->inputMem)) { ... }
    //
    // On execute(), set inputTensors[0].v1.memHandle = state_->inputMem and skip
    // the .data copy. Same for the output tensor.
    LOGW("registerZeroCopyMem_() is a placeholder — wire QnnMem ION handles.");
    return true;
#else
    return false;
#endif
}

// ---------------------------------------------------------------------------
// execute() — one inference pass per 1 s audio chunk.
// PLACEHOLDER body: shows the intended graphExecute call. The PCM->INT8
// dequant of `input` and INT8->PCM requant of the output are model-specific.
// ---------------------------------------------------------------------------
bool QnnInference::execute(const int16_t* input, std::vector<int16_t>& output) {
    if (!ready_.load(std::memory_order_acquire)) return false;

#if ONEVOICE_HAS_QNN
    // TODO(model): populate tensor data, run the graph, copy results out.
    //
    //   // 1) Dequantize int16 PCM -> INT8/ufixed8 into inputTensors[0].v1.data
    //   //    (or, with zero-copy: write into the ION buffer, no copy).
    //   prepareInput_(input, cfg_.inputSampleCount);
    //
    //   // 2) Execute on the HTP (synchronous; async via signal/fence is a TODO).
    //   if (QNN_SUCCESS != state_->api->graphExecute(
    //         state_->graph,
    //         state_->inputTensors.data(),  state_->inputTensors.size(),
    //         state_->outputTensors.data(), state_->outputTensors.size(),
    //         /*signal=*/nullptr, /*timeout=*/0)) {
    //       LOGE("graphExecute failed");
    //       return false;
    //   }
    //
    //   // 3) Requantize output INT8 -> int16 PCM into `output`.
    //   readOutput_(output);
    (void)input;
    output.assign(static_cast<std::size_t>(cfg_.inputSampleCount), 0);  // silence stub
    return true;
#else
    (void)input;
    output.clear();
    return false;
#endif
}

// ---------------------------------------------------------------------------
// shutdown() — release in reverse order of creation. Idempotent.
// ---------------------------------------------------------------------------
void QnnInference::shutdown() {
    ready_.store(false, std::memory_order_release);

#if ONEVOICE_HAS_QNN
    if (state_ && state_->api) {
        const auto& qnn = state_->api->QNN_INTERFACE_VER_NAME;
        if (state_->inputMem)   qnn.memDeRegister(&state_->inputMem, 1);
        if (state_->outputMem)  qnn.memDeRegister(&state_->outputMem, 1);
        // No graphFree in the QNN API: a graph is owned by its context and is
        // released by contextFree below.
        if (state_->context)    qnn.contextFree(state_->context, /*profile=*/nullptr);
        if (state_->device)     qnn.deviceFree(state_->device);
        if (state_->backend)    qnn.backendFree(state_->backend);
    }
#endif
    if (state_ && state_->backendLib) dlclose(state_->backendLib);
    state_.reset();
}

}  // namespace onevoice
