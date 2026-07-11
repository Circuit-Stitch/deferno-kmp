// Startup Baseline Profile generator (cold-start AOT). A `com.android.test` module that drives
// app/androidApp's startup journey on a connected device via Macrobenchmark and emits the Baseline
// Profile; the `androidx.baselineprofile` plugin then has app/androidApp bundle it so ProfileInstaller
// AOT-compiles the hot startup path on install (instead of the runtime DEX-verify + JIT the
// `run-from-apk` install otherwise pays on every launch).
//
// Hand-written build file (no `deferno.*` convention plugin) — like app/iosApp. The conventions
// target shippable KMP/Android-app modules; `com.android.test` fits none of them, and `ProjectConfig`
// isn't on a raw module's buildscript classpath without applying one. The SDK levels below are kept in
// sync with build-logic `ProjectConfig` (the source of truth); this module ships nothing.
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "com.circuitstitch.deferno.baselineprofile"
    // Keep in sync with ProjectConfig.COMPILE_SDK (37) / TARGET_SDK (36). minSdk is 28 — the floor
    // Baseline Profile generation requires on the target device (above the app's own minSdk 27; this
    // test APK never ships, and the emulator is well past 28).
    compileSdk = 37

    defaultConfig {
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // The app this generator launches, profiles, and writes the bundled profile back into.
    targetProjectPath = ":app:androidApp"
}

// Generate against whatever device/emulator is connected (ADR-0006: no Gradle-managed device wired —
// the dev/CI host attaches one). The plugin builds app/androidApp's `nonMinifiedRelease` variant,
// installs it + this generator, runs the journey, and writes the merged profile into
// app/androidApp/src/release/generated/baselineProfiles/.
baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.junit)
}

// Same JVM target the rest of the project compiles to (mirrors ProjectConfig.JVM_TOOLCHAIN = 21,
// auto-provisioned via the Foojay resolver in settings.gradle.kts).
kotlin {
    jvmToolchain(21)
}
