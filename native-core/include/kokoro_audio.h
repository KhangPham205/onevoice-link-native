#pragma once

#include <cstddef>
#include <string>

namespace onevoice {

// Kokoro's CPU source-generator/STFT host glue. F0 has 50 values; HAR is
// channel-major [1,22,3001] (11 magnitude + 11 phase bins). Successive calls
// advance harmonic/noise state by the QNN host's 20-frame bucket stride.
bool makeKokoroHarmonics(const float* f0, std::size_t f0Count,
                         float* har, std::size_t harCount,
                         bool resetPhase,
                         std::string& error);

}  // namespace onevoice
