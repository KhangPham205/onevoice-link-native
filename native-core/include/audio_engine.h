#pragma once
// ============================================================================
// onevoice::AudioEngine  —  Google Oboe capture + playback
// ----------------------------------------------------------------------------
// Owns two Oboe streams tuned for bare-metal latency:
//   * PerformanceMode::LowLatency  — engages the MMAP fast path (Android 8.1+)
//     bypassing the AudioFlinger mixer thread entirely.
//   * SharingMode::Exclusive       — no mixer, no resampler, lowest possible
//     round-trip latency; we fall back to Shared automatically if unavailable.
//
// Threading contract (this is the whole point of the architecture):
//   * Oboe invokes onAudioReady() on a high-priority, framework-managed thread
//     that MUST NEVER block, allocate, lock, or call into Java. We do only
//     non-blocking SPSC ring push/pop + memset here.
//   * The 16 ms (~256-frame) callback granularity keeps the audio buffer tiny;
//     the AI thread independently drains full 1 s (16000-sample) chunks from
//     the ring, decoupling model latency from audio latency.
//
// Format: 16 kHz, mono, PCM_INT16 — matches the model's static input shape and
// the Android VoiceCommunication audio path (AEC/NS engaged via Usage).
// ============================================================================

#include <atomic>
#include <memory>

#include <oboe/Oboe.h>

#include "ring_buffer.h"

namespace onevoice {

class AudioEngine : public oboe::AudioStreamCallback {
public:
    AudioEngine() = default;
    ~AudioEngine() override;

    AudioEngine(const AudioEngine&) = delete;
    AudioEngine& operator=(const AudioEngine&) = delete;

    // Open both streams (does not start them). Returns 0 on success.
    int init();

    // Begin capture + playback. The realtime callbacks start firing here.
    int start();

    // Stop + close both streams. Safe to call from any thread; idempotent.
    void stop();

    // Rings bridging the audio world to the AI world.
    //   inputBuffer()  : capture-callback (producer)  -> AI thread (consumer)
    //   outputBuffer() : AI thread (producer)         -> playback-callback (consumer)
    SpscRingBuffer<int16_t>& inputBuffer()  { return inputBuffer_; }
    SpscRingBuffer<int16_t>& outputBuffer() { return outputBuffer_; }

    bool isRunning() const noexcept { return running_.load(std::memory_order_acquire); }

    // ---- oboe::AudioStreamCallback (invoked on Oboe's realtime thread) ----
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream,
                                          void* audioData,
                                          int32_t numFrames) override;
    void onErrorBeforeClose(oboe::AudioStream* stream, oboe::Result error) override;
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

private:
    // ---- Stream configuration constants -----------------------------------
    static constexpr int32_t kSampleRate       = 16000;   // model sample rate
    static constexpr int     kChannelCount      = 1;       // mono
    static constexpr int32_t kFramesPerCallback = 256;     // 16 ms @ 16 kHz

    // Ring capacity = 32768 samples = 2.048 s (power of two).
    // Large enough to absorb a transient AI stall without dropping speech.
    static constexpr std::size_t kRingCapacity = 1u << 15;

    oboe::ManagedStream inputStream_;
    oboe::ManagedStream outputStream_;

    SpscRingBuffer<int16_t> inputBuffer_{kRingCapacity};
    SpscRingBuffer<int16_t> outputBuffer_{kRingCapacity};

    std::atomic<bool> running_{false};
};

}  // namespace onevoice
