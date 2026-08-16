#pragma once

#include <string>

namespace onevoice {

/**
 * Android thermal headroom/status, read from native so a benchmark worker can
 * sample it without bouncing through JNI on every utterance.
 *
 * The NDK thermal API arrived in API 30 while this module targets minSdk 28, so
 * the symbols are resolved with dlsym rather than linked. Linking them directly
 * would make the library fail to load on 28/29 even though nothing calls them
 * there.
 */
class ThermalProbe {
public:
    ThermalProbe();
    ~ThermalProbe();

    ThermalProbe(const ThermalProbe&) = delete;
    ThermalProbe& operator=(const ThermalProbe&) = delete;

    /** True when the platform exposes the API and a manager was acquired. */
    bool available() const noexcept { return manager_ != nullptr; }

    /**
     * Current throttling status as the AThermalStatus enum value, or -1 when
     * unavailable. Reported as an int so callers need no NDK header.
     */
    int status() const;

    /** Human-readable form of [status] for logs and CSV columns. */
    std::string statusName() const;

private:
    void* library_ = nullptr;
    void* manager_ = nullptr;
    void* acquire_ = nullptr;
    void* release_ = nullptr;
    void* getStatus_ = nullptr;
};

}  // namespace onevoice
