import com.circuitstitch.deferno.gradle.ProjectConfig

// KMP foundation convention: the cross-platform target set, the JVM toolchain, and
// the commonTest framework — everything that is *not* Android-specific (ADR-0004).
// Composable: `deferno.android` layers the Android library target on top, and
// `deferno.kmp.library` bundles kmp + android + coverage for core/* and feature/*.
// Usable on its own for a future pure-Kotlin (no-Android) shared module.

plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

kotlin {
    jvmToolchain(ProjectConfig.JVM_TOOLCHAIN)

    // Desktop / JVM-fast test path (ADR-0006: the bulk of logic is tested here).
    jvm()

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
