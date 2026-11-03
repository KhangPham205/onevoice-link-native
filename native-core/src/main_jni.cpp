// ============================================================================
// main_jni.cpp — JNI boundary for QNN execution and native audio transforms
// ----------------------------------------------------------------------------
// This is the ONLY file that touches JNI. The entire audio->AI->audio cascade
// is orchestrated by NpuPipeline on a bounded Kotlin worker. This file exposes
// direct-buffer graph execution plus the native NeMo fbank and Kokoro HAR
// transforms; Android AudioRecord/AudioTrack live on the Kotlin side.
//
// Model loading is a separate, per-stage call: the pipeline is a cascade of
// independently-compiled context binaries, so the dashboard loads them one at a
// time and renders each result. See qnn_runtime.h.
// ============================================================================

#include <jni.h>

#include <android/log.h>
#include <memory>
#include <string>
#include <vector>

#include "qnn_runtime.h"
#include "asr_frontend.h"
#include "kokoro_audio.h"
#include "thermal_probe.h"

#define LOG_TAG "OneVoiceCore"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// Aggregated native QNN state. A single instance is plenty for one conversation
// session; the Kotlin side serializes lifecycle calls.
struct Engine {
    std::unique_ptr<onevoice::QnnRuntime>  qnn;
};
std::unique_ptr<Engine> g_engine;

// Snapshot handed across the two-call profile drain (stages first, then
// values), so both arrays describe the same samples. Only ever touched from the
// benchmark thread between utterances, never from the audio path.
std::vector<onevoice::QnnRuntime::ProfileSample> g_profileSnapshot;

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
// Brings up the QNN backend + device. Audio capture/playback is owned by the
// Kotlin NPU pipeline so opening an Oboe input here would reserve the mic twice.
// Loads NO
// models: the cascade stages are loaded individually by nativeLoadModel so the
// dashboard can report each one.
JNIEXPORT jboolean JNICALL
Java_com_onevoice_link_core_OneVoiceNative_nativeInitRuntime(
        JNIEnv* env, jclass /*clazz*/,
        jstring backendPath, jstring systemPath, jstring adspLibraryPath,
        jboolean useSignedProcessDomain) {
    if (g_engine) return JNI_TRUE;  // already initialized

    auto e = std::make_unique<Engine>();
    e->qnn = std::make_unique<onevoice::QnnRuntime>();
    if (!e->qnn->initBackend(jstr(env, backendPath), jstr(env, systemPath),
                             jstr(env, adspLibraryPath),
                             useSignedProcessDomain == JNI_TRUE)) {
        LOGE("QNN backend init failed");
        return JNI_FALSE;
    }

    g_engine = std::move(e);
    LOGI("Runtime initialized (QNN backend ready)");
    return JNI_TRUE;
}

// ---- nativeExecuteGraph(stage, directInputs, directOutputs) : String? -----
// Returns null on success and a diagnostic string on failure.
JNIEXPORT jstring JNICALL
Java_com_onevoice_link_core_OneVoiceNative_nativeExecuteGraph(
        JNIEnv* env, jclass /*clazz*/, jstring stage,
        jobjectArray inputBuffers, jobjectArray outputBuffers) {
    if (!g_engine || !g_engine->qnn) {
        return env->NewStringUTF("runtime not initialized");
    }
    const jsize inputCount = inputBuffers ? env->GetArrayLength(inputBuffers) : 0;
    const jsize outputCount = outputBuffers ? env->GetArrayLength(outputBuffers) : 0;
    std::vector<onevoice::GraphBuffer> inputs;
    std::vector<onevoice::GraphBuffer> outputs;
    inputs.reserve(static_cast<std::size_t>(inputCount));
    outputs.reserve(static_cast<std::size_t>(outputCount));

    auto collect = [&](jobjectArray array, jsize count,
                       std::vector<onevoice::GraphBuffer>& destination,
                       const char* kind) -> jstring {
        for (jsize i = 0; i < count; ++i) {
            jobject buffer = env->GetObjectArrayElement(array, i);
            void* address = buffer ? env->GetDirectBufferAddress(buffer) : nullptr;
            const jlong capacity = buffer ? env->GetDirectBufferCapacity(buffer) : -1;
            if (buffer) env->DeleteLocalRef(buffer);
            if (!address || capacity < 0) {
                const std::string message = std::string(kind) + " buffer " +
                    std::to_string(i) + " is not a direct ByteBuffer";
                return env->NewStringUTF(message.c_str());
            }
            destination.push_back(
                {address, static_cast<std::size_t>(capacity)});
        }
        return nullptr;
    };
    if (jstring error = collect(inputBuffers, inputCount, inputs, "input")) return error;
    if (jstring error = collect(outputBuffers, outputCount, outputs, "output")) return error;

    std::string error;
    if (!g_engine->qnn->executeGraph(jstr(env, stage), inputs, outputs, error)) {
        return env->NewStringUTF(error.c_str());
    }
    return nullptr;
}

// ---- nativeExtractAsrFeatures(samples, directOutput) : String? ------------
JNIEXPORT jstring JNICALL
Java_com_onevoice_link_core_OneVoiceNative_nativeExtractAsrFeatures(
        JNIEnv* env, jclass /*clazz*/, jfloatArray samples, jobject outputBuffer) {
    if (!samples || !outputBuffer) return env->NewStringUTF("null ASR frontend buffer");
    const jsize count = env->GetArrayLength(samples);
    float* output = static_cast<float*>(env->GetDirectBufferAddress(outputBuffer));
    const jlong capacity = env->GetDirectBufferCapacity(outputBuffer);
    if (!output || capacity < 0) {
        return env->NewStringUTF("ASR output is not a direct ByteBuffer");
    }
    std::vector<float> waveform(static_cast<std::size_t>(count));
    env->GetFloatArrayRegion(samples, 0, count, waveform.data());
    if (env->ExceptionCheck()) return env->NewStringUTF("cannot read ASR samples");
    std::string error;
    if (!onevoice::makeParakeetFeatures(
            waveform.data(), waveform.size(), output,
            static_cast<std::size_t>(capacity) / sizeof(float), error)) {
        return env->NewStringUTF(error.c_str());
    }
    return nullptr;
}

// ---- nativeExtractGipformerFeatures(samples, directOutput) : String? ------
JNIEXPORT jstring JNICALL
Java_com_onevoice_link_core_OneVoiceNative_nativeExtractGipformerFeatures(
        JNIEnv* env, jclass /*clazz*/, jfloatArray samples, jobject outputBuffer) {
    if (!samples || !outputBuffer) return env->NewStringUTF("null Gipformer frontend buffer");
    const jsize count = env->GetArrayLength(samples);
    float* output = static_cast<float*>(env->GetDirectBufferAddress(outputBuffer));
    const jlong capacity = env->GetDirectBufferCapacity(outputBuffer);
    if (!output || capacity < 0) {
        return env->NewStringUTF("Gipformer output is not a direct ByteBuffer");
    }
    std::vector<float> waveform(static_cast<std::size_t>(count));
    env->GetFloatArrayRegion(samples, 0, count, waveform.data());
    if (env->ExceptionCheck()) return env->NewStringUTF("cannot read Gipformer samples");
    std::string error;
    if (!onevoice::makeGipformerFeatures(
            waveform.data(), waveform.size(), output,
            static_cast<std::size_t>(capacity) / sizeof(float), error)) {
        return env->NewStringUTF(error.c_str());
    }
    return nullptr;
}

// ---- nativeExtractSpeechFlowFeatures(samples, directOutput, outFrames) ----
// outFrames receives the unpadded frame count the classifier masks with; the
// caller must forward it as input_2 or attention pools over the zero padding.
JNIEXPORT jstring JNICALL
Java_com_onevoice_link_core_OneVoiceNative_nativeExtractSpeechFlowFeatures(
        JNIEnv* env, jclass /*clazz*/, jfloatArray samples, jobject outputBuffer,
        jintArray outFrames) {
    if (!samples || !outputBuffer || !outFrames) {
        return env->NewStringUTF("null SpeechFlow frontend buffer");
    }
    if (env->GetArrayLength(outFrames) < 1) {
        return env->NewStringUTF("SpeechFlow frame-count array is empty");
    }
    const jsize count = env->GetArrayLength(samples);
    float* output = static_cast<float*>(env->GetDirectBufferAddress(outputBuffer));
    const jlong capacity = env->GetDirectBufferCapacity(outputBuffer);
    if (!output || capacity < 0) {
        return env->NewStringUTF("SpeechFlow output is not a direct ByteBuffer");
    }
    std::vector<float> waveform(static_cast<std::size_t>(count));
    env->GetFloatArrayRegion(samples, 0, count, waveform.data());
    if (env->ExceptionCheck()) return env->NewStringUTF("cannot read SpeechFlow samples");
    std::size_t validFrames = 0;
    std::string error;
    if (!onevoice::makeSpeechFlowFeatures(
            waveform.data(), waveform.size(), output,
            static_cast<std::size_t>(capacity) / sizeof(float),
            &validFrames, error)) {
        return env->NewStringUTF(error.c_str());
    }
    jint frames = static_cast<jint>(validFrames);
    env->SetIntArrayRegion(outFrames, 0, 1, &frames);
    return nullptr;
}

// ---- nativeBuildKokoroHar(directF0, directHar) : String? ------------------
JNIEXPORT jstring JNICALL
Java_com_onevoice_link_core_OneVoiceNative_nativeBuildKokoroHar(
        JNIEnv* env, jclass /*clazz*/, jobject f0Buffer, jobject harBuffer,
        jboolean resetPhase) {
    float* f0 = static_cast<float*>(env->GetDirectBufferAddress(f0Buffer));
    float* har = static_cast<float*>(env->GetDirectBufferAddress(harBuffer));
    const jlong f0Bytes = env->GetDirectBufferCapacity(f0Buffer);
    const jlong harBytes = env->GetDirectBufferCapacity(harBuffer);
    if (!f0 || !har || f0Bytes < 0 || harBytes < 0) {
        return env->NewStringUTF("Kokoro F0/HAR buffers must be direct ByteBuffers");
    }
    std::string error;
    if (!onevoice::makeKokoroHarmonics(
            f0, static_cast<std::size_t>(f0Bytes) / sizeof(float),
            har, static_cast<std::size_t>(harBytes) / sizeof(float),
            resetPhase == JNI_TRUE, error)) {
        return env->NewStringUTF(error.c_str());
    }
    return nullptr;
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

// ---- Benchmark surface -----------------------------------------------------
// Deliberately separate from the execute path: adding an out-parameter to
// nativeExecuteGraph would put benchmarking work on the hot call and change an
// arity that main_jni.cpp and OneVoiceNative.kt must keep in lockstep.

// ---- nativeSetQnnProfiling(enabled) : boolean ------------------------------
JNIEXPORT jboolean JNICALL
Java_com_onevoice_link_core_OneVoiceNative_nativeSetQnnProfiling(
        JNIEnv* /*env*/, jclass /*clazz*/, jboolean enabled) {
    if (!g_engine || !g_engine->qnn) return JNI_FALSE;
    return g_engine->qnn->setProfilingEnabled(enabled == JNI_TRUE)
        ? JNI_TRUE : JNI_FALSE;
}

// ---- nativeApplyHtpPowerConfig(mode, latencyUs, pollingUs) : String? -------
JNIEXPORT jstring JNICALL
Java_com_onevoice_link_core_OneVoiceNative_nativeApplyHtpPowerConfig(
        JNIEnv* env, jclass /*clazz*/, jstring mode,
        jint rpcControlLatencyUs, jint rpcPollingTimeUs) {
    if (!g_engine || !g_engine->qnn) {
        return env->NewStringUTF("runtime not initialized");
    }
    onevoice::QnnRuntime::PowerConfig config;
    config.mode = jstr(env, mode);
    config.rpcControlLatencyUs = static_cast<uint32_t>(rpcControlLatencyUs);
    config.rpcPollingTimeUs    = static_cast<uint32_t>(rpcPollingTimeUs);
    std::string error;
    if (!g_engine->qnn->applyPowerConfig(config, error)) {
        return env->NewStringUTF(error.c_str());
    }
    return nullptr;
}

// ---- nativeDrainQnnProfileStages() : String[] ------------------------------
// Stage name per sample, positionally aligned with nativeDrainQnnProfile().
// Names travel as strings rather than a hash because a hash would have to be
// reproduced identically in Kotlin, and std::hash is not specified to be stable
// across toolchains or even runs.
//
// Call order matters: this drains the buffer, so it must be called FIRST and
// nativeDrainQnnProfile() reads the same snapshot it produced.
JNIEXPORT jobjectArray JNICALL
Java_com_onevoice_link_core_OneVoiceNative_nativeDrainQnnProfileStages(
        JNIEnv* env, jclass /*clazz*/) {
    g_profileSnapshot.clear();
    if (g_engine && g_engine->qnn) g_profileSnapshot = g_engine->qnn->drainProfile();

    jclass stringClass = env->FindClass("java/lang/String");
    if (stringClass == nullptr) return nullptr;
    jobjectArray out = env->NewObjectArray(
        static_cast<jsize>(g_profileSnapshot.size()), stringClass, nullptr);
    if (out == nullptr) return nullptr;
    for (std::size_t i = 0; i < g_profileSnapshot.size(); ++i) {
        jstring name = env->NewStringUTF(g_profileSnapshot[i].stage.c_str());
        env->SetObjectArrayElement(out, static_cast<jsize>(i), name);
        env->DeleteLocalRef(name);
    }
    return out;
}

// ---- nativeDrainQnnProfile() : long[] --------------------------------------
// Flat [wallUs, executeUs, acceleratorUs, queueWaitUs, profiled] * n over the
// snapshot taken by nativeDrainQnnProfileStages(). A flat primitive array keeps
// this one JNI crossing with no per-sample object allocation. Returns an empty
// array rather than null so callers need no nullability special case.
JNIEXPORT jlongArray JNICALL
Java_com_onevoice_link_core_OneVoiceNative_nativeDrainQnnProfile(
        JNIEnv* env, jclass /*clazz*/) {
    constexpr jsize kFields = 5;
    const jsize count = static_cast<jsize>(g_profileSnapshot.size());
    jlongArray out = env->NewLongArray(count * kFields);
    if (out == nullptr) return nullptr;
    if (count == 0) return out;

    std::vector<jlong> flat(static_cast<std::size_t>(count) * kFields);
    for (jsize i = 0; i < count; ++i) {
        const auto& s = g_profileSnapshot[static_cast<std::size_t>(i)];
        flat[i * kFields + 0] = static_cast<jlong>(s.wallUs);
        flat[i * kFields + 1] = static_cast<jlong>(s.executeUs);
        flat[i * kFields + 2] = static_cast<jlong>(s.acceleratorUs);
        flat[i * kFields + 3] = static_cast<jlong>(s.queueWaitUs);
        flat[i * kFields + 4] = s.profiled ? 1 : 0;
    }
    env->SetLongArrayRegion(out, 0, count * kFields, flat.data());
    g_profileSnapshot.clear();
    return out;
}

// ---- nativeThermalStatus() : int -------------------------------------------
// AThermalStatus value, or -1 when the platform predates API 30. Sampled from
// the benchmark thread between utterances; the manager is created once and
// kept, because acquiring it per call would dominate what it measures.
JNIEXPORT jint JNICALL
Java_com_onevoice_link_core_OneVoiceNative_nativeThermalStatus(
        JNIEnv* /*env*/, jclass /*clazz*/) {
    static onevoice::ThermalProbe probe;
    return static_cast<jint>(probe.status());
}

// ---- nativeActivePowerMode() : String --------------------------------------
JNIEXPORT jstring JNICALL
Java_com_onevoice_link_core_OneVoiceNative_nativeActivePowerMode(
        JNIEnv* env, jclass /*clazz*/) {
    if (!g_engine || !g_engine->qnn) return env->NewStringUTF("unavailable");
    return env->NewStringUTF(g_engine->qnn->activePowerMode().c_str());
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
    LOGW("nativeStartEngine is obsolete; NpuEngine owns bounded AudioRecord/AudioTrack workers");
    return JNI_FALSE;
}

// ---- nativeStopEngine() : void --------------------------------------------
JNIEXPORT void JNICALL
Java_com_onevoice_link_core_OneVoiceNative_nativeStopEngine(JNIEnv* /*env*/, jclass /*clazz*/) {
    LOGI("nativeStopEngine: no native audio worker is active");
}

// ---- nativeDestroyEngine() : void -----------------------------------------
JNIEXPORT void JNICALL
Java_com_onevoice_link_core_OneVoiceNative_nativeDestroyEngine(JNIEnv* /*env*/, jclass /*clazz*/) {
    if (!g_engine) return;
    g_engine->qnn->shutdown();
    g_engine.reset();
    LOGI("Engine destroyed");
}

}  // extern "C"
