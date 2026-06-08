plugins {
    // Compose UI library on the Compose platforms only (Android + JVM/desktop), no iOS — holds the
    // Calendar slice's Compose Views (#74). Sibling of `:feature:calendar` for the same reason as plan/ui.
    id("deferno.compose.library")
}

kotlin {
    android {
        namespace = "com.circuitstitch.deferno.feature.calendar.ui"
    }

    sourceSets {
        // commonMain holds the reusable, stateless atoms (the month grid, the day agenda + its rows,
        // the occurrence action sheet) so a future desktop View can share them; the Android-native
        // screen lives in androidMain.
        commonMain.dependencies {
            implementation(project(":core:model"))
            implementation(project(":core:designsystem"))

            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            // The month grid derives its 42 day cells from the visible month (LocalDate math).
            implementation(libs.kotlinx.datetime)
        }
        // The Android-native Calendar screen (#74): renders the shared CalendarComponent. Kept out of
        // commonMain so desktop/iOS get their own native screens (ADR-0007).
        androidMain.dependencies {
            implementation(project(":feature:calendar"))
        }
        // The desktop-native Calendar screen: renders the shared CalendarComponent, reusing the
        // commonMain atoms — the desktop counterpart of the Android screen (ADR-0007).
        jvmMain.dependencies {
            implementation(project(":feature:calendar"))
        }
        // The desktop render/UI test: a Compose-Multiplatform UI test on the JVM-fast path (no device)
        // over the stateless Calendar content — month grid markers, the day agenda's neutral chips, and
        // the kind-aware action set (reschedule hidden for habit/chore).
        jvmTest.dependencies {
            implementation(project(":feature:calendar"))
            implementation(compose.desktop.uiTestJUnit4)
            implementation(compose.desktop.currentOs)
        }
    }
}
