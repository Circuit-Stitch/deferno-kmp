// Convention plugin for a shared Kotlin Multiplatform library module
// (every core/* and feature/* module). It pins the cross-platform target set,
// the JVM toolchain, and the commonTest test framework in one place (ADR-0004:
// "convention plugins from day one"). Each module supplies only its android
// `namespace` and its module-to-module dependencies.

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    jvmToolchain(17)

    // Desktop / JVM-fast test path.
    jvm()

    // Android target (the KMP android library DSL — replaces the legacy `android {}`
    // top-level block). `namespace` is set per-module.
    android {
        compileSdk = 35
        minSdk = 26
        withHostTest {}
    }

    // Apple targets. Klibs cross-compile on any host; running iOS tests / linking
    // device frameworks happens on a macOS runner (ADR-0006).
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
