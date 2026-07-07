// ============================================================================
// audio_engine.cpp — Oboe capture + playback implementation
// ----------------------------------------------------------------------------
// All callback work is O(1) and allocation-free: SPSC ring push/pop + memset.
// Never call Java, never malloc, never lock from onAudioReady().
// ============================================================================

#include "audio_engine.h"

#include <android/log.h>
#include <array>
#include <cstring>          // std::memset
#include <utility>

#define LOG_TAG "OneVoiceAudio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace onevoice {

AudioEngine::~AudioEngine() { stop(); }

// ----------------------------------------------------------------------------
// init() — open both streams with low-latency / exclusive settings.
// We do NOT start them; start() does, so the AI thread can be primed first.
// ----------------------------------------------------------------------------
int AudioEngine::init() {
    using namespace oboe;

    // ---- Input (capture) stream ------------------------------------------
    // Oboe's fluent setters return AudioStreamBuilder* (not a reference), so the
    // chain uses `.` on the temporary and `->` on every subsequent call.
    auto inResult = AudioStreamBuilder()
                        .setDirection(Direction::Input)
                        ->setPerformanceMode(PerformanceMode::LowLatency)
                        ->setSharingMode(SharingMode::Exclusive)
                        ->setFormat(AudioFormat::I16)
                        ->setChannelCount(kChannelCount)
                        ->setSampleRate(kSampleRate)
                        ->setFramesPerCallback(kFramesPerCallback)
                        ->setDataCallback(this)
                        ->setErrorCallback(this)
                        ->openStream(inputStream_);
    if (inResult != Result::OK) {
        LOGE("Input stream open failed: %s", convertToText(inResult));
        return -1;
    }

    // ---- Output (playback) stream ----------------------------------------
    // Usage::VoiceCommunication + ContentType::Speech route through the
    // platform's acoustic-echo-cancellation / noise-suppression path —
    // essential at a 95 dB industrial site.
    auto outResult = AudioStreamBuilder()
                         .setDirection(Direction::Output)
                         ->setPerformanceMode(PerformanceMode::LowLatency)
                         ->setSharingMode(SharingMode::Exclusive)
                         ->setFormat(AudioFormat::I16)
                         ->setChannelCount(kChannelCount)
                         ->setSampleRate(kSampleRate)
                         ->setFramesPerCallback(kFramesPerCallback)
                         ->setUsage(Usage::VoiceCommunication)
                         ->setContentType(ContentType::Speech)
                         ->setDataCallback(this)
                         ->setErrorCallback(this)
                         ->openStream(outputStream_);
    if (outResult != Result::OK) {
        LOGE("Output stream open failed: %s", convertToText(outResult));
        return -1;
    }

    LOGI("Streams opened. inBurst=%d outBurst=%d (frames) | "
         "actualSR=%d/%d | sharing=%s/%s",
         inputStream_->getFramesPerBurst(), outputStream_->getFramesPerBurst(),
         inputStream_->getSampleRate(), outputStream_->getSampleRate(),
         convertToText(inputStream_->getSharingMode()),
         convertToText(outputStream_->getSharingMode()));
    return 0;
}

// ----------------------------------------------------------------------------
// start() — begin full-duplex audio.
// Output is started first and primed with one burst of silence so the first
// callback doesn't underflow while the AI thread warms up.
// ----------------------------------------------------------------------------
int AudioEngine::start() {
    if (!inputStream_ || !outputStream_) {
        LOGE("start() called before successful init()");
        return -1;
    }
    running_.store(true, std::memory_order_release);

    // Prime the output ring with one callback's worth of silence to avoid a
    // startup click; the AI thread will overwrite this naturally.
    std::array<int16_t, kFramesPerCallback> silence{};
    outputBuffer_.write(silence.data(), silence.size());

    const auto r1 = inputStream_->requestStart();
    const auto r2 = outputStream_->requestStart();
    if (r1 != oboe::Result::OK || r2 != oboe::Result::OK) {
        LOGE("requestStart failed: in=%s out=%s",
             convertToText(r1), convertToText(r2));
        running_.store(false, std::memory_order_release);
        return -1;
    }
    LOGI("Audio pipeline started");
    return 0;
}

// ----------------------------------------------------------------------------
// stop() — drain gracefully, then close. Idempotent.
// ----------------------------------------------------------------------------
void AudioEngine::stop() {
    running_.store(false, std::memory_order_release);
    if (inputStream_)  inputStream_->stop();
    if (outputStream_) outputStream_->stop();
    if (inputStream_)  inputStream_->close();
    if (outputStream_) outputStream_->close();
    inputStream_.reset();
    outputStream_.reset();
}

// ----------------------------------------------------------------------------
// onAudioReady() — the realtime hot path. One writer per ring, branchless copy.
// ----------------------------------------------------------------------------
oboe::DataCallbackResult AudioEngine::onAudioReady(oboe::AudioStream* stream,
                                                   void* audioData,
                                                   int32_t numFrames) {
    if (!running_.load(std::memory_order_acquire))
        return oboe::DataCallbackResult::Stop;

    if (stream->getDirection() == oboe::Direction::Input) {
        // ---- Capture: push fresh samples into the input ring. ----
        // If the AI thread has stalled and the ring is full, write() drops the
        // newest frames (returns < numFrames). Dropping is preferable to
        // blocking the realtime callback.
        inputBuffer_.write(static_cast<const int16_t*>(audioData),
                           static_cast<std::size_t>(numFrames));
    } else {
        // ---- Playback: pull synthesized samples from the output ring. ----
        // If the AI thread hasn't produced enough yet (underrun), zero-fill the
        // tail so the DAC gets a clean gap rather than a glitch from stale data.
        int16_t* out = static_cast<int16_t*>(audioData);
        const std::size_t got = outputBuffer_.read(
            out, static_cast<std::size_t>(numFrames));
        if (got < static_cast<std::size_t>(numFrames)) {
            std::memset(out + got, 0,
                        (numFrames - got) * sizeof(int16_t));
        }
    }
    return oboe::DataCallbackResult::Continue;
}

void AudioEngine::onErrorBeforeClose(oboe::AudioStream* /*stream*/, oboe::Result error) {
    LOGE("Audio stream error (before close): %s", convertToText(error));
}

void AudioEngine::onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) {
    LOGE("Audio stream error (after close): %s", convertToText(error));
    // Exclusive streams can be lost when another app grabs the device, or when
    // the framework disconnects (e.g. headset plug/unplug). The Kotlin layer
    // observes running_==false via nativeStopEngine and may call nativeStartEngine
    // again to reopen. (Full auto-recovery is a TODO for the orchestrator.)
    running_.store(false, std::memory_order_release);
    (void)stream;
}

}  // namespace onevoice
