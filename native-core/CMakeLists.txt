# ============================================================================
# OneVoice Link  —  native-core build definition
# ----------------------------------------------------------------------------
# Builds libonevoice_core.so : a pure-C++ real-time Speech-to-Speech pipeline
#
#   Oboe capture  ->  SPSC ring buffer  ->  QNN / Hexagon HTP cascade
#                                                       |
#   Oboe playback  <-  SPSC ring buffer  <--------------+
#
# Target : Android arm64-v8a on Qualcomm Snapdragon (Hexagon HTP NPU).
# Design : zero-Java-overhead. The entire realtime path lives in native code so
#          ART GC pauses can never tear the audio or stall inference.
# ============================================================================
cmake_minimum_required(VERSION 3.22.1)   # minimum shipped by modern Android NDK

project(onevoice_core LANGUAGES CXX)

# ----------------------------------------------------------------------------
# Language standard & compiler flags
# ----------------------------------------------------------------------------
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)            # -std=c++20, not gnu++20

# Default to Release so the realtime core is always built for speed.
if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release CACHE STRING "Build type" FORCE)
endif()

# -O3 + Link-Time Optimization. -ffast-math is DELIBERATELY OMITTED: the INT8
# inference path must keep numerically well-defined reductions/accumulations.
set(CMAKE_CXX_FLAGS_RELEASE  "-O3 -flto -DNDEBUG")
set(CMAKE_C_FLAGS_RELEASE    "-O3 -flto -DNDEBUG")
# -Wl,-z,max-page-size=16384 : Android 15+ (API 35) requires 16 KB ELF segment
# alignment for native libs to load on 16 KB-page devices. Oboe sets this on
# itself (PRIVATE), so it does NOT propagate to libonevoice_core.so — apply it
# here explicitly. (Only affects shared libs; Oboe is static, so it's untouched.)
set(CMAKE_SHARED_LINKER_FLAGS "-flto -Wl,-O1 -Wl,--gc-sections -Wl,-z,max-page-size=16384")

# Hide symbols by default; only JNI extern "C" entry points are exported.
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)

# Function/data sectioning lets the linker garbage-collect dead code (--gc-sections).
add_compile_options(
    $<$<COMPILE_LANGUAGE:CXX>:-ffunction-sections>
    $<$<COMPILE_LANGUAGE:CXX>:-fdata-sections>
)
# ARMv8.2 dot-product + CRC + crypto help NEON vectorization that feeds the HTP.
# arm64-only: the x86_64 build (Android Studio emulator, QNN stub mode) must not
# see ARM -march flags.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    add_compile_options($<$<COMPILE_LANGUAGE:CXX>:-march=armv8.2-a+dotprod+crc+crypto>)
endif()

# ----------------------------------------------------------------------------
# Third-party dependency locations (vendored under native-core/third_party so
# the native build is self-contained — NOT at the repo root).
# ----------------------------------------------------------------------------
set(THIRD_PARTY_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party)
set(OBOE_DIR        ${THIRD_PARTY_DIR}/oboe)
set(QNN_SDK_DIR     ${THIRD_PARTY_DIR}/qnn_sdk)

# --- Google Oboe ------------------------------------------------------------
# Build from source when the subtree is present (preferred: full LTO control).
# Oboe's add_library(oboe ...) has no explicit type, so it defaults to STATIC —
# baked straight into libonevoice_core.so, no separate liboboe.so to ship.
if(EXISTS "${OBOE_DIR}/CMakeLists.txt")
    add_subdirectory(${OBOE_DIR} ${CMAKE_BINARY_DIR}/oboe EXCLUDE_FROM_ALL)
    set(OBOE_LIB oboe)
else()
    find_library(OBOE_LIB oboe
        PATHS ${OBOE_DIR}/lib/arm64-v8a NO_DEFAULT_PATH REQUIRED)
endif()

# ----------------------------------------------------------------------------
# Core library target
# ----------------------------------------------------------------------------
add_library(onevoice_core SHARED
    src/main_jni.cpp
    src/audio_engine.cpp
    src/ring_buffer.cpp
    src/qnn_inference.cpp
)

target_include_directories(onevoice_core PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/include
    ${OBOE_DIR}/include
    ${QNN_SDK_DIR}/include/QNN      # QnnInterface.h, QnnGraph.h, QnnMem.h, ...
)

# --- Qualcomm QNN prebuilt backend ------------------------------------------
# libQnnHtp.so drives the Hexagon HTP and is dlopen'd at runtime.
# libQnnSystem.so loads the serialized .dlc/.bin context onto the device.
# arm64-only: the prebuilts are aarch64; on x86_64 (emulator) the core still
# compiles against the QNN headers but the runtime dlopen simply fails and
# nativeInitEngine returns false — the Kotlin layer falls back to the HF path.
if(EXISTS "${QNN_SDK_DIR}/lib/aarch64-android" AND ANDROID_ABI STREQUAL "arm64-v8a")
    target_link_directories(onevoice_core PRIVATE ${QNN_SDK_DIR}/lib/aarch64-android)
    target_link_libraries(onevoice_core PRIVATE QnnSystem QnnHtp QnnHtpPrepare)
else()
    message(WARNING
        "QNN SDK libs not linked for ABI ${ANDROID_ABI}; QNN is available only "
        "on arm64-v8a with the SDK vendored under ${QNN_SDK_DIR}.")
endif()

# --- Android platform libraries ---------------------------------------------
find_library(log-lib       log)        # __android_log_print
find_library(opensles-lib  OpenSLES)   # fallback audio backend used by Oboe
find_library(aaudio-lib    aaudio)     # MMAP path used by Oboe on Android 8.1+

target_link_libraries(onevoice_core PRIVATE
    ${log-lib}
    ${opensles-lib}
    ${aaudio-lib}
    ${OBOE_LIB}
)

# Strip local symbols from release binaries to shrink the APK payload.
if(CMAKE_BUILD_TYPE STREQUAL "Release")
    add_custom_command(TARGET onevoice_core POST_BUILD
        COMMAND ${CMAKE_STRIP} -g $<TARGET_FILE:onevoice_core>)
endif()
