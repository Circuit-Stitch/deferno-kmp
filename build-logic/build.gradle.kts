plugins {
    `kotlin-dsl`
}

// The convention plugins (precompiled `*.gradle.kts` scripts under src/main/kotlin)
// reference the AGP / Kotlin / Kover Gradle plugin APIs. `compileOnly` is enough to
// compile against them — the plugins themselves are put on each project's buildscript
// classpath by the consuming (root) build's plugin resolution.
//
// INVARIANT: every external plugin a convention plugin applies via its `plugins {}`
// block must ALSO be declared `apply false` in the root build.gradle.kts. compileOnly
// only satisfies *compilation* here; without the root declaration the plugin is absent
// at apply-time and consumers fail with "plugin not found" (a runtime, not compile,
// error). Adding Kover meant touching both this file and the root build for that reason.
dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.kover.gradlePlugin)
    compileOnly(libs.ksp.gradlePlugin)
    compileOnly(libs.compose.gradlePlugin)
    compileOnly(libs.compose.compiler.gradlePlugin)
}

// Match the project JDK toolchain (see kotlin { jvmToolchain(17) } in modules).
kotlin {
    jvmToolchain(17)
}
