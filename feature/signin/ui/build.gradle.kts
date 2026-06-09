plugins {
    // Compose UI library on the Compose platforms only (Android + JVM/desktop), no iOS — holds the
    // Sign-in slice's Compose View (#15). Sibling of `:feature:signin` for the same reason as the other
    // slices: the Compose compiler plugin is module-wide and would break the logic module's iOS
    // compilation (ADR-0004 #27).
    id("deferno.compose.library")
}

kotlin {
    android {
        namespace = "com.circuitstitch.deferno.feature.signin.ui"
    }

    sourceSets {
        // The paste-PAT sign-in screen lives in commonMain: it is identical on Android and desktop (a
        // single token field, no adaptive layout), so it is ONE Compose-Multiplatform definition rather
        // than per-platform copies. core:designsystem supplies the theme the screenshot/render tests wrap
        // it in; :feature:signin supplies the SignInComponent it renders.
        commonMain.dependencies {
            implementation(project(":core:designsystem"))
            implementation(project(":feature:signin"))

            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
        }
        // The desktop render test (cf. feature:profile:ui): a Compose-Multiplatform UI test on the
        // JVM-fast path (no device) driving the screen over a fake SignInComponent — no DI graph.
        jvmTest.dependencies {
            implementation(libs.compose.ui.test.junit4)
            implementation(compose.desktop.currentOs)
        }
    }
}
