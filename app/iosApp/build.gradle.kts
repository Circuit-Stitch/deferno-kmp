plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// The iOS entry point: bundles the shared feature slices into a single `Deferno`
// framework that the Xcode project (see ./iosApp, added on macOS) links against.
// Apple-only module — no android/jvm targets. Klibs cross-compile on any host;
// linking the framework binary happens on a macOS runner (ADR-0006).
kotlin {
    // Keep in lockstep with ProjectConfig.JVM_TOOLCHAIN (the build's source of truth,
    // used by the deferno.* conventions). This bespoke iOS-only framework module can't
    // apply those conventions (different target set, no jvm()), and a dedicated
    // convention for a single module isn't earned yet (ADR-0004).
    jvmToolchain(17)

    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Deferno"
            isStatic = true
            // Public declarations of THIS module (e.g. IosGreeting in iosMain) already
            // land in the generated Deferno.framework header for Swift to call. To also
            // surface public APIs from the feature/core *dependencies*, switch their
            // `implementation(...)` below to `export(...)` (and have those modules declare
            // the deps `api(...)` so they're transitively exportable).
            //
            // SKIE (ADR-0003) — which bridges Flow/suspend/sealed into idiomatic Swift —
            // is deliberately NOT applied yet: no released SKIE supports Kotlin 2.4.0 as
            // of 2026-06, and SKIE's configuration-time version check would fail Gradle
            // sync on every host. When a 2.4.0-compatible SKIE ships, apply
            // `alias(libs.plugins.skie)` (see gradle/libs.versions.toml) and export the
            // feature components here.
        }
    }

    sourceSets {
        iosMain.dependencies {
            implementation(project(":feature:auth"))
            implementation(project(":feature:tasks"))
            implementation(project(":feature:plan"))
            // No :core:designsystem — it's a Compose UI module (Android + desktop). The iOS
            // View layer is SwiftUI with its own design system in the Xcode project (ADR-0004).
        }
    }
}
