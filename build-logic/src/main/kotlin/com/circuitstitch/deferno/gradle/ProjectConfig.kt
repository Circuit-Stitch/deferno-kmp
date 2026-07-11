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
    // compileSdk 37 (Android 17, was 36): floored by mikepenz/multiplatform-markdown-renderer's
    // `-android` AAR metadata (`minCompileSdk 37` at v0.42.0+, the Task-detail markdown renderer).
    // compileSdk only widens the APIs available at compile time; it is deliberately DECOUPLED from
    // targetSdk below (Google's own guidance — the two move independently), so this bump carries no
    // runtime-behavior change.
    const val COMPILE_SDK = 37
    // minSdk 27 (Android 8.1, was 26): floored by amzn/kmp-logger's `kmp-logger-log-android`, which
    // declares minSdk 27 — the manifest merger requires the app's minSdk ≥ every library's.
    const val MIN_SDK = 27
    // targetSdk 36 (Android 16): opts the app in to API 36 runtime behavior. Held one below compileSdk
    // (37) on purpose — targetSdk changes runtime semantics and wants an API 37 emulator soak before it
    // moves, whereas compileSdk 37 is a pure compile-time floor from a dependency (see COMPILE_SDK).
    const val TARGET_SDK = 36

    /**
     * The single source of truth for the application version, semver (ADR-0021, #101). One value
     * drives every artifact's version and the release tag: the Android `versionName` (+ a derived
     * [ANDROID_VERSION_CODE]), the desktop `packageVersion` / `project.version` Conveyor reads (via
     * `printConveyorConfig` → `app.version`), and the release tag. It replaces the old split
     * (desktop `packageVersion "1.0.0"` vs Android `versionName "0.1.0"`/`versionCode 1`).
     *
     * The version is **explicit** — never derived from the clock (the repo's no-real-clock-dates
     * convention). Bump it here to move every artifact's reported version in lockstep, then push a
     * matching bare-semver tag (e.g. `0.1.0`, no `v` — Conveyor names the Release after `app.version`)
     * to cut a release. See `docs/RELEASING.md`.
     */
    const val APP_VERSION = "0.1.0"

    /**
     * A deterministic, monotonic Android `versionCode` derived from [APP_VERSION] as
     * `major*1_000_000 + minor*1_000 + patch` — so a higher semver always yields a higher code
     * (minor/patch each have 0–999 of headroom; the max stays far under Android's 2.1e9 ceiling).
     * Any pre-release/build metadata suffix (`-rc1`, `+meta`) is ignored — the code keys off the
     * numeric `major.minor.patch` core only, while `versionName` carries the full string.
     */
    val ANDROID_VERSION_CODE: Int
        get() {
            val (major, minor, patch) = APP_VERSION
                .substringBefore('-').substringBefore('+')
                .split('.')
                .let { parts -> Triple(parts.intAt(0), parts.intAt(1), parts.intAt(2)) }
            return major * 1_000_000 + minor * 1_000 + patch
        }

    private fun List<String>.intAt(index: Int): Int = getOrNull(index)?.toIntOrNull() ?: 0

    /**
     * JDK the Kotlin/Java toolchain pins (auto-provisioned via the Foojay resolver). 21 (LTS, was 17):
     * mikepenz/multiplatform-markdown-renderer v0.40.0+ ships **Java 21 bytecode** in BOTH its `-android`
     * and `-jvm` artifacts. Android re-dexes so a device is unaffected, but the JVM-hosted Robolectric
     * unit tests (and the desktop app) load those classes directly — on JDK 17 that throws
     * `UnsupportedClassVersionError` (class file 65.0). Bumping the toolchain to 21 lets every JVM
     * consumer load them. Move in lockstep with any future markdown-renderer bytecode bump.
     */
    const val JVM_TOOLCHAIN = 21

    /**
     * The NDK the native speech library pins (#92, ADR-0018 — the repo's first native code). The
     * `deferno.android.nativelib` convention sets this on the JNI module so the whisper.cpp CMake build
     * uses one toolchain everywhere. Bump in lockstep with the locally installed/CI NDK side-by-side
     * version (`$ANDROID_HOME/ndk/<version>`).
     */
    const val NDK_VERSION = "30.0.14904198"
}
