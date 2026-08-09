#pragma once

#include <cstddef>
#include <string>

namespace onevoice {

// NeMo-compatible 16 kHz waveform -> [1,1001,80] normalized log-mel tensor.
bool makeParakeetFeatures(const float* samples, std::size_t sampleCount,
                          float* output, std::size_t outputCount,
                          std::string& error);

}  // namespace onevoice
