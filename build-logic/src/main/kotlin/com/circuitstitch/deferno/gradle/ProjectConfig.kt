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
}
