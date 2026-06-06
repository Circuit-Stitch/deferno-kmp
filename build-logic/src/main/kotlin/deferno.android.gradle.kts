import com.circuitstitch.deferno.gradle.ProjectConfig

// Android convention for a KMP *library* module: applies the KMP Android library
// plugin (the `kotlin { android {} }` target DSL — AGP 9's replacement for the legacy
// top-level `android {}` block) and pins the SDK levels in one place. Layers on the
// KMP foundation (`deferno.kmp`), so it can be applied directly or via
// `deferno.kmp.library`. Each module supplies only its android `namespace`.

plugins {
    id("deferno.kmp")
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    android {
        compileSdk = ProjectConfig.COMPILE_SDK
        minSdk = ProjectConfig.MIN_SDK

        // Android unit tests run on the JVM host (no device) — the JVM-fast path.
        withHostTest {}
    }
}
