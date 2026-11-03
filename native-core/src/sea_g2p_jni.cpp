// ============================================================================
// sea_g2p_jni.cpp — JNI bridge to sea-g2p, VieNeu-TTS's Vietnamese normaliser
// and grapheme-to-phoneme library (Rust, C ABI; include/sea_g2p.h upstream).
//
// Built as its own small library rather than inside libonevoice_core so that
// adding or rebuilding it never relinks the core. libsea_g2p_rs.so is
// dlopen()ed, not linked: it is an optional, separately built artifact
// (tools/models/build-sea-g2p-android.sh), and a Kokoro or Supertonic build
// must load without it.
//
// Strings cross JNI as UTF-8 byte arrays. GetStringUTFChars/NewStringUTF use
// modified UTF-8, which differs from the UTF-8 the library expects.
// ============================================================================
#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>

#include <mutex>
#include <string>

namespace {

constexpr const char* kTag = "OneVoiceSeaG2p";
constexpr int kExpectedAbi = 1;  // SEA_G2P_ABI_VERSION in sea_g2p.h

struct SeaG2pApi {
    int (*abi_version)() = nullptr;
    const char* (*last_error)() = nullptr;
    void* (*open)(const char*) = nullptr;
    void (*close)(void*) = nullptr;
    void (*string_free)(char*) = nullptr;
    char* (*phonemize)(const void*, const char*, int) = nullptr;
};

std::once_flag g_once;
SeaG2pApi g_api;
std::string g_load_error;

template <typename T>
bool bind(void* lib, const char* name, T& slot) {
    slot = reinterpret_cast<T>(dlsym(lib, name));
    if (slot == nullptr) g_load_error = std::string("missing symbol ") + name;
    return slot != nullptr;
}

void loadLibrary() {
    void* lib = dlopen("libsea_g2p_rs.so", RTLD_NOW | RTLD_LOCAL);
    if (lib == nullptr) {
        const char* why = dlerror();
        g_load_error = std::string("dlopen libsea_g2p_rs.so: ") + (why ? why : "unknown");
        return;
    }
    SeaG2pApi api;
    if (!bind(lib, "sea_g2p_abi_version", api.abi_version) ||
        !bind(lib, "sea_g2p_last_error", api.last_error) ||
        !bind(lib, "sea_g2p_open", api.open) ||
        !bind(lib, "sea_g2p_close", api.close) ||
        !bind(lib, "sea_g2p_string_free", api.string_free) ||
        !bind(lib, "sea_g2p_phonemize", api.phonemize)) {
        return;
    }
    const int abi = api.abi_version();
    if (abi != kExpectedAbi) {
        g_load_error = "sea-g2p ABI " + std::to_string(abi) + ", expected " +
                       std::to_string(kExpectedAbi);
        return;
    }
    g_api = api;
    __android_log_print(ANDROID_LOG_INFO, kTag, "sea-g2p loaded (ABI %d)", abi);
}

const SeaG2pApi* api(JNIEnv* env) {
    std::call_once(g_once, loadLibrary);
    if (g_api.phonemize == nullptr) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), g_load_error.c_str());
        return nullptr;
    }
    return &g_api;
}

void throwLastError(JNIEnv* env, const SeaG2pApi* api, const char* what) {
    const char* detail = api->last_error();
    std::string message = std::string(what) + ": " + (detail ? detail : "unknown error");
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message.c_str());
}

std::string utf8(JNIEnv* env, jbyteArray bytes) {
    const jsize size = env->GetArrayLength(bytes);
    std::string out(static_cast<size_t>(size), '\0');
    env->GetByteArrayRegion(bytes, 0, size, reinterpret_cast<jbyte*>(out.data()));
    return out;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_onevoice_link_core_SeaG2p_nativeOpen(JNIEnv* env, jclass, jbyteArray dictPath) {
    const SeaG2pApi* g2p = api(env);
    if (g2p == nullptr) return 0;
    const std::string path = utf8(env, dictPath);
    void* handle = g2p->open(path.c_str());
    if (handle == nullptr) throwLastError(env, g2p, "sea_g2p_open");
    return reinterpret_cast<jlong>(handle);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_onevoice_link_core_SeaG2p_nativePhonemize(
    JNIEnv* env, jclass, jlong handle, jbyteArray text, jboolean puncNorm) {
    const SeaG2pApi* g2p = api(env);
    if (g2p == nullptr) return nullptr;
    const std::string input = utf8(env, text);
    char* phones = g2p->phonemize(reinterpret_cast<const void*>(handle), input.c_str(),
                                  puncNorm ? 1 : 0);
    if (phones == nullptr) {
        throwLastError(env, g2p, "sea_g2p_phonemize");
        return nullptr;
    }
    const std::string result(phones);
    g2p->string_free(phones);
    jbyteArray out = env->NewByteArray(static_cast<jsize>(result.size()));
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(result.size()),
                            reinterpret_cast<const jbyte*>(result.data()));
    return out;
}

extern "C" JNIEXPORT void JNICALL
Java_com_onevoice_link_core_SeaG2p_nativeClose(JNIEnv* env, jclass, jlong handle) {
    if (handle == 0) return;
    const SeaG2pApi* g2p = api(env);
    if (g2p != nullptr) g2p->close(reinterpret_cast<void*>(handle));
}
