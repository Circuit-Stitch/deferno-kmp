plugins {
    // Compose UI library on the Compose platforms only (Android + JVM/desktop), no iOS — the
    // shared design system has no iOS consumer (iOS View = SwiftUI, ADR-0003/0004).
    id("deferno.compose.library")
}

kotlin {
    android {
        namespace = "com.circuitstitch.deferno.core.designsystem"
    }

    sourceSets {
        commonMain.dependencies {
            // Compose Multiplatform UI surface (resolves to androidx.compose.* on Android).
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.material3)
            // Compose Resources — the IBM Plex font families packaged in commonMain/composeResources.
            implementation(libs.compose.components.resources)
            // Markdown rendering for the reusable MarkdownDescription atom (the Task detail's NOTES /
            // GitHub-imported descriptions). Compose-target-only (this module has no iOS target); the
            // `-m3` module api-exposes the core renderer but both are declared for an explicit surface.
            implementation(libs.markdown.renderer)
            implementation(libs.markdown.renderer.m3)
            // LocalizedDateFormats bridges kotlinx types (LocalDate/LocalTime/Instant) to java.time
            // formatting — this module is Android+JVM only, so commonMain sees java.time (ADR-0004).
            implementation(libs.kotlinx.datetime)
        }
    }
}

// Stable package for the generated `Res` accessor so the fonts can be referenced from the theme
// (and, later, from feature Views) by a fixed import.
compose.resources {
    publicResClass = true
    packageOfResClass = "com.circuitstitch.deferno.core.designsystem.resources"
    generateResClass = always
}
