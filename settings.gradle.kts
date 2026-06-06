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
// isn't installed locally — pairs with kotlin { jvmToolchain(17) } in app/build.gradle.kts
// and the daemon toolchain in gradle/gradle-daemon-jvm.properties.
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

// Feature slices: each owns its shared Decompose component + ViewModel + state.
include(":feature:auth")
include(":feature:tasks")
include(":feature:plan")

// Per-platform application entry points.
include(":app:androidApp")
include(":app:desktopApp")
include(":app:iosApp")
