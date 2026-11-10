plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// ============================================================================
// Task 2 — Qualcomm QNN runtime library staging
// ----------------------------------------------------------------------------
// The native wrapper dlopen()s QNN after ADSP_LIBRARY_PATH is configured. CMake
// deliberately does not link these .so files (that would create eager NEEDED
// entries), but dlopen dependencies still have to be packaged in the APK.
//
// We stage the exact subset the core depends on into a build-managed folder and
// register it as an extra jniLibs source dir. Advantages over committing the
// binaries under src/main/jniLibs: no ~96 MB of vendored blobs in git, the list
// stays explicit here, and `Sync` keeps the staging dir clean across SDK bumps.
// ============================================================================
val qnnAndroidLibDir =
    file("../../native-core/third_party/qnn_sdk/lib/aarch64-android")
// Resolved eagerly to a File: AGP 9's jniLibs.srcDir rejects lazy Providers
// (it can't tell generated vs. static). The <abi> subdir lives beneath this root.
val qnnStagingRoot = layout.buildDirectory.dir("qnnJniLibs").get().asFile
val qnnSignedDspAssetsRoot = layout.buildDirectory.dir("qnnSignedDspAssets").get().asFile
val qnnSignedSkelSource = providers.gradleProperty("qnnSignedSkel")
    .orNull
    ?.let(::file)
val qnnPdMode = providers.gradleProperty("qnnPdMode").orElse("auto").get().also {
    require(it in setOf("auto", "signed", "unsigned")) {
        "qnnPdMode must be auto, signed, or unsigned (got '$it')"
    }
}
val routingMode = providers.gradleProperty("routingMode").get().also {
    require(it in setOf("DUAL_ASR", "LID")) {
        "routingMode must be DUAL_ASR or LID (got '$it')"
    }
}
val dualAsrMarginThreshold = providers.gradleProperty("dualAsrMarginThreshold")
    .get()
    .toDoubleOrNull()
    ?.takeIf(Double::isFinite)
    ?: error("dualAsrMarginThreshold must be a finite number")
val dualAsrFloorThreshold = providers.gradleProperty("dualAsrFloorThreshold")
    .get()
    .toDoubleOrNull()
    ?.takeIf(Double::isFinite)
    ?: error("dualAsrFloorThreshold must be a finite number")
val dualAsrPriorWeight = providers.gradleProperty("dualAsrPriorWeight")
    .get()
    .toDoubleOrNull()
    ?.takeIf { it.isFinite() && it >= 0.0 }
    ?: error("dualAsrPriorWeight must be a finite number >= 0")
// Coverage rules (DualAsrTurnRouter): emitted tokens per second of audio. 0 disables a rule.
fun nonNegativeDouble(name: String): Double = providers.gradleProperty(name).orElse("0.0").get()
    .toDoubleOrNull()
    ?.takeIf { it.isFinite() && it >= 0.0 }
    ?: error("$name must be a finite number >= 0")
val dualAsrCoverageLowRate = nonNegativeDouble("dualAsrCoverageLowRate")
val dualAsrCoverageHighRate = nonNegativeDouble("dualAsrCoverageHighRate")
val dualAsrCoverageTieRatio = nonNegativeDouble("dualAsrCoverageTieRatio")
val dualAsrMinWinnerRate = nonNegativeDouble("dualAsrMinWinnerRate")
val dualAsrCoverageTokenGap = providers.gradleProperty("dualAsrCoverageTokenGap").orElse("0").get()
    .toIntOrNull()
    ?.takeIf { it >= 0 }
    ?: error("dualAsrCoverageTokenGap must be an integer >= 0")
val vadMode = providers.gradleProperty("vadMode").orElse("SILERO").get().also {
    require(it in setOf("SILERO", "ENERGY")) { "vadMode must be SILERO or ENERGY (got '$it')" }
}
// Which microphone records (AudioInputMode). SELECT: the user picks Phone mic
// or USB-C mic on the Dashboard. PHONE / USB: fixed to that mic. DASHBOARD: the
// older Auto/Phone/Bluetooth selector. Only DASHBOARD records from Bluetooth;
// the others play to a connected A2DP headset, else the loudspeaker.
val audioInput = providers.gradleProperty("audioInput").orElse("SELECT").get().also {
    require(it in setOf("SELECT", "PHONE", "USB", "DASHBOARD")) {
        "audioInput must be SELECT, PHONE, USB or DASHBOARD (got '$it')"
    }
}
// Drop translation units made only of hesitation words ("ừ", "uh") and cut
// leading ones (FillerWords): mic noise decoded as "ừ" was spoken as "Yeah.".
val dropFillerWords = providers.gradleProperty("dropFillerWords").orElse("true").get().also {
    require(it == "true" || it == "false") { "dropFillerWords must be true or false (got '$it')" }
}.toBoolean()
val dualAsrEnOffset = providers.gradleProperty("dualAsrEnOffset").orElse("0.0").get()
    .toDoubleOrNull()
    ?.takeIf(Double::isFinite)
    ?: error("dualAsrEnOffset must be a finite number")
// ORT intra-op threads for each whole-utterance Kokoro session. SM8550 has one
// X3 + four A715/A710 performance cores; the value is an A/B knob, not a
// hidden decoder constant.
val kokoroThreads = providers.gradleProperty("kokoroThreads").orElse("2").get()
    .toIntOrNull()
    ?.takeIf { it in 1..8 }
    ?: error("kokoroThreads must be an integer in 1..8")
// Vietnamese target speech (en-vi): VIENEU (default; VieNeu-TTS v3 Nano,
// sea-g2p phonemes; needs models-vieneu staged and libsea_g2p_rs.so packaged),
// KOKORO (espeak-ng vi + aliased tokens) or SUPERTONIC (Supertonic-3 int8,
// grapheme input). Switching is an APK rebuild only.
val viTtsEngine = providers.gradleProperty("viTtsEngine").orElse("VIENEU").get().also {
    require(it in setOf("KOKORO", "SUPERTONIC", "VIENEU")) {
        "viTtsEngine must be KOKORO, SUPERTONIC or VIENEU (got '$it')"
    }
}
// VieNeu preset voice id as prepare-vieneu-nano.py slugs it (minh_quan, ai_han...).
val vieneuVoice = providers.gradleProperty("vieneuVoice").orElse("minh_quan").get().also {
    require(Regex("[a-z_]+").matches(it)) { "vieneuVoice must be a slug like minh_quan (got '$it')" }
}
val vieneuSteps = providers.gradleProperty("vieneuSteps").orElse("16").get()
    .toIntOrNull()
    ?.takeIf { it in 1..32 }
    ?: error("vieneuSteps must be an integer in 1..32")
val vieneuCfg = providers.gradleProperty("vieneuCfg").orElse("3.0").get()
    .toFloatOrNull()
    ?.takeIf { it in 0f..10f }
    ?: error("vieneuCfg must be a number in 0..10")
val vieneuSpeed = providers.gradleProperty("vieneuSpeed").orElse("1.0").get()
    .toFloatOrNull()
    ?.takeIf { it in 0.5f..2.0f }
    ?: error("vieneuSpeed must be a number in 0.5..2.0")
val vieneuThreads = providers.gradleProperty("vieneuThreads").orElse("4").get()
    .toIntOrNull()
    ?.takeIf { it in 1..8 }
    ?: error("vieneuThreads must be an integer in 1..8")
val vieneuSeed = providers.gradleProperty("vieneuSeed").orElse("1234").get()
    .toLongOrNull()
    ?: error("vieneuSeed must be an integer")
val supertonicVoice = providers.gradleProperty("supertonicVoice").orElse("F1").get().also {
    require(Regex("[FM][1-5]").matches(it)) {
        "supertonicVoice must be F1..F5 or M1..M5 (got '$it')"
    }
}
val supertonicSteps = providers.gradleProperty("supertonicSteps").orElse("5").get()
    .toIntOrNull()
    ?.takeIf { it in 1..32 }
    ?: error("supertonicSteps must be an integer in 1..32")
// Divides the duration predictor's estimate. On Vietnamese it over-allocates
// time, and Supertonic fills the slack with repeated words.
val supertonicSpeed = providers.gradleProperty("supertonicSpeed").orElse("1.05").get()
    .toFloatOrNull()
    ?.takeIf { it in 0.5f..2.0f }
    ?: error("supertonicSpeed must be a number in 0.5..2.0")
val supertonicThreads = providers.gradleProperty("supertonicThreads").orElse("4").get()
    .toIntOrNull()
    ?.takeIf { it in 1..8 }
    ?: error("supertonicThreads must be an integer in 1..8")
// Long turns (docs/LONG_FORM_SPEECH_IMPLEMENTATION.md). LEGACY keeps the strict
// one-window 10 s turn; ADAPTIVE keeps capturing across ASR windows. The other
// values are provisional starting points recorded in every session header.
val longTurnMode = providers.gradleProperty("longTurnMode").orElse("ADAPTIVE").get().also {
    require(it in setOf("LEGACY", "ADAPTIVE")) { "longTurnMode must be LEGACY or ADAPTIVE (got '$it')" }
}
fun longTurnInt(name: String, default: String, range: IntRange): Int =
    providers.gradleProperty(name).orElse(default).get().toIntOrNull()
        ?.takeIf { it in range }
        ?: error("$name must be an integer in $range")
val longTurnSoftSplitMs = longTurnInt("longTurnSoftSplitMs", "7000", 2_000..9_500)
val longTurnMicroPauseMs = longTurnInt("longTurnMicroPauseMs", "160", 40..800)
// A multiple of 80 ms keeps the CTC (80 ms) and RNN-T (40 ms) grids aligned.
val longTurnOverlapMs = longTurnInt("longTurnOverlapMs", "960", 80..2_000).also {
    require(it % 80 == 0) { "longTurnOverlapMs must be a multiple of 80 (got $it)" }
}
val longTurnMaxTurnS = longTurnInt("longTurnMaxTurnS", "120", 10..300)
require(dualAsrMarginThreshold >= 0.0) {
    "dualAsrMarginThreshold must be >= 0 (got $dualAsrMarginThreshold)"
}

val copyQnnRuntimeLibs = tasks.register<Sync>("copyQnnRuntimeLibs") {
    description = "Stage the QNN runtime .so files that libonevoice_core.so " +
        "dynamically depends on, so they ship under lib/arm64-v8a in the APK."
    group = "build"

    from(qnnAndroidLibDir) {
        include(
            "libQnnSystem.so",     // loads the serialized context onto the device
            "libQnnHtp.so",        // Hexagon HTP backend (dlopen'd via the core)
            "libQnnHtpPrepare.so", // on-device graph prepare/finalize for HTP
            "libQnnHtpV*Stub.so",  // CPU-side FastRPC stubs for every HTP arch (v68..v81)
        )
    }
    into(File(qnnStagingRoot, "arm64-v8a"))

    // Fail loudly at build time rather than shipping an APK that crashes on launch.
    // Copy the path into a local File first: referencing the script-level `val`
    // inside the action closure would capture the Gradle script object, which the
    // configuration cache cannot serialize. A plain File captures cleanly.
    val sdkLibDir = qnnAndroidLibDir
    doFirst {
        require(sdkLibDir.isDirectory) {
            "QNN SDK android libs not found at $sdkLibDir — vendor the " +
                "SDK under native-core/third_party/qnn_sdk before building."
        }
    }
}

// Optional licensed artifact injection:
//   ./gradlew :app:assembleDebug \
//     -PqnnSignedSkel=/secure/path/libQnnHtpV73Skel.so
//
// The DSP6 binary must be an asset, not an arm64 jniLib. It is extracted to a
// real file at runtime and selected together with signed-PD device config. When
// the property is absent this Sync task empties its output, preventing a stale
// signed binary from leaking into a later build.
val prepareQnnSignedDspAssets = tasks.register<Sync>("prepareQnnSignedDspAssets") {
    description = "Stage an externally supplied production/test-signed Hexagon v73 skeleton."
    group = "build"

    if (qnnSignedSkelSource != null) {
        from(qnnSignedSkelSource) {
            rename { "libQnnHtpV73Skel.so" }
        }
    }
    into(File(qnnSignedDspAssetsRoot, "dsp/signed"))

    val signedSkel = qnnSignedSkelSource
    doFirst {
        if (signedSkel != null) {
            require(signedSkel.isFile) {
                "qnnSignedSkel does not name a file: $signedSkel"
            }
        }
    }
}

android {
    namespace = "com.onevoice.link"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    // Pinned so every machine builds the core with the same toolchain; AGP's
    // implicit default drifts between plugin versions.
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.onevoice.link"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        // `auto` uses the matching unsigned QAIRT skeleton unless an explicitly
        // supplied signed skeleton is staged. `signed` never falls back to an
        // unsigned/OEM-mismatched image.
        buildConfigField("String", "QNN_PD_MODE", "\"$qnnPdMode\"")
        // Routing is a build-time experiment flag so A/B APKs are explicit and
        // thresholds are recorded in BuildConfig instead of hidden in decoder code.
        buildConfigField("String", "ROUTING_MODE", "\"$routingMode\"")
        buildConfigField(
            "double",
            "DUAL_ASR_MARGIN_THRESHOLD",
            dualAsrMarginThreshold.toString(),
        )
        buildConfigField(
            "double",
            "DUAL_ASR_FLOOR_THRESHOLD",
            dualAsrFloorThreshold.toString(),
        )

        buildConfigField(
            "double",
            "DUAL_ASR_PRIOR_WEIGHT",
            dualAsrPriorWeight.toString(),
        )
        buildConfigField("double", "DUAL_ASR_EN_OFFSET", dualAsrEnOffset.toString())
        buildConfigField("double", "DUAL_ASR_COVERAGE_LOW_RATE", dualAsrCoverageLowRate.toString())
        buildConfigField("double", "DUAL_ASR_COVERAGE_HIGH_RATE", dualAsrCoverageHighRate.toString())
        buildConfigField("int", "DUAL_ASR_COVERAGE_TOKEN_GAP", dualAsrCoverageTokenGap.toString())
        buildConfigField("double", "DUAL_ASR_COVERAGE_TIE_RATIO", dualAsrCoverageTieRatio.toString())
        buildConfigField("double", "DUAL_ASR_MIN_WINNER_RATE", dualAsrMinWinnerRate.toString())
        buildConfigField("int", "KOKORO_THREADS", kokoroThreads.toString())
        buildConfigField("String", "VAD_MODE", "\"$vadMode\"")
        buildConfigField("String", "AUDIO_INPUT", "\"$audioInput\"")
        buildConfigField("boolean", "DROP_FILLER_WORDS", dropFillerWords.toString())
        buildConfigField("String", "VI_TTS_ENGINE", "\"$viTtsEngine\"")
        buildConfigField("String", "SUPERTONIC_VOICE", "\"$supertonicVoice\"")
        buildConfigField("int", "SUPERTONIC_STEPS", supertonicSteps.toString())
        buildConfigField("int", "SUPERTONIC_THREADS", supertonicThreads.toString())
        buildConfigField("float", "SUPERTONIC_SPEED", "${supertonicSpeed}f")
        buildConfigField("String", "VIENEU_VOICE", "\"$vieneuVoice\"")
        buildConfigField("int", "VIENEU_STEPS", vieneuSteps.toString())
        buildConfigField("float", "VIENEU_CFG", "${vieneuCfg}f")
        buildConfigField("float", "VIENEU_SPEED", "${vieneuSpeed}f")
        buildConfigField("int", "VIENEU_THREADS", vieneuThreads.toString())
        buildConfigField("long", "VIENEU_SEED", "${vieneuSeed}L")
        buildConfigField("String", "LONG_TURN_MODE", "\"$longTurnMode\"")
        buildConfigField("int", "LONG_TURN_SOFT_SPLIT_MS", longTurnSoftSplitMs.toString())
        buildConfigField("int", "LONG_TURN_MICRO_PAUSE_MS", longTurnMicroPauseMs.toString())
        buildConfigField("int", "LONG_TURN_OVERLAP_MS", longTurnOverlapMs.toString())
        buildConfigField("int", "LONG_TURN_MAX_TURN_S", longTurnMaxTurnS.toString())

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Task 1 — realtime core is Snapdragon-only; build a single ABI.
        ndk {
            abiFilters.add("arm64-v8a")
        }

        // Task 1 — inject the optimized C++ toolchain flags and shared STL.
        // ANDROID_STL=c++_shared is required because Oboe/QNN and
        // our core must agree on a single libc++ instance across .so boundaries.
        externalNativeBuild {
            cmake {
                cppFlags("-std=c++20")
                arguments("-DANDROID_STL=c++_shared")
            }
        }
    }

    // Task 1 — bind the pre-existing native core by relative path.
    externalNativeBuild {
        cmake {
            path = file("../../native-core/CMakeLists.txt")
            version = "3.22.1" // matches cmake_minimum_required in the core
        }
    }

    // Task 2 — expose the staged QNN libs to the APK packager. AGP's default
    // main jniLibs source set (src/main/jniLibs) is also honored, so any libs
    // dropped there manually (e.g. a future signed skel) are picked up too.
    sourceSets {
        getByName("main") {
            jniLibs.srcDir(qnnStagingRoot)
            assets.srcDir(qnnSignedDspAssetsRoot)
        }
    }

    androidResources {
        // The Hexagon skel ships as an asset (ADSP_LIBRARY_PATH needs a real
        // file on disk, and packaged jniLibs are never extracted here). Storing
        // it uncompressed lets the app check its size via openFd and stream it
        // out without inflating 11 MB.
        noCompress += "so"
    }

    packaging {
        jniLibs {
            // Uncompressed + 16 KB page-aligned in the APK. Required so the
            // dynamic loader can mmap the libs directly on 16 KB-page devices
            // (Android 15+), matching how the core .so is aligned in CMake.
            useLegacyPackaging = false
            // sherpa-onnx is the complete offline fallback for ASR/TTS while
            // MarianMT uses the official ORT Android artifact. The static-link
            // sherpa AAR only overlaps ORT on unused 32-bit ABIs.
            pickFirsts.add("lib/**/libonnxruntime.so")
        }
    }

    buildTypes {
        debug {
            externalNativeBuild {
                cmake {
                    // Fast compilation for debug builds
                    cppFlags("-O0", "-g")
                }
            }
        }
        release {
            externalNativeBuild {
                cmake {
                    // Heavy optimization for production
                    cppFlags("-O3", "-flto")
                }
            }
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// Task 2 — a plain-File jniLibs srcDir does NOT auto-carry a task dependency, so
// wire the staging task explicitly ahead of the jniLibs source merge (which
// consumes the srcDir) and preBuild (so it also runs on a bare `assemble`).
tasks.withType<com.android.build.gradle.tasks.MergeSourceSetFolders>().configureEach {
    dependsOn(copyQnnRuntimeLibs, prepareQnnSignedDspAssets)
}
tasks.named("preBuild") {
    dependsOn(copyQnnRuntimeLibs, prepareQnnSignedDspAssets)
}

// libsea_g2p_rs.so is a git-ignored Rust build output. Without it a VIENEU APK
// still assembles and then fails at the first turn with UnsatisfiedLinkError,
// so refuse the jniLibs merge instead. Unit tests never merge jniLibs and keep
// running on machines without the Rust toolchain.
val seaG2pLib = file("src/main/jniLibs/arm64-v8a/libsea_g2p_rs.so")
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach {
    // Locals, so the action captures a String and a File, not the script.
    val needsSeaG2p = viTtsEngine == "VIENEU"
    val lib = seaG2pLib
    doFirst {
        check(!needsSeaG2p || lib.isFile) {
            "viTtsEngine=VIENEU needs ${lib.path}: run tools/models/build-sea-g2p-android.sh " +
                "or build with -PviTtsEngine=KOKORO"
        }
    }
}

dependencies {
    implementation(files("libs/sherpa-onnx-static-link-onnxruntime-1.13.4.aar"))
    implementation(libs.onnxruntime.android)
    implementation(libs.tensorflow.lite)
    implementation(libs.tensorflow.lite.select.tf.ops)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
