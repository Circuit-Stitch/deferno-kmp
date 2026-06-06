import com.circuitstitch.deferno.gradle.ProjectConfig

// Android *application* convention for the androidApp entry point. AGP 9 compiles
// Kotlin itself (built-in Kotlin support), so there is no separate kotlin-android
// plugin to apply. This shares the SDK levels + JVM toolchain with the library
// convention (`deferno.android`) via ProjectConfig, so a bump lands in one place.
// App-specific config (applicationId, buildTypes, Compose, dependencies) stays in
// the app's own build file.

plugins {
    id("com.android.application")
}

android {
    compileSdk = ProjectConfig.COMPILE_SDK

    defaultConfig {
        minSdk = ProjectConfig.MIN_SDK
        targetSdk = ProjectConfig.TARGET_SDK
    }
}

// Single source of truth for the JVM target (auto-provisioned via the Foojay
// resolver in settings.gradle.kts when JDK 17 isn't installed locally).
kotlin {
    jvmToolchain(ProjectConfig.JVM_TOOLCHAIN)
}
