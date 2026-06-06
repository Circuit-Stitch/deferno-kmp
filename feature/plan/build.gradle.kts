plugins {
    id("deferno.kmp.library")
}

kotlin {
    android {
        namespace = "com.circuitstitch.deferno.feature.plan"
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:domain"))
            implementation(project(":core:data"))
        }
        // The design system is Compose (Android + desktop), not iOS — so the Android Views
        // depend on it from androidMain, never from the iOS-targeting commonMain (ADR-0004).
        androidMain.dependencies {
            implementation(project(":core:designsystem"))
        }
    }
}
