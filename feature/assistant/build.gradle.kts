plugins {
    id("deferno.kmp.library")
}

kotlin {
    android {
        namespace = "com.circuitstitch.deferno.feature.assistant"
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:common")) // componentScope()
            implementation(project(":core:model"))
            implementation(project(":core:data")) // AssistantClient, ConversationStore, Connectivity seams
            // `api`, not `implementation`: `AssistantComponent`/`DefaultAssistantComponent` expose Decompose
            // (`ComponentContext`) and coroutines `StateFlow`/`Flow` in their public API, so View consumers
            // (the iOS framework + a later `:feature:assistant:ui`) must see them.
            api(libs.decompose)
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
        }
        // The SwiftUI chat View lives in app/iosApp; the Android/desktop Compose Views (and a sibling
        // :feature:assistant:ui module) are deferred until those platforms adopt the Assistant (ADR-0040).
    }
}
