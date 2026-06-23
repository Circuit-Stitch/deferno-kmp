plugins {
    id("deferno.kmp.library")
}

kotlin {
    android {
        namespace = "com.circuitstitch.deferno.feature.braindumps"
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:common")) // componentScope() (#174)
            implementation(project(":core:model")) // BrainDumpDraft
            implementation(project(":core:agent")) // Extractor + InferenceResult — the shared brain-dump pipeline (#265)
            implementation(project(":core:data")) // draft/recording persistence seams + keep-recordings + salvage counter (#265)
            // `api`, not `implementation`: `InboxComponent`/`DefaultInboxComponent` expose Decompose
            // (`ComponentContext`) and coroutines `StateFlow` in their public API, so View consumers
            // (`:feature:braindumps:ui`) and the shell must see them.
            api(libs.decompose)
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
        }
        // The Compose Views for this slice live in the sibling `:feature:braindumps:ui` module (Compose
        // platforms only, no iOS) — the Inbox review surface (ADR-0015 Inbox amendment).
    }
}
