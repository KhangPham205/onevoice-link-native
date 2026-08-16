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

// NeMo AmberNet-compatible 16 kHz waveform -> [1,80,1008] normalized
// log-mel tensor. Features are channel-first; frames beyond the utterance's
// valid NeMo length (floor(sampleCount / 160) + 1) are zero-filled.
bool makeAmberNetFeatures(const float* samples, std::size_t sampleCount,
                          float* output, std::size_t outputCount,
                          std::string& error);

// SpeechFlow LID-compatible 16 kHz waveform -> [1,1000,80] normalized log-mel
// tensor, writing the frame count the classifier must mask with to
// *validFrames.
//
// Two differences from makeAmberNetFeatures are load-bearing and produce
// plausible-looking garbage rather than an error when confused:
//   * layout is time-major (BTC), not channel-first;
//   * normalization is over the whole utterance, because upstream config.yml
//     sets normalize_per_feature to false.
// The waveform is also peak-normalized first (normalize_signal: true).
bool makeSpeechFlowFeatures(const float* samples, std::size_t sampleCount,
                            float* output, std::size_t outputCount,
                            std::size_t* validFrames, std::string& error);

}  // namespace onevoice
