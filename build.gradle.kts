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
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.kover) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.sqldelight) apply false
    // Applied directly (per-module alias) by app/androidApp, which hosts the screenshot tests (#27),
    // so it lands here `apply false` to reach that module's classpath — same as sqldelight above.
    alias(libs.plugins.roborazzi) apply false

    // The repo root is Kover's aggregation point: this convention applies Kover here and
    // enforces the merged shared-core coverage gate (ADR-0006, issue #11). Kover stays
    // `apply false` above so it also lands on every subproject's classpath (the INVARIANT).
    id("deferno.coverage.aggregation")
}
