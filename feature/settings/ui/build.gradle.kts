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
        // The desktop-native Settings screen (#85): renders the shared SettingsComponent's tier-3
        // drill-down on Compose Desktop — the desktop counterpart of the Android screen (ADR-0017),
        // not the phone layout stretched. Mirrors the androidMain deps; the icon glyphs come from the
        // desktop `material-icons-core` artifact (the desktop variant, not the KMP one androidMain uses).
        jvmMain.dependencies {
            implementation(project(":feature:settings"))
            implementation(project(":core:model"))
            implementation(project(":core:designsystem"))

            implementation(libs.decompose)
            implementation(libs.decompose.extensions.compose)

            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(libs.compose.material.icons.core)
        }
        // The desktop render/screenshot test (#85, cf. #39): a Compose-Multiplatform UI test on the
        // JVM-fast path (no device) over a real DefaultSettingsComponent + in-memory settings fakes.
        // core:data supplies the SettingsRepository/SettingsWriter the fakes implement.
        jvmTest.dependencies {
            implementation(project(":core:data"))
            implementation(compose.desktop.uiTestJUnit4)
            implementation(compose.desktop.currentOs)
        }
    }
}
