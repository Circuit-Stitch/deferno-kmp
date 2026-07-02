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
        // KMP Android-library targets default androidResources off (no R class). The source-mark tree
        // row resolves its GitHub/Google vector drawables via this module's R on Android (androidMain/res)
        // — Robolectric isn't served a dependency module's composeResources, so the native res is needed.
        androidResources.enable = true
    }

    sourceSets {
        // commonMain holds the platform-neutral, stateless atoms (TaskRow, the status badge, the
        // pane header, empty/loading states) plus the pure secondary-pane precedence helper
        // (resolveSecondarySlot, #67) so the Android and desktop screens share one source. The
        // Android-native *screens* that arrange them live in androidMain.
        commonMain.dependencies {
            implementation(project(":core:model"))
            implementation(project(":core:designsystem"))
            // The precedence helper takes TaskPane (the component's foreground enum); :feature:tasks
            // sits in commonMain here — not duplicated per platform — so both screens inherit it.
            implementation(project(":feature:tasks"))

            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            // stringResource/pluralStringResource over core:designsystem's generated string catalog (Res);
            // also serves the desktop source-mark painterResource(DrawableResource), previously jvmMain-only.
            implementation(libs.compose.components.resources)
        }
        // The Android-native screens (#27): they render the shared Decompose components (#25) and
        // observe the co-resident detail/tree slots via `subscribeAsState()`. Kept out of commonMain
        // so desktop/iOS get their own native screens rather than this phone single-pane layout
        // stretched across platforms (ADR-0007's "stretched phone" non-goal).
        androidMain.dependencies {
            // The Search overlay View binds to the search query/sort value types (#73), which live in
            // the data layer (TaskSearchQuery, SearchSort) — feature:tasks exposes them but only via
            // `implementation`, so this module declares its own dependency to reference them directly.
            implementation(project(":core:data"))
            implementation(libs.decompose.extensions.compose)
            // The Task detail's attachment picker uses the Storage Access Framework via
            // `rememberLauncherForActivityResult` (androidx.activity.compose) — Android-only glue.
            implementation(libs.androidx.activity.compose)
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
            // The desktop Search overlay View binds to the search query/sort value types (#86):
            // SearchSort (and TaskSearchQuery) live in the data layer, which feature:tasks exposes only
            // via `implementation`, so jvmMain declares its own dependency to reference them directly —
            // mirroring androidMain (the Android SearchScreen needs the same types).
            implementation(project(":core:data"))
            implementation(libs.decompose.extensions.compose)
            // The desktop source-mark painter loads core:designsystem's Compose resource via its public
            // Res (Res.drawable.ic_source_*); painterResource(DrawableResource) comes from
            // libs.compose.components.resources, declared in commonMain above.
        }
        // The desktop render/screenshot test (#86, cf. #39): a Compose-Multiplatform UI test on the
        // JVM-fast path (no device) exercising the desktop Search View's empty/results/no-matches states
        // and asserting the open/dismiss intents are forwarded.
        jvmTest.dependencies {
            implementation(project(":core:data"))
            implementation(libs.compose.ui.test.junit4)
            implementation(compose.desktop.currentOs)
        }
    }
}
