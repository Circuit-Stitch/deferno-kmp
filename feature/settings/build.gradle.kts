plugins {
    id("deferno.kmp.library")
}

kotlin {
    android {
        namespace = "com.circuitstitch.deferno.feature.settings"
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:common")) // componentScope() (#174)
            implementation(project(":core:model"))
            implementation(project(":core:data"))
            // The device-local speech-engine [[App setting]] (#93): the Settings Destination reads the
            // AppScope SpeechEngineCatalog (registered engines + their availability + the device-local
            // choice) — distinct from the synced UserSettings the other categories drive (ADR-0018).
            api(project(":core:speech"))
            // The Agent opt-in + entitlement gate (#150): the Settings Destination reads the AppScope
            // AgentGate (device-local opt-in + per-Account relay entitlement) — `api` because
            // AgentSettings appears on the component's public surface, like the speech catalog above.
            api(project(":core:agent"))
            // `api`, not `implementation`: the component interface exposes Decompose (`ComponentContext`,
            // the tier-3 `ChildStack`) and coroutines (`StateFlow`) in its public signatures, so the View
            // consumer (`:feature:settings:ui`) must see them. Mirrors `:feature:profile`.
            api(libs.decompose)
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
        }
        // The Compose View for this slice lives in the sibling `:feature:settings:ui` module (Android +
        // JVM, no iOS): the Compose compiler plugin is module-wide and would break this module's iOS
        // compilation, so the slice's logic (here, iOS-capable) and its Android-native screens stay
        // separate (#27, ADR-0004).
    }
}
