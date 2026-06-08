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
            // Brand theme for the `@Preview`s (DefernoTheme) — the screen itself relies on the host's
            // theme, but its previews render under the real design system like every other slice's do.
            implementation(project(":core:designsystem"))

            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            // The multiplatform `@Preview` annotation (androidx.compose.ui.tooling.preview, the same one
            // app/androidApp uses) so the auth screen renders in the IDE preview pane. Pulled via the CMP
            // `ui-tooling-preview` artifact (versioned by the Compose plugin) — this is a Compose
            // Multiplatform module with no androidx Compose BOM to version it against.
            implementation(compose.components.uiToolingPreview)
        }
    }
}
