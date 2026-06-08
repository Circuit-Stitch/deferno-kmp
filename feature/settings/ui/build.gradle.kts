plugins {
    // Compose UI library on the Compose platforms only (Android + JVM/desktop), no iOS — holds the
    // Settings slice's tier-3 Compose Views (#72). Sibling of `:feature:settings` for the same reason
    // as profile/ui: the Compose compiler plugin is module-wide and would break the logic module's iOS
    // compilation (ADR-0004).
    id("deferno.compose.library")
}

kotlin {
    android {
        namespace = "com.circuitstitch.deferno.feature.settings.ui"
    }

    sourceSets {
        // The Android-native Settings screens (#72): render the shared SettingsComponent's tier-3
        // drill-down. Android-only for now — the iOS Settings is SwiftUI (ADR-0003/0004). core:designsystem
        // supplies the brand tokens (DefernoPalette for the Appearance preview, inkMuted for muted text).
        androidMain.dependencies {
            implementation(project(":feature:settings"))
            implementation(project(":core:model"))
            implementation(project(":core:designsystem"))

            implementation(libs.decompose)
            implementation(libs.decompose.extensions.compose)

            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            // The handful of nav glyphs the tier-3 drill-down uses (back arrow, the chevron on a
            // category row): `material-icons-core` (the small set, not the ≈37MB extended) — the same
            // choice the shell + desktop nav suite make. The JetBrains Compose Multiplatform artifact
            // (carries its own version, no androidx BOM needed in a CMP library module).
            implementation(libs.compose.material.icons.core.kmp)
        }
    }
}
