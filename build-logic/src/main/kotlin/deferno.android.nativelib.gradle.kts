import com.circuitstitch.deferno.gradle.ProjectConfig

// Android convention for a bespoke *native* (JNI) library module (#92, ADR-0018 — the repo's first
// native code). Uses the classic `com.android.library` plugin because only its `android {}` DSL exposes
// externalNativeBuild / NDK / CMake — the KMP android-library target (`deferno.android`) does not.
//
// It pins the SDK levels + the NDK from ProjectConfig in one place; the module supplies the bits that
// are genuinely its own (its `namespace`, the CMake path, and the ABI filters). AGP 9 compiles Kotlin
// itself, so there is no separate kotlin-android plugin. This module is intentionally NOT a KMP/coverage
// module: it only emits a JNI `.so` consumed by core/speech's androidMain, so it lives outside
// `:core:`/`:feature:` and the merged coverage gate never tries to measure it (ADR-0006).

plugins {
    id("com.android.library")
}

android {
    compileSdk = ProjectConfig.COMPILE_SDK

    defaultConfig {
        minSdk = ProjectConfig.MIN_SDK
    }

    // One NDK toolchain for the whisper.cpp CMake build (ADR-0018); see ProjectConfig.NDK_VERSION.
    ndkVersion = ProjectConfig.NDK_VERSION
}
