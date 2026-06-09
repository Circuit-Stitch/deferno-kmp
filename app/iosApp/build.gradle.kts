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
            // Surface the shared presentation layer to SwiftUI (#51). The Tasks + Plan
            // Decompose components, the domain model (Task/TaskId/WorkingState), and the
            // Decompose/coroutines types their public API exposes must land in the generated
            // Deferno.framework header for the per-feature SwiftUI Views to render them.
            // `export(...)` requires the matching `api(project(...))` dependency below.
            //
            // SKIE (ADR-0003) — which bridges Flow/suspend/sealed into idiomatic Swift — is
            // still NOT applied (no released SKIE supports Kotlin 2.4.0 as of 2026-06; see
            // gradle/libs.versions.toml + ./README.md). Until it ships, the SwiftUI Views
            // observe `StateFlow`/`Value` through the hand-written, SKIE-free bridge in
            // `src/iosMain/.../ios/bridge` (ObservableState.swift wraps it on the Swift side).
            export(project(":feature:tasks"))
            export(project(":feature:plan"))
            export(project(":core:model"))
            export(libs.decompose)
            export(libs.kotlinx.coroutines.core)
        }
    }

    sourceSets {
        iosMain.dependencies {
            // `api` (not `implementation`) so the exported framework header carries these —
            // `export(...)` above only works for `api` dependencies.
            api(project(":feature:tasks"))
            api(project(":feature:plan"))
            api(project(":core:model"))
            api(libs.decompose)
            api(libs.kotlinx.coroutines.core)
            // Used only by the in-memory demo harness (DemoRepositories.kt) — the repository
            // ports the components construct over. Not part of the Swift-facing API, so not
            // exported. The real iOS app shell (a follow-up) will supply DI-provided
            // repositories instead (#68, ADR-0014); this stays a scaffold fixture.
            implementation(project(":core:data"))
            implementation(libs.kotlinx.datetime)
        }
    }
}
