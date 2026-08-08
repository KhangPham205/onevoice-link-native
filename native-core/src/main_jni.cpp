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
//      runs the QNN cascade, fills output ring
//   3. Oboe playback callback (framework RT thread) -> output ring (consumer)
//
// Model loading is a separate, per-stage call: the pipeline is a cascade of
// independently-compiled context binaries, so the dashboard loads them one at a
// time and renders each result. See qnn_runtime.h.
// ============================================================================

#include <jni.h>

#include <android/log.h>
#include <atomic>
#include <chrono>
#include <memory>
#include <string>
#include <thread>
#include <vector>

#include "audio_engine.h"
#include "qnn_runtime.h"

#define LOG_TAG "OneVoiceCore"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// Aggregated native engine state. A single instance is plenty for one
// conversation session; the Kotlin side serializes lifecycle calls.
struct Engine {
    std::unique_ptr<onevoice::AudioEngine> audio;
    std::unique_ptr<onevoice::QnnRuntime>  qnn;
    std::thread                            worker;
    std::atomic<bool>                      running{false};
};
std::unique_ptr<Engine> g_engine;

// Fixed chunk the ASR window consumes per pass (1 s @ 16 kHz).
constexpr std::size_t kChunkSamples = 16000;

// ------------------------------------------------------------------------
// workerLoop() — the AI cascade. Runs entirely in C++, never touches the JVM.
// ------------------------------------------------------------------------
void workerLoop(Engine* e) {
    LOGI("AI worker thread started");
    std::vector<int16_t> inChunk(kChunkSamples);
    std::vector<int16_t> outChunk;
    // executeCascade() is not implemented yet (no Android host-glue), so it
    // fails on every chunk. Log the first one and then stay quiet instead of
    // filling logcat at 1 Hz for the whole session.
    bool loggedExecuteFailure = false;

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
        if (!e->qnn->executeCascade(inChunk.data(), got, outChunk)) {
            if (!loggedExecuteFailure) {
                LOGW("cascade execute unavailable — audio captured but not "
                     "translated (host-glue not implemented; see HANDOFF.md)");
                loggedExecuteFailure = true;
            }
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

// ---- Minimal JSON writer -------------------------------------------------
// The load result is a small, fixed-shape record and pulling in a JSON library
// for one struct is not worth the binary size. Escaping covers what QNN tensor
// names and dlerror() strings can actually contain.
std::string jsonEscape(const std::string& in) {
    std::string out;
    out.reserve(in.size() + 8);
    for (const char c : in) {
        switch (c) {
            case '"':  out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n";  break;
            case '\r': out += "\\r";  break;
            case '\t': out += "\\t";  break;
            default:
                if (static_cast<unsigned char>(c) < 0x20) {
                    char buf[7];
                    snprintf(buf, sizeof(buf), "\\u%04x", c);
                    out += buf;
                } else {
                    out += c;
                }
        }
    }
    return out;
}

std::string tensorsToJson(const std::vector<onevoice::TensorInfo>& ts) {
    std::string s = "[";
    for (std::size_t i = 0; i < ts.size(); ++i) {
        if (i) s += ",";
        s += "{\"name\":\"" + jsonEscape(ts[i].name) + "\",";
        s += "\"dtype\":\"" + jsonEscape(ts[i].dataType) + "\",";
        s += "\"dims\":[";
        for (std::size_t k = 0; k < ts[i].dims.size(); ++k) {
            if (k) s += ",";
            s += std::to_string(ts[i].dims[k]);
        }
        s += "]}";
    }
    return s + "]";
}

std::string resultToJson(const onevoice::ModelLoadResult& r) {
    char ms[32];
    snprintf(ms, sizeof(ms), "%.1f", r.loadMs);
    std::string s = "{";
    s += "\"stage\":\"" + jsonEscape(r.stage) + "\",";
    s += "\"ok\":" + std::string(r.ok ? "true" : "false") + ",";
    s += "\"loadMs\":" + std::string(ms) + ",";
    s += "\"sizeBytes\":" + std::to_string(r.sizeBytes) + ",";
    s += "\"graphName\":\"" + jsonEscape(r.graphName) + "\",";
    s += "\"socModel\":" + std::to_string(r.socModel) + ",";
    s += "\"buildId\":\"" + jsonEscape(r.buildId) + "\",";
    s += "\"inputs\":" + tensorsToJson(r.inputs) + ",";
    s += "\"outputs\":" + tensorsToJson(r.outputs) + ",";
    s += "\"error\":\"" + jsonEscape(r.error) + "\"";
    return s + "}";
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

// ---- nativeInitRuntime(backendPath, systemPath, adspPath, signedPd) --------
// Opens the audio streams and brings up the QNN backend + device. Loads NO
// models: the cascade stages are loaded individually by nativeLoadModel so the
// dashboard can report each one.
JNIEXPORT jboolean JNICALL
Java_com_onevoice_link_core_OneVoiceNative_nativeInitRuntime(
        JNIEnv* env, jclass /*clazz*/,
        jstring backendPath, jstring systemPath, jstring adspLibraryPath,
        jboolean useSignedProcessDomain) {
    if (g_engine) return JNI_TRUE;  // already initialized

    auto e = std::make_unique<Engine>();
    e->audio = std::make_unique<onevoice::AudioEngine>();
    if (e->audio->init() != 0) {
        LOGE("AudioEngine init failed");
        return JNI_FALSE;
    }

    e->qnn = std::make_unique<onevoice::QnnRuntime>();
    if (!e->qnn->initBackend(jstr(env, backendPath), jstr(env, systemPath),
                             jstr(env, adspLibraryPath),
                             useSignedProcessDomain == JNI_TRUE)) {
        LOGE("QNN backend init failed");
        return JNI_FALSE;
    }

    g_engine = std::move(e);
    LOGI("Runtime initialized (audio + QNN backend ready)");
    return JNI_TRUE;
}

// ---- nativeLoadModel(stage, binPath, graphName) : String (JSON) -----------
JNIEXPORT jstring JNICALL
Java_com_onevoice_link_core_OneVoiceNative_nativeLoadModel(
        JNIEnv* env, jclass /*clazz*/,
        jstring stage, jstring binPath, jstring graphName) {
    onevoice::ModelSpec spec;
    spec.stage       = jstr(env, stage);
    spec.contextPath = jstr(env, binPath);
    spec.graphName   = jstr(env, graphName);

    if (!g_engine || !g_engine->qnn) {
        onevoice::ModelLoadResult r;
        r.stage = spec.stage;
        r.error = "runtime not initialized";
        return env->NewStringUTF(resultToJson(r).c_str());
    }
    const auto r = g_engine->qnn->loadModel(spec);
    return env->NewStringUTF(resultToJson(r).c_str());
}

// ---- nativeLoadedModelCount() : int ---------------------------------------
JNIEXPORT jint JNICALL
Java_com_onevoice_link_core_OneVoiceNative_nativeLoadedModelCount(
        JNIEnv* /*env*/, jclass /*clazz*/) {
    if (!g_engine || !g_engine->qnn) return 0;
    return static_cast<jint>(g_engine->qnn->modelCount());
}

// ---- nativeReleaseModels() : void -----------------------------------------
// Drops every loaded context but keeps backend/device up, so switching
// direction reloads only the .bin files, not the whole runtime.
JNIEXPORT void JNICALL
Java_com_onevoice_link_core_OneVoiceNative_nativeReleaseModels(
        JNIEnv* /*env*/, jclass /*clazz*/) {
    if (g_engine && g_engine->qnn) g_engine->qnn->releaseModels();
}

// ---- nativeStartEngine() : boolean ----------------------------------------
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

// ---- nativeStopEngine() : void --------------------------------------------
JNIEXPORT void JNICALL
Java_com_onevoice_link_core_OneVoiceNative_nativeStopEngine(JNIEnv* /*env*/, jclass /*clazz*/) {
    if (!g_engine) return;
    g_engine->running.store(false, std::memory_order_release);
    if (g_engine->worker.joinable()) g_engine->worker.join();
    g_engine->audio->stop();
    LOGI("Engine stopped");
}

// ---- nativeDestroyEngine() : void -----------------------------------------
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
