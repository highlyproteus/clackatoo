// SPDX-License-Identifier: GPL-3.0-only

// Native speech-to-text with NVIDIA Parakeet through parakeet.cpp (MIT, our fork in asr/native). It links its own
// static ggml with hidden symbols: the ml module builds ggml with loadable CPU backends, which parakeet cannot use.
// Only the JNI wrapper lives here; everything else is in the app module.
plugins {
    id("com.android.library")
}

android {
    namespace = "helium314.keyboard.clackatoo.asr"
    compileSdk = 37
    ndkVersion = "28.0.13004108"

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            // 64-bit ARM phones and the x86_64 emulator; other ABIs build the app without voice typing and grammar check
            abiFilters.addAll(listOf("arm64-v8a", "x86_64"))
        }
        externalNativeBuild {
            cmake {
                // ggml needs a newer API level than the app's minSdk 21; NativeMl catches the load failure on older systems
                arguments += listOf("-DANDROID_PLATFORM=android-28", "-DANDROID_STL=c++_shared")
                // Reproducible builds: ggml's GGML_ABORT and asserts (and parakeet's log macros) embed __FILE__, which would carry the checkout's path.
                val map = "-ffile-prefix-map=${rootDir.absolutePath}=/clackatoo"
                cFlags += map
                cppFlags += map
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
