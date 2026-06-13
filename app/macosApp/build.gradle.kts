plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// The macOS entry point (ADR-0029): bundles the shared app shell + feature slices into a single
// `Deferno` framework that the native SwiftUI Xcode app (./macosApp) links — the macOS twin of
// app/iosApp. Apple-only module, **macosArm64** (Apple Silicon; Mac Catalyst can't link a
// Kotlin/Native iOS framework, so this is a real macOS target). Klibs cross-compile on any host;
// linking the framework binary happens on macOS (ADR-0006).
//
// Phase 1 renders the shared shell over an in-memory **demo** harness (DefernoDemoRoot) — no backend,
// no DI graph, no encrypted DB — so the SwiftUI Views are runnable today. The real DefernoRoot over
// the DI graph (the macOS twin of DefernoApplication + MainActivity) is Phase 1b. This mirrors how
// app/iosApp started (the DefernoDemo scaffold predated the real DefernoRoot, #51 → #35).
kotlin {
    // Keep in lockstep with ProjectConfig.JVM_TOOLCHAIN (the build's source of truth). Bespoke
    // Apple-only framework module — can't apply the deferno.* conventions (different target set), and
    // a dedicated convention for one module isn't earned yet (ADR-0004 / ADR-0029).
    jvmToolchain(17)

    macosArm64().binaries.framework {
        baseName = "Deferno"
        isStatic = true
        // Surface the shared presentation layer to SwiftUI, mirroring app/iosApp (ADR-0017/0029): the
        // SwiftUI Views render the whole shared shell (RootComponent → Auth/Main → the five
        // Destinations + the Search/New overlays), so the shell module, every feature slice whose
        // component/State types appear in a Swift-facing signature, the domain model, and the
        // Decompose/coroutines/datetime types those APIs expose must land in the generated
        // `Deferno.framework` header. `export(...)` requires the matching `api(project(...))` below.
        //
        // SKIE (ADR-0003) is still NOT applied (no released SKIE supports Kotlin 2.4.0 — see
        // app/iosApp/README.md). The SwiftUI Views observe StateFlow/Value/ChildStack/ChildSlot via the
        // same hand-written SKIE-free bridge as iOS (src/macosMain/.../bridge).
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

    sourceSets {
        macosMain.dependencies {
            // `api` (not `implementation`) so the exported framework header carries these.
            // Swift-facing (exported): the shared shell + feature slices + model + speech.
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

            // The Phase-1 demo harness (DefernoDemoRoot) builds DefaultRootComponent over in-memory
            // fakes that implement the data-layer repository + session seams + the command/domain types
            // the AccountSession exposes. NOT exported (Swift never names these) — `api` so macosMain
            // compiles against them; the SearchSort the SKIE-free bridge surfaces lives in core:data.
            api(project(":core:data"))
            api(project(":core:domain"))

            // The shared logger (DefernoDemoRoot configures it + emits the first log) — the uniform
            // facade (ADR-0029), os_log-backed on macOS. `implementation`: Swift never names it.
            implementation(project(":core:common"))

            // Phase 3 (ADR-0029): the InferenceEngine seam + the propose-only Extractor the on-device
            // Foundation Models engine drives. NOT exported — Swift names only the app-module bridge
            // types (NativeInference / DraftTasksBridge / DraftPreview), never a core:agent symbol.
            implementation(project(":core:agent"))
        }
    }
}
