#pragma once
// ============================================================================
// onevoice::QnnRuntime  —  Qualcomm QNN / Hexagon HTP multi-context runtime
// ----------------------------------------------------------------------------
// The S2ST pipeline is NOT one end-to-end graph: the model team ships a cascade
// of separately-compiled QNN context binaries (ASR, MT encoder, MT decoder, TTS
// encoder, TTS decoder — see onevoice-npu-bins/HANDOFF.md). Each .bin holds its
// own graph under its own AI Hub-generated name (`graph_08xlh5ri`,
// `opus_mt_encoder`, …), so the old single-context/single-graph design could
// never have loaded this pipeline.
//
// The runtime therefore separates the two concerns:
//   * backend + device are created ONCE (backendCreate / deviceCreate),
//   * each .bin then becomes its own context via contextCreateFromBinary.
//
// Loading a model is a three-step proof that the artifact really matches this
// phone, and each step is reported separately so a failure is diagnosable:
//   1. QnnSystemContext_getBinaryInfo  — parse the blob, read graph name +
//      tensor contract + socModel WITHOUT touching hardware.
//   2. contextCreateFromBinary         — actually place the graphs on the HTP.
//      This is the step that fails when the .bin was compiled for a different
//      Hexagon arch (e.g. an 8-Elite build on an 8 Gen 2 phone).
//   3. graphRetrieve(graphName)        — confirm the graph identity we expect.
//
// The header stays SDK-agnostic (no QNN types leak out): all QNN handles live
// behind the `State` pimpl in the .cpp, so callers need no QNN headers.
// ============================================================================

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace onevoice {

/** One cascade stage: a context binary plus the graph to retrieve from it. */
struct ModelSpec {
    std::string stage;        // logical role, e.g. "asr", "mt_encoder"
    std::string contextPath;  // absolute path to the .bin on the device
    std::string graphName;    // exact graph name inside that context
};

/** A graph input/output tensor as declared by the context binary itself. */
struct TensorInfo {
    std::string           name;
    std::string           dataType;  // QNN_DATATYPE_* with the prefix stripped
    std::vector<uint32_t> dims;
};

/** Outcome of one loadModel() call — the payload the dashboard renders. */
struct ModelLoadResult {
    std::string             stage;
    bool                    ok = false;
    double                  loadMs = 0.0;   // wall time across steps 1-3
    uint64_t                sizeBytes = 0;
    std::string             graphName;      // as found inside the binary
    uint32_t                socModel = 0;   // QNN_SOC_MODEL_* the .bin targets
    std::string             buildId;        // QAIRT version that produced it
    std::vector<TensorInfo> inputs;
    std::vector<TensorInfo> outputs;
    std::string             error;          // empty when ok
};

class QnnRuntime {
public:
    QnnRuntime();
    ~QnnRuntime();

    QnnRuntime(const QnnRuntime&) = delete;
    QnnRuntime& operator=(const QnnRuntime&) = delete;

    /**
     * dlopen the HTP backend + the system library, pick a version-compatible
     * interface provider, then create the backend and device. Idempotent.
     *
     * @param backendLibPath absolute path to libQnnHtp.so (in nativeLibraryDir)
     * @param systemLibPath  absolute path to libQnnSystem.so (same directory)
     * @param adspLibraryPath semicolon-delimited FastRPC search path. It must
     *                        contain the directory holding the matching Hexagon
     *                        skeleton and is published before libQnnHtp.so loads.
     * @param useSignedProcessDomain true when the selected skeleton is signed;
     *                               configures QNN_HTP_DEVICE_CONFIG_OPTION_SIGNEDPD.
     */
    bool initBackend(const std::string& backendLibPath,
                     const std::string& systemLibPath,
                     const std::string& adspLibraryPath,
                     bool useSignedProcessDomain);

    /** Load one cascade stage. Never throws; failures come back in the result. */
    ModelLoadResult loadModel(const ModelSpec& spec);

    /** Free every loaded context; backend/device stay up for a fast reload. */
    void releaseModels();

    /** Full teardown (contexts, device, backend, dlopen handles). Idempotent. */
    void shutdown();

    bool backendReady() const noexcept { return ready_.load(std::memory_order_acquire); }
    std::size_t modelCount() const noexcept;

    /**
     * Run the cascade over one audio chunk.
     *
     * NOT IMPLEMENTED — deliberately. Executing the cascade needs the host-glue
     * the model team has not delivered for Android yet: 80-mel fbank + CTC
     * greedy collapse (ASR), SentencePiece plus an autoregressive decode loop
     * driving the 27-input/13-output decoder KV-cache (MT), and G2P + alignment
     * scatter + F0/har synthesis + overlap-add (TTS). See HANDOFF.md §3-§5.
     *
     * Until that lands this returns false, so the audio worker drops the chunk
     * rather than pushing fabricated silence into the playback ring and making
     * the pipeline look functional when it is not.
     */
    bool executeCascade(const int16_t* input, std::size_t sampleCount,
                        std::vector<int16_t>& output);

private:
    struct State;
    std::unique_ptr<State> state_;
    std::atomic<bool>      ready_{false};
};

}  // namespace onevoice
