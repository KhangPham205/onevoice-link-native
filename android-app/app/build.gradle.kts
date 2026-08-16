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
