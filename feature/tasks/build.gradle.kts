plugins {
    id("deferno.kmp.library")
}

kotlin {
    android {
        namespace = "com.circuitstitch.deferno.feature.tasks"
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:common")) // componentScope() (#174)
            implementation(project(":core:model"))
            implementation(project(":core:domain"))
            implementation(project(":core:data"))
            // `api`, not `implementation`: the component interfaces expose Decompose types (`Value`,
            // `ChildSlot`, `ComponentContext`) and coroutines `StateFlow` in their public signatures,
            // so View consumers (`:feature:tasks:ui`) must see them. Decompose api-exposes Essenty.
            api(libs.decompose)
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
            implementation(libs.kotlinx.datetime) // construct Task fixtures (dateCreated: Instant)
        }
        // The Compose Views for this slice live in the sibling `:feature:tasks:ui` module — a
        // Compose UI library on the Compose platforms only (Android + desktop, no iOS). The Compose
        // *compiler* plugin can't be applied here because this module also targets iOS (SwiftUI,
        // ADR-0004), and the compiler demands a Compose runtime the iOS classpath must not carry.
    }
}
