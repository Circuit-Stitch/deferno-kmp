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
        // commonMain holds the reusable, stateless Profile atoms (#84): the identity card and the
        // co-located Account controls, shared by BOTH the Android (androidMain) and desktop (jvmMain)
        // screens — one definition, no per-platform copies (ADR-0007). All Compose-Multiplatform-common;
        // no iOS (the iOS Profile is SwiftUI, ADR-0003/0004). core:designsystem supplies the brand tokens
        // (plexMono for @handle, inkMuted for muted text); core:model the User/Account the atoms render.
        commonMain.dependencies {
            implementation(project(":core:model"))
            implementation(project(":core:designsystem"))

            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
        }
        // The Android-native Profile screen (#70): renders the shared ProfileComponent (#71), reusing
        // the commonMain atoms. Kept out of commonMain so desktop/iOS get their own native screens.
        androidMain.dependencies {
            implementation(project(":feature:profile"))
        }
        // The desktop-native Profile screen (#84): renders the shared ProfileComponent, reusing the
        // commonMain atoms — the desktop counterpart of the Android screen (ADR-0017), not it stretched.
        jvmMain.dependencies {
            implementation(project(":feature:profile"))
        }
        // The desktop render test (#84, cf. #39): a Compose-Multiplatform UI test on the JVM-fast path
        // (no device) driving the screen over a fake ProfileComponent (no DI graph, no AuthRepository).
        jvmTest.dependencies {
            implementation(libs.compose.ui.test.junit4)
            implementation(compose.desktop.currentOs)
        }
    }
}
