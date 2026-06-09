plugins {
    id("deferno.android.nativelib")
}

// The bespoke native (JNI) whisper library (#92, ADR-0018 — the repo's first native code). It compiles
// the vendored, pinned whisper.cpp submodule (third_party/whisper.cpp) into `libwhisper_jni.so` via
// CMake/NDK and exposes a thin C bridge. It has NO Kotlin/business logic — the `external fun`
// declarations and the engine that drives them live in core:speech's androidMain (which depends on this
// module so the `.so` is packaged). Keeping the package of the JNI symbols (see whisper_jni.cpp) aligned
// with that `WhisperBridge` class is what wires them together.
android {
    namespace = "com.circuitstitch.deferno.speech.whisper"

    defaultConfig {
        ndk {
            // arm64-v8a for modern devices, x86_64 for emulators. 32-bit armeabi-v7a is deliberately
            // dropped: small.en needs ~850 MB runtime, impractical on the low-RAM 32-bit devices it
            // targets (and whisper.cpp's 32-bit ARM path needs extra FPU handling). v1 is 64-bit only.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                // C++17 for whisper.cpp/ggml; the lean build options are set in CMakeLists.txt (FORCE).
                // The CMake 4.x policy-version floor the old whisper.cpp sub-builds need is set inside
                // CMakeLists.txt (CMAKE_POLICY_VERSION_MINIMUM) — not as a `-D` arg here, which made
                // CMake warn that the variable went unused by the project.
                cppFlags += "-std=c++17"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            // CMake version intentionally unpinned: AGP picks an installed SDK CMake (≥3.22.1, the
            // floor the CMakeLists declares). Pin here only if the SDK gains multiple CMake versions.
        }
    }
}
