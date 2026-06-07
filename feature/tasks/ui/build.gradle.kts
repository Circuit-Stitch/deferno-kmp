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
            // Adaptive list/detail Panes (#29, ADR-0007 tier-2): the Android-native `TasksScreen`
            // renders the co-resident detail/tree slots as 1 or 2 panes by window size class via M3
            // `ListDetailPaneScaffold` (+ `currentWindowAdaptiveInfo()` for the continuous width metric,
            // ADR-0008 G1). These androidx adaptive artifacts are Android-only (the desktop two-pane in
            // jvmMain uses CMP `BoxWithConstraints` instead). They carry an explicit version (this is a
            // Compose *Multiplatform* module with no androidx Compose BOM) pinned to what the app shell's
            // BOM resolves, so both compile against the same adaptive API.
            implementation(libs.androidx.material3.adaptive)
            implementation(libs.androidx.material3.adaptive.layout)
        }
        // The desktop-native screen: a large-screen two-pane list + detail/tree layout (ADR-0007's
        // tier-2 "1 or 2 panes by size class") — the desktop counterpart of the Android single-pane
        // screen, not that phone layout stretched. It reuses the commonMain atoms (TaskRow,
        // PaneHeader, …) — `internal`, but visible here because jvmMain shares this module — and
        // renders the same shared Decompose components via `subscribeAsState()`.
        jvmMain.dependencies {
            implementation(project(":feature:tasks"))
            implementation(libs.decompose.extensions.compose)
        }
    }
}
