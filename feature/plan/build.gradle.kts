plugins {
    id("deferno.kmp.library")
}

kotlin {
    android {
        namespace = "com.circuitstitch.deferno.feature.plan"
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:model"))
            implementation(project(":core:domain"))
            implementation(project(":core:data"))
            // Shared Decompose component + state for the daily Plan (#25).
            implementation(libs.decompose)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
        }
        // The design system is Compose (Android + desktop), not iOS — so the Android Views
        // depend on it from androidMain, never from the iOS-targeting commonMain (ADR-0004).
        androidMain.dependencies {
            implementation(project(":core:designsystem"))
        }
    }
}
