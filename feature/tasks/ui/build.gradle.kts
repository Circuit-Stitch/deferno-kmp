plugins {
    // Compose UI library on the Compose platforms only (Android + JVM/desktop), no iOS — the same
    // convention as core/designsystem. Holds the Tasks slice's Compose Views (#27). It is a sibling
    // of `:feature:tasks` (logic + iOS) rather than a source set inside it, because the Compose
    // compiler plugin is module-wide and would break the parent's iOS compilation (ADR-0004).
    id("deferno.compose.library")
}

kotlin {
    android {
        namespace = "com.circuitstitch.deferno.feature.tasks.ui"
    }

    sourceSets {
        // commonMain holds the platform-neutral, stateless atoms (TaskRow, the status badge, the
        // pane header, empty/loading states) so a future desktop (jvmMain) View + the design system
        // can reuse them. The Android-native *screens* that arrange them live in androidMain.
        commonMain.dependencies {
            implementation(project(":core:model"))
            implementation(project(":core:designsystem"))

            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
        }
        // The Android-native screens (#27): they render the shared Decompose components (#25) and
        // observe the co-resident detail/tree slots via `subscribeAsState()`. Kept out of commonMain
        // so desktop/iOS get their own native screens rather than this phone single-pane layout
        // stretched across platforms (ADR-0007's "stretched phone" non-goal).
        androidMain.dependencies {
            implementation(project(":feature:tasks"))
            implementation(libs.decompose.extensions.compose)
        }
    }
}
