// ============================================================================
// main_jni.cpp — JNI boundary + the pure-C++ realtime pipeline
// ----------------------------------------------------------------------------
// This is the ONLY file that touches JNI. The entire audio->AI->audio cascade
// runs in native threads so ART garbage-collection pauses can never tear the
// pipeline or cause latency spikes — the JVM is not on the hot path at all.
//
// Threads at runtime:
//   1. Oboe capture callback  (framework RT thread) -> input ring (producer)
//   2. AI worker thread       (this file, spawned here) -> drains input ring,
//      runs QNN, fills output ring
//   3. Oboe playback callback (framework RT thread) -> output ring (consumer)
//
// The Kotlin UI talks only through the four lifecycle entry points below.
// ============================================================================

#include <jni.h>

#include <android/log.h>
#include <atomic>
#include <chrono>
#include <memory>
#include <thread>
#include <vector>

#include "audio_engine.h"
#include "qnn_inference.h"

#define LOG_TAG "OneVoiceCore"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// Aggregated native engine state. A single instance is plenty for one
// conversation session; the Kotlin side serializes lifecycle calls.
struct Engine {
    std::unique_ptr<onevoice::AudioEngine>   audio;
    std::unique_ptr<onevoice::QnnInference>  qnn;
    std::thread                              worker;
    std::atomic<bool>                        running{false};
};
std::unique_ptr<Engine> g_engine;

// Fixed chunk the model consumes per inference pass (1 s @ 16 kHz). Matches
// InferenceConfig::inputSampleCount and the model's static input shape.
constexpr std::size_t kChunkSamples = 16000;

// ------------------------------------------------------------------------
// workerLoop() — the AI cascade. Runs entirely in C++, never touches the JVM.
// It blocks (yields) until a full chunk is available, so audio latency is
// governed by the Oboe callbacks, not by the model's chunk size.
// ------------------------------------------------------------------------
void workerLoop(Engine* e) {
    LOGI("AI worker thread started");
    std::vector<int16_t> inChunk(kChunkSamples);
    std::vector<int16_t> outChunk;

    while (e->running.load(std::memory_order_acquire)) {
        // ---- Drain a full 1 s chunk from the capture ring. ----
        // The SPSC read is non-blocking; when starved, yield + micro-sleep so we
        // don't burn a whole core. A condition_variable would add a mutex on the
        // hot path, so we deliberately keep the ring lock-free.
        std::size_t got = 0;
        while (got < kChunkSamples && e->running.load(std::memory_order_acquire)) {
            got += e->audio->inputBuffer().read(inChunk.data() + got,
                                                kChunkSamples - got);
            if (got < kChunkSamples)
                std::this_thread::sleep_for(std::chrono::microseconds(100));
        }
        if (!e->running.load(std::memory_order_acquire)) break;

        // ---- Run the S2ST cascade on the Hexagon HTP. ----
        if (!e->qnn->execute(inChunk.data(), outChunk)) {
            LOGE("QNN execute failed — dropping chunk");
            continue;
        }

        // ---- Push synthesized speech to the playback ring. ----
        // Backpressure: if playback falls behind (rare), yield until room opens.
        std::size_t put = 0;
        while (put < outChunk.size() && e->running.load(std::memory_order_acquire)) {
            put += e->audio->outputBuffer().write(outChunk.data() + put,
                                                  outChunk.size() - put);
            if (put < outChunk.size())
                std::this_thread::sleep_for(std::chrono::microseconds(100));
        }
    }
    LOGI("AI worker thread exiting");
}

// Helper: copy a jstring to a std::string (null-safe).
std::string jstr(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    env->ReleaseStringUTFChars(s, c);
    return out;
}

}  // namespace

// ============================================================================
// JNI entry points. Names map to com.onevoice.link.core.OneVoiceNative in
// Kotlin (Java_com_onevoice_link_core_OneVoiceNative_*).
// ============================================================================

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* /*vm*/, void* /*reserved*/) {
    LOGI("OneVoice core native library loaded (JNI 1.6)");
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* /*vm*/, void* /*reserved*/) {
    LOGI("OneVoice core native library unloaded");
}

// ---- nativeInitEngine(backendPath, contextBinPath, graphName) : boolean ----
// Opens audio streams and loads the QNN context. Does NOT start audio yet.
JNIEXPORT jboolean JNICALL
Java_com_onevoice_link_core_OneVoiceNative_nativeInitEngine(
        JNIEnv* env, jclass /*clazz*/,
        jstring backendPath, jstring contextBinPath, jstring graphName) {
    if (g_engine) return JNI_TRUE;  // already initialized

    auto e = std::make_unique<Engine>();
    e->audio = std::make_unique<onevoice::AudioEngine>();
    if (e->audio->init() != 0) {
        LOGE("AudioEngine init failed");
        return JNI_FALSE;
    }

    e->qnn = std::make_unique<onevoice::QnnInference>();
    onevoice::InferenceConfig cfg;
    cfg.backendLibPath    = jstr(env, backendPath);
    cfg.contextBinaryPath = jstr(env, contextBinPath);
    cfg.graphName         = jstr(env, graphName);
    if (!e->qnn->init(cfg)) {
        LOGE("QNN init failed");
        return JNI_FALSE;
    }

    g_engine = std::move(e);
    LOGI("Engine initialized (audio + QNN ready)");
    return JNI_TRUE;
}

// ---- nativeStartEngine() : boolean ---------------------------------------
// Spawns the AI worker and starts full-duplex audio.
JNIEXPORT jboolean JNICALL
Java_com_onevoice_link_core_OneVoiceNative_nativeStartEngine(JNIEnv* /*env*/, jclass /*clazz*/) {
    if (!g_engine) return JNI_FALSE;
    // Idempotent: a second start while running would move-assign a new thread
    // onto the still-joinable worker, which calls std::terminate().
    if (g_engine->running.load(std::memory_order_acquire)) return JNI_TRUE;
    g_engine->running.store(true, std::memory_order_release);
    g_engine->worker = std::thread(workerLoop, g_engine.get());
    if (g_engine->audio->start() != 0) {
        LOGE("AudioEngine start failed");
        g_engine->running.store(false, std::memory_order_release);
        if (g_engine->worker.joinable()) g_engine->worker.join();
        return JNI_FALSE;
    }
    LOGI("Engine started");
    return JNI_TRUE;
}

// ---- nativeStopEngine() : void -------------------------------------------
// Halts the worker, stops + closes audio. Engine remains initialized for a
// fast restart; nativeDestroyEngine tears it down fully.
JNIEXPORT void JNICALL
Java_com_onevoice_link_core_OneVoiceNative_nativeStopEngine(JNIEnv* /*env*/, jclass /*clazz*/) {
    if (!g_engine) return;
    g_engine->running.store(false, std::memory_order_release);
    if (g_engine->worker.joinable()) g_engine->worker.join();
    g_engine->audio->stop();
    LOGI("Engine stopped");
}

// ---- nativeDestroyEngine() : void ----------------------------------------
JNIEXPORT void JNICALL
Java_com_onevoice_link_core_OneVoiceNative_nativeDestroyEngine(JNIEnv* /*env*/, jclass /*clazz*/) {
    if (!g_engine) return;
    if (g_engine->running.load(std::memory_order_acquire)) {
        g_engine->running.store(false, std::memory_order_release);
        if (g_engine->worker.joinable()) g_engine->worker.join();
    }
    g_engine->audio->stop();
    g_engine->qnn->shutdown();
    g_engine.reset();
    LOGI("Engine destroyed");
}

}  // extern "C"
