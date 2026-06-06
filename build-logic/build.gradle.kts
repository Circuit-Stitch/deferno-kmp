plugins {
    `kotlin-dsl`
}

// The convention plugins (precompiled `*.gradle.kts` scripts under src/main/kotlin)
// reference the AGP and Kotlin Multiplatform Gradle plugin APIs. `compileOnly` is
// enough to compile against them — the plugins themselves are applied (and so put
// on the classpath) by the consuming build's plugin resolution.
dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
}

// Match the project JDK toolchain (see kotlin { jvmToolchain(17) } in modules).
kotlin {
    jvmToolchain(17)
}
