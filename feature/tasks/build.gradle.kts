plugins {
    id("deferno.kmp.library")
}

kotlin {
    android {
        namespace = "com.circuitstitch.deferno.feature.tasks"
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:model"))
            implementation(project(":core:domain"))
            implementation(project(":core:data"))
            // Shared Decompose components + ViewModels/state (#25). Decompose api-exposes Essenty.
            implementation(libs.decompose)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
            implementation(libs.kotlinx.datetime) // construct Task fixtures (dateCreated: Instant)
        }
        // The design system is Compose (Android + desktop), not iOS — so the Android Views
        // depend on it from androidMain, never from the iOS-targeting commonMain (ADR-0004).
        androidMain.dependencies {
            implementation(project(":core:designsystem"))
        }
    }
}
