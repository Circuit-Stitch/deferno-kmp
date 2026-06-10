plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// The iOS entry point: bundles the shared app shell + feature slices into a single `Deferno`
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
            // Surface the shared presentation layer to SwiftUI (#51, #35). The SwiftUI Views render
            // the **whole shared shell** now (RootComponent → Auth/Main → the five Destinations +
            // the Search/New overlays, ADR-0013/0017), not just the Tasks+Plan demo — so the shell
            // module, every feature slice whose component/State types appear in a Swift-facing
            // signature, the domain model, and the Decompose/coroutines/datetime types those APIs
            // expose must all land in the generated `Deferno.framework` header.
            //
            // `export(...)` requires the matching `api(project(...))` dependency below. Only the
            // modules whose types Swift *names* are exported; the DI graph + data/domain/network
            // layers the Kotlin `iosMain` bootstrap wires (DefernoRoot.kt) are `api`-but-not-export
            // (Swift never names AppComponent/AccountManager/repositories — only the bootstrap does).
            //
            // SKIE (ADR-0003) — which bridges Flow/suspend/sealed into idiomatic Swift — is
            // still NOT applied (no released SKIE supports Kotlin 2.4.0 as of 2026-06; see
            // gradle/libs.versions.toml + ./README.md). Until it ships, the SwiftUI Views
            // observe `StateFlow`/`Value`/`ChildStack`/`ChildSlot` through the hand-written,
            // SKIE-free bridge in `src/iosMain/.../ios/bridge` (ObservableState.swift wraps it).
            export(project(":app:shell"))
            export(project(":feature:tasks"))
            export(project(":feature:plan"))
            export(project(":feature:calendar"))
            export(project(":feature:profile"))
            export(project(":feature:settings"))
            export(project(":feature:signin"))
            export(project(":core:model"))
            export(project(":core:speech"))
            export(libs.decompose)
            export(libs.kotlinx.coroutines.core)
            export(libs.kotlinx.datetime)
        }
    }

    sourceSets {
        iosMain.dependencies {
            // `api` (not `implementation`) so the exported framework header carries these —
            // `export(...)` above only works for `api` dependencies.
            //
            // Swift-facing (exported): the shared shell + the feature slices + model + speech.
            api(project(":app:shell"))
            api(project(":feature:tasks"))
            api(project(":feature:plan"))
            api(project(":feature:calendar"))
            api(project(":feature:profile"))
            api(project(":feature:settings"))
            api(project(":feature:signin"))
            api(project(":core:model"))
            api(project(":core:speech"))
            api(libs.decompose)
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.datetime)

            // Kotlin-only (NOT exported): the bootstrap in `ios/DefernoRoot.kt` builds the real
            // AppComponent + per-Account AccountComponent over this DI/data graph (ADR-0008/0014)
            // and constructs DefaultRootComponent — the iOS analogue of DefernoApplication +
            // MainActivity. Swift never names these types; it only ever holds the assembled
            // RootComponent + the SKIE-free bridges. `api` so iosMain compiles against them and
            // their transitive AppScope bindings (secure Keychain vault, native SQLCipher driver,
            // Darwin Ktor engine) link into the framework.
            api(project(":core:di"))
            api(project(":core:scopes"))
            api(project(":core:data"))
            api(project(":core:domain"))
            api(project(":core:network"))
        }
    }
}
