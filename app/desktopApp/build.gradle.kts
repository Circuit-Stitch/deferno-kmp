import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JvmVendorSpec

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

// The desktop app runs on, and ships with, the **JetBrains Runtime** — the JDK Compose Desktop is
// built for. Stock OpenJDK on Linux renders worse and ends up with dated, AWT-drawn window chrome,
// while JBR integrates with the window manager (native KDE Breeze decorations, etc.) and the OS
// dark/light theme. `compose.desktop.application.javaHome` (set below) is what Compose uses for BOTH
// the dev `run` task and the jpackage/jlink packaging — so the bundled distribution runtime is JBR
// too. Resolved (and auto-provisioned) via the Foojay resolver at the project's Java 17 level
// (ProjectConfig.JVM_TOOLCHAIN); compile/test stay on the shared toolchain (the runtime JVM only
// matters for the GUI). jbrsdk ships jmods, so jlink/jpackage can build the native distributions.
val jetbrainsRuntimeHome: String = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(17))
    vendor.set(JvmVendorSpec.JETBRAINS)
}.get().metadata.installationPath.asFile.absolutePath

// Compose Desktop's own application DSL (replaces Gradle's built-in `application`):
// it owns `mainClass` and the `run` task and wires Skiko onto the runtime classpath.
compose.desktop.application {
    mainClass = "com.circuitstitch.deferno.desktop.MainKt"
    // Run + package on the JetBrains Runtime (see note above); bundles JBR into the distribution.
    javaHome = jetbrainsRuntimeHome
    nativeDistributions {
        // Each installer format only builds on its host OS; configuring all three is
        // harmless cross-platform (Deb is the one this Linux box can actually produce).
        targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
        packageName = "Deferno"
        packageVersion = "1.0.0"
    }
}

dependencies {
    // Compose Desktop runtime for the host OS (bundles Skiko) + Material 3 + the Material icon
    // set the navigation rail/drawer uses (the androidx adaptive nav-suite the Android shell uses
    // is an Android-only artifact, so desktop builds its own native rail/drawer here).
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    // Just the core Material icon set (the two glyphs the nav rail/drawer uses) — not the ≈37 MB
    // `materialIconsExtended` set, which the bundled desktop distribution would otherwise ship.
    implementation(libs.compose.material.icons.core)

    // The navigation shell (mirrors #55 / ADR-0013, desktop edition): the Compose-free shell
    // components (RootComponent: Auth ↔ Main; the Main shell's Destination graph) live in
    // src/main/.../shell, the desktop-native Views (rail/drawer + two-pane Tasks) beside them,
    // backed by the in-memory stub repositories + SampleData under src/main/.../demo (TEMPORARY
    // until DI lands, ADR-0008). The shell logic is duplicated from app/androidApp on purpose —
    // these are throwaway-until-DI stubs, and Android stays untouched.
    //
    // Feature slices (logic) the shell constructs:
    implementation(project(":feature:tasks"))
    implementation(project(":feature:plan"))
    // Desktop-native feature screens (the :ui submodules' jvmMain) — they reuse the slices' shared
    // Compose atoms (TaskRow, PaneHeader, …) and render the shared Decompose components.
    implementation(project(":feature:tasks:ui"))
    implementation(project(":feature:plan:ui"))
    // Data contracts + domain model for the demo repositories and the shell wiring.
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    // DefernoTheme for the Compose host.
    implementation(project(":core:designsystem"))

    // Decompose: the shell/Destination component tree + `subscribeAsState()` Compose bindings, and
    // `LifecycleController`, which drives Essenty's LifecycleRegistry off the desktop window state
    // (the desktop counterpart of Android's `retainedComponent`).
    implementation(libs.decompose)
    implementation(libs.decompose.extensions.compose)
    // The demo repositories expose Kotlin Flows; the shell computes today's date.
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.datetime)

    // JVM-fast unit tests for the (duplicated) Compose-free shell components — the desktop
    // counterpart of app/androidApp's shell tests (DefaultComponentContext + LifecycleRegistry,
    // no UI). JUnit 4 to match the Android shell tests.
    testImplementation(libs.junit)
}
