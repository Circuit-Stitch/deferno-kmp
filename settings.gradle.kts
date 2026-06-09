pluginManagement {
    // Convention plugins (deferno.kmp.library, …) live in the build-logic composite build.
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

// Lets Gradle download a matching JDK for the project toolchain (JDK 17) when one
// isn't installed locally — pairs with ProjectConfig.JVM_TOOLCHAIN (applied via the
// deferno.* convention plugins) and the daemon toolchain in gradle/gradle-daemon-jvm.properties.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Deferno"

// Shared Kotlin Multiplatform core (ADR-0004): layered foundations (declared in ADR order).
include(":core:model")
include(":core:scopes")
include(":core:common")
include(":core:network")
include(":core:database")
include(":core:secure")
// On-device speech-to-text (ADR-0018, #92): the SpeechToText seam + selector + engine impls. A device
// capability bound at AppScope (identity-independent), mirroring core:secure. Sits beside it in the
// foundation layer — it depends only on core:scopes (AppScope) and core:model.
include(":core:speech")
include(":core:data")
include(":core:domain")
include(":core:designsystem")
include(":core:di")

// Feature slices: each owns its shared Decompose component + ViewModel + state (commonMain + iOS).
include(":feature:auth")
// Paste-PAT sign-in (#15, ADR-0023): the v1 Auth-shell surface — validate a pasted PAT via /auth/me,
// then establish the Account. Browser-OAuth + PKCE minting stacks on this seam when backend #299 lands.
include(":feature:signin")
include(":feature:tasks")
include(":feature:plan")
include(":feature:calendar")
include(":feature:profile")
include(":feature:settings")

// Per-slice Compose Views (#27): a UI submodule on the Compose platforms only (Android + desktop,
// no iOS — iOS is SwiftUI). Kept separate from the slice's logic module because the Compose compiler
// plugin is module-wide and would break the logic module's iOS compilation (ADR-0004).
include(":feature:auth:ui")
include(":feature:signin:ui")
include(":feature:tasks:ui")
include(":feature:plan:ui")
include(":feature:calendar:ui")
include(":feature:profile:ui")
include(":feature:settings:ui")

// The shared, Compose-free app Shell library (ADR-0017): the shell *components* (RootComponent, Main
// shell, Destination graph, AccountSession, New, Auth) rendered three ways by the per-platform Views
// in the app entry points below. Sits above feature/* and below app/*.
include(":app:shell")

// The bespoke native (JNI) whisper library (#92, ADR-0018 — the repo's first native code): a classic
// `com.android.library` module (the only Android module type whose DSL exposes externalNativeBuild/NDK)
// that compiles the vendored whisper.cpp submodule into a `.so` consumed by core:speech's androidMain.
// Kept OUTSIDE `:core:`/`:feature:` so the merged coverage gate never tries to measure native-only code.
include(":speech-whisper-jni")

// The whisper model's Play Asset Delivery install-time pack (#92, ADR-0019): ships small.en off the
// base-APK budget. A `com.android.asset-pack` module declared by app/androidApp's `assetPacks`.
include(":speech-model-pack")

// Per-platform application entry points.
include(":app:androidApp")
include(":app:desktopApp")
include(":app:iosApp")

// Startup Baseline Profile generator (cold-start AOT): a `com.android.test` Macrobenchmark module that
// drives app/androidApp's launch and emits the profile the release APK bundles. Tooling, not shippable.
include(":baselineprofile")
