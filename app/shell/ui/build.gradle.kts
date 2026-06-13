plugins {
    // Compose UI library on the Compose platforms only (Android + JVM/desktop), no iOS — the same
    // convention as the per-slice `:feature:*:ui` submodules (#27). It is a sibling of `:app:shell`
    // (components + iOS) rather than a source set inside it, because the Compose compiler plugin is
    // module-wide and would break the parent's iOS compilation (ADR-0004) — the #27 pattern applied
    // to the shell (#175).
    id("deferno.compose.library")
}

kotlin {
    android {
        namespace = "com.circuitstitch.deferno.shell.ui"
    }

    sourceSets {
        // commonMain holds the platform-neutral, stateless New-form atoms (#175): the explicit kind
        // picker, the dictating Title/Notes rows, the date and Event start/end rows, the create-status
        // and Dictation feedback, and the submit button — ONE binding to NewComponent's state, rendered
        // by both the Android overlay (app/androidApp NewScreen) and the desktop window (app/desktopApp
        // NewDesktopScreen), which keep layout + platform affordances only.
        commonMain.dependencies {
            // The shared, Compose-free shell component/state types the atoms bind to (NewState,
            // NewStatus, DictationField, DictationStatus — ADR-0017).
            implementation(project(":app:shell"))
            // ItemKind for the explicit kind picker (ADR-0015).
            implementation(project(":core:model"))
            // defernoColors (inkMuted) for the gentle notes + the shared mic glyph Res.drawable.ic_mic.
            implementation(project(":core:designsystem"))

            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            // Compose Resources runtime: the Dictation mic loads the design-system glyph
            // `Res.drawable.ic_mic` (#94) via `painterResource` — material-icons-core has no Mic.
            // core:designsystem packages the asset (publicResClass) but declares the loader as
            // `implementation`, so this module names its own dependency.
            implementation(libs.compose.components.resources)
            // The ShellChrome drawer's per-Destination glyphs (Plan/Calendar/Tasks/Profile/Settings) +
            // the menu/search icons: `material-icons-core` (the small set, not the ≈37 MB extended) — the
            // same choice the shell + per-slice UI modules make. The JetBrains Compose Multiplatform
            // artifact, so it resolves for both the Android and JVM compilations from commonMain.
            implementation(libs.compose.material.icons.core.kmp)
        }
    }
}
