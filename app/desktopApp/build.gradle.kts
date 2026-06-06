import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    // Kotlin/JVM + the shared JVM toolchain (from ProjectConfig). This convention no
    // longer applies Gradle's `application` plugin — Compose Desktop supplies `run`.
    id("deferno.jvm.application")
    // Compose Desktop (ADR-0003: desktop View = Compose Desktop): the runtime + the
    // `compose.desktop.application` packaging/run DSL.
    alias(libs.plugins.compose.multiplatform)
    // The Compose *compiler* plugin (pinned to the Kotlin version via the catalog).
    alias(libs.plugins.kotlin.compose)
}

// Compose Desktop's own application DSL (replaces Gradle's built-in `application`):
// it owns `mainClass` and the `run` task and wires Skiko onto the runtime classpath.
compose.desktop.application {
    mainClass = "com.circuitstitch.deferno.desktop.MainKt"
    nativeDistributions {
        // Each installer format only builds on its host OS; configuring all three is
        // harmless cross-platform (Deb is the one this Linux box can actually produce).
        targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
        packageName = "Deferno"
        packageVersion = "1.0.0"
    }
}

dependencies {
    // Compose Desktop runtime for the host OS (bundles Skiko).
    implementation(compose.desktop.currentOs)
    // Material 3 (matches the Android host's design language).
    implementation(compose.material3)

    // Shared KMP feature slices (resolve to their JVM variants). Empty shells for now —
    // the desktop Views that host the shared Decompose tree land with the feature UIs.
    implementation(project(":feature:auth"))
    implementation(project(":feature:tasks"))
    implementation(project(":feature:plan"))
    implementation(project(":core:designsystem"))
}
