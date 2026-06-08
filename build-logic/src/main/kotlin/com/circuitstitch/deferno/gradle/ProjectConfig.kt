package com.circuitstitch.deferno.gradle

/**
 * Single source of truth for build-level configuration shared across the convention
 * plugins (and, through them, every module). Dependency *versions* live in
 * `gradle/libs.versions.toml`; these are project build settings (SDK levels, JVM
 * toolchain) that the catalog isn't the right home for.
 *
 * This object is the authority — `CLAUDE.md` describes these values for humans but
 * points here; change them in one place.
 */
object ProjectConfig {
    const val COMPILE_SDK = 35
    const val MIN_SDK = 26
    const val TARGET_SDK = 35

    /** JDK the Kotlin/Java toolchain pins (auto-provisioned via the Foojay resolver). */
    const val JVM_TOOLCHAIN = 17

    /**
     * The NDK the native speech library pins (#92, ADR-0018 — the repo's first native code). The
     * `deferno.android.nativelib` convention sets this on the JNI module so the whisper.cpp CMake build
     * uses one toolchain everywhere. Bump in lockstep with the locally installed/CI NDK side-by-side
     * version (`$ANDROID_HOME/ndk/<version>`).
     */
    const val NDK_VERSION = "30.0.14904198"
}
