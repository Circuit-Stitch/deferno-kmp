plugins {
    // Compose UI library on the Compose platforms only (Android + JVM/desktop), no iOS — holds the
    // Auth slice's minimal Compose View (#20). Sibling of `:feature:auth` for the same reason as
    // tasks/ui and plan/ui: the Compose compiler plugin is module-wide and would break the logic
    // module's iOS compilation (ADR-0004).
    id("deferno.compose.library")
}

kotlin {
    android {
        namespace = "com.circuitstitch.deferno.feature.auth.ui"
    }

    sourceSets {
        // The Android-native auth screen (#20): renders the shared AuthComponent. It is intentionally
        // the only View here — the tracer's "minimal screen" — so there are no shared commonMain atoms
        // yet (a desktop/iOS sign-in surface is its own follow-up, ADR-0007), and the screen stays in
        // androidMain rather than being a stretched cross-platform layout.
        androidMain.dependencies {
            implementation(project(":feature:auth"))
            implementation(project(":core:model"))
            // core:designsystem hosts the shared string catalog (composeResources Res accessors).
            implementation(project(":core:designsystem"))
            implementation(libs.compose.components.resources)

            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
        }
    }
}
