plugins {
    // Compose UI library on the Compose platforms only (Android + JVM/desktop), no iOS — holds the
    // Profile slice's Compose View (#70). Sibling of `:feature:profile` for the same reason as
    // tasks/ui and plan/ui: the Compose compiler plugin is module-wide and would break the logic
    // module's iOS compilation (ADR-0004).
    id("deferno.compose.library")
}

kotlin {
    android {
        namespace = "com.circuitstitch.deferno.feature.profile.ui"
    }

    sourceSets {
        // The Android-native Profile screen (#70): renders the shared ProfileComponent. Android-only
        // for now — the iOS Profile is SwiftUI (ADR-0003/0004) — so there are no shared commonMain
        // atoms yet and the screen stays in androidMain. core:designsystem supplies the brand tokens
        // (plexMono for @handle, inkMuted for muted text).
        androidMain.dependencies {
            implementation(project(":feature:profile"))
            implementation(project(":core:model"))
            implementation(project(":core:designsystem"))

            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            // The multiplatform `@Preview` annotation (androidx.compose.ui.tooling.preview, the same one
            // app/androidApp uses) so the Profile screen renders in the IDE preview pane. Pulled via the
            // CMP `ui-tooling-preview` artifact (versioned by the Compose plugin) — this is a Compose
            // Multiplatform module with no androidx Compose BOM to version it against.
            implementation(compose.components.uiToolingPreview)
        }
    }
}
