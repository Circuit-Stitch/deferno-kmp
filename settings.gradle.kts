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
include(":core:common")
include(":core:network")
include(":core:database")
include(":core:secure")
include(":core:data")
include(":core:domain")
include(":core:designsystem")
include(":core:di")

// Feature slices: each owns its shared Decompose component + ViewModel + state (commonMain + iOS).
include(":feature:auth")
include(":feature:tasks")
include(":feature:plan")

// Per-slice Compose Views (#27): a UI submodule on the Compose platforms only (Android + desktop,
// no iOS — iOS is SwiftUI). Kept separate from the slice's logic module because the Compose compiler
// plugin is module-wide and would break the logic module's iOS compilation (ADR-0004).
include(":feature:tasks:ui")
include(":feature:plan:ui")

// Per-platform application entry points.
include(":app:androidApp")
include(":app:desktopApp")
include(":app:iosApp")
