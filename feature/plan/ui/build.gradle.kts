plugins {
    // Compose UI library on the Compose platforms only (Android + JVM/desktop), no iOS — holds the
    // Plan slice's Compose View (#27). Sibling of `:feature:plan` for the same reason as tasks/ui.
    id("deferno.compose.library")
}

kotlin {
    android {
        namespace = "com.circuitstitch.deferno.feature.plan.ui"
    }

    sourceSets {
        // commonMain holds the reusable atoms (the plan row, state label, empty/loading states) for a
        // future desktop View to share; the Android-native screen lives in androidMain.
        commonMain.dependencies {
            implementation(project(":core:model"))
            implementation(project(":core:designsystem"))

            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
        }
        // The Android-native Plan screen (#27): renders the shared PlanComponent (#25). Kept out of
        // commonMain so desktop/iOS get their own native screens (ADR-0007), not this phone layout.
        androidMain.dependencies {
            implementation(project(":feature:plan"))
        }
    }
}
