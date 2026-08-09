#include "asr_frontend.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <complex>
#include <limits>
#include <vector>

namespace onevoice {
namespace {

constexpr std::size_t kSamples = 160000;
constexpr std::size_t kFft = 512;
constexpr std::size_t kBins = kFft / 2 + 1;
constexpr std::size_t kMels = 80;
constexpr std::size_t kFrames = 1001;
constexpr std::size_t kWindow = 400;
constexpr std::size_t kHop = 160;
constexpr float kPreemphasis = 0.97f;
constexpr float kSampleRate = 16000.0f;
constexpr float kLogGuard = 1.0f / 16777216.0f;  // 2^-24
constexpr float kPi = 3.14159265358979323846f;

float hzToMel(float hz) {
    constexpr float fSp = 200.0f / 3.0f;
    constexpr float minLogHz = 1000.0f;
    constexpr float minLogMel = minLogHz / fSp;
    constexpr float logStep = 0.06875177742094912f;  // log(6.4) / 27
    return hz < minLogHz ? hz / fSp
                         : minLogMel + std::log(hz / minLogHz) / logStep;
}

float melToHz(float mel) {
    constexpr float fSp = 200.0f / 3.0f;
    constexpr float minLogHz = 1000.0f;
    constexpr float minLogMel = minLogHz / fSp;
    constexpr float logStep = 0.06875177742094912f;
    return mel < minLogMel ? mel * fSp
                           : minLogHz * std::exp(logStep * (mel - minLogMel));
}

void fft(std::array<std::complex<float>, kFft>& values) {
    for (std::size_t i = 1, j = 0; i < kFft; ++i) {
        std::size_t bit = kFft >> 1;
        for (; j & bit; bit >>= 1) j ^= bit;
        j ^= bit;
        if (i < j) std::swap(values[i], values[j]);
    }
    for (std::size_t len = 2; len <= kFft; len <<= 1) {
        const float angle = -2.0f * kPi / static_cast<float>(len);
        const std::complex<float> step(std::cos(angle), std::sin(angle));
        for (std::size_t i = 0; i < kFft; i += len) {
            std::complex<float> w(1.0f, 0.0f);
            for (std::size_t j = 0; j < len / 2; ++j) {
                const auto u = values[i + j];
                const auto v = values[i + j + len / 2] * w;
                values[i + j] = u + v;
                values[i + j + len / 2] = u - v;
                w *= step;
            }
        }
    }
}

using MelBank = std::array<std::array<float, kBins>, kMels>;

MelBank makeMelBank() {
    MelBank bank{};
    std::array<float, kMels + 2> edges{};
    const float lowMel = hzToMel(0.0f);
    const float highMel = hzToMel(kSampleRate / 2.0f);
    for (std::size_t i = 0; i < edges.size(); ++i) {
        const float mel = lowMel + (highMel - lowMel) *
            static_cast<float>(i) / static_cast<float>(edges.size() - 1);
        edges[i] = melToHz(mel);
    }
    for (std::size_t m = 0; m < kMels; ++m) {
        const float left = edges[m];
        const float center = edges[m + 1];
        const float right = edges[m + 2];
        const float enorm = 2.0f / (right - left);
        for (std::size_t bin = 0; bin < kBins; ++bin) {
            const float hz = static_cast<float>(bin) * kSampleRate / kFft;
            const float lower = (hz - left) / (center - left);
            const float upper = (right - hz) / (right - center);
            bank[m][bin] = std::max(0.0f, std::min(lower, upper)) * enorm;
        }
    }
    return bank;
}

}  // namespace

bool makeParakeetFeatures(const float* samples, std::size_t sampleCount,
                          float* output, std::size_t outputCount,
                          std::string& error) {
    error.clear();
    if (!samples || !output) {
        error = "ASR frontend received a null buffer";
        return false;
    }
    if (sampleCount == 0 || sampleCount > kSamples) {
        error = "ASR segment must contain 1..160000 samples";
        return false;
    }
    if (outputCount < kFrames * kMels) {
        error = "ASR feature buffer is smaller than [1001,80]";
        return false;
    }

    std::vector<float> waveform(kSamples, 0.0f);
    waveform[0] = samples[0];
    for (std::size_t i = 1; i < sampleCount; ++i) {
        waveform[i] = samples[i] - kPreemphasis * samples[i - 1];
    }

    static const MelBank melBank = makeMelBank();
    std::array<float, kWindow> window{};
    for (std::size_t i = 0; i < kWindow; ++i) {
        // NeMo torch.hann_window(periodic=false).
        window[i] = 0.5f - 0.5f * std::cos(
            2.0f * kPi * static_cast<float>(i) / static_cast<float>(kWindow - 1));
    }

    std::array<std::complex<float>, kFft> spectrum{};
    std::array<float, kBins> power{};
    for (std::size_t frame = 0; frame < kFrames; ++frame) {
        spectrum.fill({0.0f, 0.0f});
        // center=true with constant padding: frame 0 starts 256 samples before
        // the waveform and frame 1000 ends in the right pad.
        const long start = static_cast<long>(frame * kHop) - static_cast<long>(kFft / 2);
        constexpr std::size_t windowOffset = (kFft - kWindow) / 2;
        for (std::size_t i = 0; i < kWindow; ++i) {
            const std::size_t fftIndex = windowOffset + i;
            const long source = start + static_cast<long>(fftIndex);
            if (source >= 0 && source < static_cast<long>(kSamples)) {
                spectrum[fftIndex] = waveform[static_cast<std::size_t>(source)] * window[i];
            }
        }
        fft(spectrum);
        for (std::size_t bin = 0; bin < kBins; ++bin) power[bin] = std::norm(spectrum[bin]);
        for (std::size_t mel = 0; mel < kMels; ++mel) {
            double energy = 0.0;
            for (std::size_t bin = 0; bin < kBins; ++bin) {
                energy += static_cast<double>(melBank[mel][bin]) * power[bin];
            }
            output[frame * kMels + mel] = std::log(static_cast<float>(energy) + kLogGuard);
        }
    }

    const std::size_t validFrames = std::min(kFrames,
        (sampleCount + kHop - 1) / kHop);
    if (validFrames < 2) {
        error = "ASR segment is too short to normalize";
        return false;
    }
    for (std::size_t mel = 0; mel < kMels; ++mel) {
        double sum = 0.0;
        for (std::size_t frame = 0; frame < validFrames; ++frame) {
            sum += output[frame * kMels + mel];
        }
        const double mean = sum / static_cast<double>(validFrames);
        double squared = 0.0;
        for (std::size_t frame = 0; frame < validFrames; ++frame) {
            const double delta = output[frame * kMels + mel] - mean;
            squared += delta * delta;
        }
        const float scale = static_cast<float>(
            std::sqrt(squared / static_cast<double>(validFrames - 1)) + 1.0e-5);
        for (std::size_t frame = 0; frame < validFrames; ++frame) {
            output[frame * kMels + mel] =
                (output[frame * kMels + mel] - static_cast<float>(mean)) / scale;
        }
        for (std::size_t frame = validFrames; frame < kFrames; ++frame) {
            output[frame * kMels + mel] = 0.0f;
        }
    }
    return true;
}

}  // namespace onevoice
