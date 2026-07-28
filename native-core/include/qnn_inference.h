#pragma once
// ============================================================================
// onevoice::QnnInference  —  Qualcomm QNN / Hexagon HTP execution
// ----------------------------------------------------------------------------
// Loads a pre-compiled QNN context binary (.bin) directly onto the Hexagon HTP
// and executes a named graph for each 1 s audio chunk. The AI team pre-bakes
// quantization (INT8) and static tensor shapes — including padding and the
// attention mask — into the model, so this layer only binds the raw audio input
// tensor and reads the synthesized output tensor.
//
// Memory model (the performance-critical part):
//   * The hot path targets ZERO-COPY tensor inputs. Tensors are backed by
//     ION/FastRPC memory registered with QnnMem_register so the HTP DMAs the
//     buffer directly — no redundant memcpy across the application/HTP boundary.
//   * Where QnnMem is unavailable (CPU/GPU backends) we fall back to a single
//     tensor.data pointer copy per execute(); still cheap, still bounded.
//
// The header is intentionally SDK-agnostic (no QNN types leak out): all
// QNN-specific state lives behind the `QnnState` pimpl defined in the .cpp, so
// consumers don't need the QNN headers to include this file.
// ============================================================================

#include <atomic>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace onevoice {

struct InferenceConfig {
    // Path to the QNN backend shared object, e.g. "libQnnHtp.so".
    std::string backendLibPath;
    // Serialized context binary (.bin) with graphs already compiled for HTP.
    std::string contextBinaryPath;
    // Graph name to retrieve from the loaded context (must match the .dlc export).
    std::string graphName;
    // Static input shape: number of PCM samples per inference (1 s @ 16 kHz).
    int32_t inputSampleCount = 16000;
};

class QnnInference {
public:
    QnnInference();
    ~QnnInference();

    QnnInference(const QnnInference&) = delete;
    QnnInference& operator=(const QnnInference&) = delete;

    // Load backend, create device, bake context from binary, retrieve graph,
    // map tensors. On failure the object is left un-initialized and safe to
    // destroy. Returns true on success.
    bool init(const InferenceConfig& cfg);

    bool isReady() const noexcept { return ready_.load(std::memory_order_acquire); }

    // Release all HTP resources. Idempotent; also called by the destructor.
    void shutdown();

    // Run one inference pass.
    //   input  : INT16 PCM samples, exactly cfg.inputSampleCount frames.
    //   output : (out) synthesized INT16 PCM written into the vector.
    //            Size is determined by the graph's static output shape.
    // Returns false on execution failure (caller drops the chunk and continues).
    bool execute(const int16_t* input, std::vector<int16_t>& output);

private:
    // SDK-specific handles/graph state, defined in the .cpp (pimpl).
    struct QnnState;
    std::unique_ptr<QnnState> state_;

    InferenceConfig cfg_;
    std::atomic<bool> ready_{false};

    // Ordered setup steps; each returns false on failure.
    bool loadBackend_();              // dlopen libQnnHtp.so + getProviderInterface
    bool createContextFromBinary_();  // deviceCreate + contextCreateFromBinary(.bin)
    bool retrieveGraph_();            // graphRetrieve(graphName)
    bool mapTensors_();               // bind .dlc/.bin tensor names + static shapes
    bool registerZeroCopyMem_();      // QnnMem_register ION/FastRPC buffers
};

}  // namespace onevoice
