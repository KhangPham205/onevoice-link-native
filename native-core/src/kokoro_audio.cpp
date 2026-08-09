#include "kokoro_audio.h"

#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <mutex>
#include <vector>

namespace onevoice {
namespace {

constexpr std::size_t kF0Count = 50;
constexpr std::size_t kUpsample = 300;
constexpr std::size_t kSamples = kF0Count * kUpsample;
constexpr std::size_t kFft = 20;
constexpr std::size_t kBins = kFft / 2 + 1;
constexpr std::size_t kHop = 5;
constexpr std::size_t kFrames = 3001;
// QNN decoder windows cover 25 alignment frames (15000 samples) and the host
// advances by 20 frames. Preserve the source state at that advance point so
// the repeated five-frame context is generated from the same timeline.
constexpr std::size_t kAdvanceSamples = 20 * 600;
constexpr float kSampleRate = 24000.0f;
constexpr float kPi = 3.14159265358979323846f;
constexpr std::array<float, 9> kMixerWeights = {
    -0.08154186606407166f, -0.1851966679096222f,
    -0.18263398110866547f, -0.17837205529212952f,
    -0.09873894602060318f,  0.0826403945684433f,
     0.08743999153375626f, -0.39068546891212463f,
    -0.5477443337440491f,
};
constexpr float kMixerBias = -0.02945026010274887f;

std::atomic<uint32_t> gSeed{0x71a53d29u};
std::mutex gSourceMutex;
std::array<float, 9> gPhase{};
uint32_t gRandom = 0;
bool gSourceInitialized = false;

float uniformSigned(uint32_t& state) {
    state ^= state << 13;
    state ^= state >> 17;
    state ^= state << 5;
    return static_cast<float>(state) / static_cast<float>(UINT32_MAX) * 2.0f - 1.0f;
}

float gaussian(uint32_t& state) {
    // Box-Muller with guarded input; generated off the realtime audio thread.
    const float u1 = std::max(1.0e-7f, (uniformSigned(state) + 1.0f) * 0.5f);
    const float u2 = (uniformSigned(state) + 1.0f) * 0.5f;
    return std::sqrt(-2.0f * std::log(u1)) * std::cos(2.0f * kPi * u2);
}

std::size_t reflected(long index) {
    // torch/ONNX reflect padding: -1 -> 1, length -> length-2.
    while (index < 0 || index >= static_cast<long>(kSamples)) {
        if (index < 0) index = -index;
        if (index >= static_cast<long>(kSamples)) {
            index = 2L * static_cast<long>(kSamples) - index - 2L;
        }
    }
    return static_cast<std::size_t>(index);
}

}  // namespace

bool makeKokoroHarmonics(const float* f0, std::size_t f0Count,
                         float* har, std::size_t harCount,
                         bool resetPhase,
                         std::string& error) {
    error.clear();
    if (!f0 || !har) {
        error = "Kokoro HAR received a null buffer";
        return false;
    }
    if (f0Count < kF0Count || harCount < 2 * kBins * kFrames) {
        error = "Kokoro HAR buffer shape mismatch";
        return false;
    }

    std::lock_guard<std::mutex> sourceLock(gSourceMutex);
    if (resetPhase || !gSourceInitialized) {
        gRandom = gSeed.fetch_add(0x9e3779b9u, std::memory_order_relaxed);
        gPhase[0] = 0.0f;
        for (std::size_t h = 1; h < gPhase.size(); ++h) {
            gPhase[h] = (uniformSigned(gRandom) + 1.0f) * kPi;
        }
        gSourceInitialized = true;
    }
    std::vector<float> source(kSamples);
    std::array<float, 9> nextPhase{};
    uint32_t nextRandom = 0;
    bool capturedNextState = false;
    for (std::size_t sample = 0; sample < kSamples; ++sample) {
        if (sample == kAdvanceSamples) {
            nextPhase = gPhase;
            nextRandom = gRandom;
            capturedNextState = true;
        }
        const float frequency = std::max(0.0f, f0[sample / kUpsample]);
        const bool voiced = frequency > 10.0f;
        const float noiseScale = voiced ? 0.003f : (0.1f / 3.0f);
        float mixed = kMixerBias;
        for (std::size_t h = 0; h < gPhase.size(); ++h) {
            gPhase[h] += 2.0f * kPi * frequency * static_cast<float>(h + 1) / kSampleRate;
            if (gPhase[h] > 2.0f * kPi) gPhase[h] = std::fmod(gPhase[h], 2.0f * kPi);
            const float harmonic = voiced ? 0.1f * std::sin(gPhase[h]) : 0.0f;
            mixed += kMixerWeights[h] * (harmonic + noiseScale * gaussian(gRandom));
        }
        source[sample] = std::tanh(mixed);
    }
    if (capturedNextState) {
        gPhase = nextPhase;
        gRandom = nextRandom;
    }

    std::array<float, kFft> window{};
    for (std::size_t i = 0; i < kFft; ++i) {
        // torch.hann_window(20) defaults periodic=true.
        window[i] = 0.5f - 0.5f * std::cos(
            2.0f * kPi * static_cast<float>(i) / static_cast<float>(kFft));
    }
    for (std::size_t frame = 0; frame < kFrames; ++frame) {
        const long start = static_cast<long>(frame * kHop) - static_cast<long>(kFft / 2);
        for (std::size_t bin = 0; bin < kBins; ++bin) {
            double real = 0.0;
            double imaginary = 0.0;
            for (std::size_t i = 0; i < kFft; ++i) {
                const float value = source[reflected(start + static_cast<long>(i))] * window[i];
                const float angle = -2.0f * kPi * static_cast<float>(bin * i) /
                                    static_cast<float>(kFft);
                real += value * std::cos(angle);
                imaginary += value * std::sin(angle);
            }
            har[bin * kFrames + frame] = static_cast<float>(
                std::sqrt(real * real + imaginary * imaginary + 1.0e-9));
            har[(kBins + bin) * kFrames + frame] = static_cast<float>(
                std::atan2(imaginary, real));
        }
    }
    return true;
}

}  // namespace onevoice
