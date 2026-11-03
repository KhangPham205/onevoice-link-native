// ============================================================================
// thermal_probe.cpp — AThermal_* via dlsym
// ----------------------------------------------------------------------------
// WHY dlsym AND NOT A DIRECT CALL: the NDK declares AThermal_* as
// __INTRODUCED_IN(30) but this module ships with minSdk 28. Referencing the
// symbols directly makes the dynamic linker refuse to load libonevoice_core.so
// on API 28/29 — the failure happens at load time, not at the call site, so
// guarding the call with a runtime version check would not save it.
//
// WHY NOT /sys/class/thermal: on retail Samsung firmware SELinux denies an app
// domain access to those nodes. The harness reads them over adb instead, where
// the shell domain is permitted.
// ============================================================================

#include "thermal_probe.h"

#include <android/log.h>
#include <dlfcn.h>

#define LOG_TAG "OneVoiceThermal"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace onevoice {
namespace {

using AcquireFn   = void* (*)();
using ReleaseFn   = void  (*)(void*);
using GetStatusFn = int   (*)(void*);

// Mirrors enum AThermalStatus from <android/thermal.h>; duplicated so this file
// does not have to include a header whose symbols it deliberately avoids.
const char* thermalStatusName(int status) {
    switch (status) {
        case -1: return "unavailable";
        case 0:  return "none";
        case 1:  return "light";
        case 2:  return "moderate";
        case 3:  return "severe";
        case 4:  return "critical";
        case 5:  return "emergency";
        case 6:  return "shutdown";
        default: return "unknown";
    }
}

}  // namespace

ThermalProbe::ThermalProbe() {
    library_ = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
    if (!library_) {
        LOGW("libandroid.so unavailable: %s", dlerror());
        return;
    }
    acquire_   = dlsym(library_, "AThermal_acquireManager");
    release_   = dlsym(library_, "AThermal_releaseManager");
    getStatus_ = dlsym(library_, "AThermal_getCurrentThermalStatus");
    if (!acquire_ || !release_ || !getStatus_) {
        LOGI("thermal API absent (pre-API-30 platform); status reporting off");
        return;
    }
    manager_ = reinterpret_cast<AcquireFn>(acquire_)();
    if (!manager_) {
        LOGW("AThermal_acquireManager returned null");
        return;
    }
    LOGI("thermal probe ready (status=%s)", statusName().c_str());
}

ThermalProbe::~ThermalProbe() {
    if (manager_ && release_) reinterpret_cast<ReleaseFn>(release_)(manager_);
    manager_ = nullptr;
    if (library_) dlclose(library_);
    library_ = nullptr;
}

int ThermalProbe::status() const {
    if (!manager_ || !getStatus_) return -1;
    return reinterpret_cast<GetStatusFn>(getStatus_)(manager_);
}

std::string ThermalProbe::statusName() const {
    return thermalStatusName(status());
}

}  // namespace onevoice
