import com.circuitstitch.deferno.gradle.ProjectConfig

// JVM *application* convention for the desktop entry point: the Kotlin/JVM plugin,
// the `application` plugin, and the shared JVM toolchain. Routes the toolchain
// through ProjectConfig (same source as the KMP + Android conventions) so a bump
// lands in one place. App-specific config (mainClass, dependencies, the Compose
// Desktop plugin in a later UI issue) stays in the app's own build file.

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("application")
}

kotlin {
    jvmToolchain(ProjectConfig.JVM_TOOLCHAIN)
}
