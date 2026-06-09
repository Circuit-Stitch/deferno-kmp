import com.circuitstitch.deferno.gradle.ProjectConfig

// JVM convention for the desktop entry point: the Kotlin/JVM plugin + the shared JVM
// toolchain (routed through ProjectConfig, same source as the KMP + Android conventions,
// so a bump lands in one place). It deliberately does NOT apply Gradle's built-in
// `application` plugin: app/desktopApp is a Compose Desktop app (ADR-0003), and the
// Compose plugin's own `compose.desktop.application {}` supplies the `run`/package tasks
// and `mainClass` — applying the built-in `application` plugin too would duplicate both.
// App-specific config (the Compose Desktop plugins, mainClass, dependencies) stays in
// the app's own build file.

plugins {
    id("org.jetbrains.kotlin.jvm")
}

// Single version source of truth (ADR-0021, #101): project.version is what Conveyor reads (via
// printConveyorConfig → app.version) and what the desktop `packageVersion` derives from. Routing it
// through ProjectConfig.APP_VERSION — the same source the Android versionName/versionCode use — means
// one bump moves the desktop package, the Android build, and the release tag together.
version = ProjectConfig.APP_VERSION

kotlin {
    jvmToolchain(ProjectConfig.JVM_TOOLCHAIN)
}
