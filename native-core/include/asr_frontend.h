#pragma once

#include <cstddef>
#include <string>

namespace onevoice {

// NeMo-compatible 16 kHz waveform -> [1,1001,80] normalized log-mel tensor.
bool makeParakeetFeatures(const float* samples, std::size_t sampleCount,
                          float* output, std::size_t outputCount,
                          std::string& error);

// Kaldi/icefall-compatible 16 kHz waveform -> [1,1000,80] log-fbank tensor.
// Gipformer is a Zipformer RNN-T: unlike Parakeet CTC it expects unnormalized
// Kaldi fbank features (Povey window, snip_edges=false, 20..7600 Hz).
bool makeGipformerFeatures(const float* samples, std::size_t sampleCount,
                           float* output, std::size_t outputCount,
                           std::string& error);

}  // namespace onevoice
