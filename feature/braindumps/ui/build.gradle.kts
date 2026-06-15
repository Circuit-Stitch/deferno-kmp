plugins {
    // Compose UI library on the Compose platforms only (Android + JVM/desktop), no iOS — holds the
    // Inbox slice's Compose View (#27, ADR-0015 Inbox amendment). Sibling of `:feature:braindumps`.
    id("deferno.compose.library")
}

kotlin {
    android {
        namespace = "com.circuitstitch.deferno.feature.braindumps.ui"
    }

    sourceSets {
        // commonMain holds the reusable atoms (the draft card, empty state) shared by the Android and
        // desktop Views; the per-platform screens that bind the InboxComponent live in androidMain/jvmMain.
        commonMain.dependencies {
            implementation(project(":core:model"))
            implementation(project(":core:designsystem"))

            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
        }
        // The Android-native Inbox screen: renders the shared InboxComponent (#27, ADR-0015 Inbox amendment).
        androidMain.dependencies {
            implementation(project(":feature:braindumps"))
        }
        // The desktop-native Inbox screen: renders the shared InboxComponent, reusing the commonMain atoms.
        jvmMain.dependencies {
            implementation(project(":feature:braindumps"))
        }
    }
}
