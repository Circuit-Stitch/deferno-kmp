import com.circuitstitch.deferno.gradle.ProjectConfig

// Convention for a Compose UI *library* that targets ONLY the Compose platforms — Android (Jetpack
// Compose) and the JVM (Compose Desktop). It deliberately OMITS the iOS targets: the iOS View layer
// is SwiftUI with its own design system (ADR-0003 / ADR-0004), so a Kotlin/Compose UI module has no
// iOS consumer — and Compose Multiplatform 1.11 no longer publishes the deprecated iosX64 variant of
// its runtime/resources, so an iOS target couldn't resolve Compose anyway. Used by core/designsystem.
//
// Sibling of `deferno.kmp.library` (jvm + iOS, for the shared *logic* modules): same Android SDK
// levels + JVM toolchain (ProjectConfig) + commonTest framework + Kover coverage, minus the iOS
// targets, plus the Compose plugins (via `deferno.compose`). A feature module that keeps its shared
// iOS code in commonMain instead applies `deferno.kmp.library` + `deferno.compose` and confines its
// Compose Views to androidMain (ADR-0004) — only a UI-*only* module drops iOS wholesale like this.

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("deferno.compose")
    id("deferno.coverage")
}

kotlin {
    jvmToolchain(ProjectConfig.JVM_TOOLCHAIN)

    // The two Compose platforms only — no iOS (see header).
    jvm()
    android {
        compileSdk = ProjectConfig.COMPILE_SDK
        minSdk = ProjectConfig.MIN_SDK

        // Package this module's composeResources into Android assets. Without this, the AGP 9 KMP
        // android target silently drops them from the APK (and from Robolectric's merged assets):
        // Res.string/Res.font would throw MissingResourceException on device (CMP-9547).
        androidResources.enable = true

        // Android unit tests run on the JVM host (no device) — the JVM-fast path.
        withHostTest {}
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
