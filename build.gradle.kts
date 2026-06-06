// Top-level build file. Plugins are declared here (and applied per-module) so every
// module shares a single source of truth for versions via gradle/libs.versions.toml.
//
// Every plugin a build-logic convention plugin applies must be listed here `apply false`
// so it lands on each project's buildscript classpath at apply-time — see the INVARIANT
// note in build-logic/build.gradle.kts.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kover) apply false
}
