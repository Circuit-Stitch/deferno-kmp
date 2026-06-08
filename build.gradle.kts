// Top-level build file. Plugins are declared here (and applied per-module) so every
// module shares a single source of truth for versions via gradle/libs.versions.toml.
//
// Every plugin a build-logic convention plugin applies must be listed here `apply false`
// so it lands on each project's buildscript classpath at apply-time — see the INVARIANT
// note in build-logic/build.gradle.kts.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    // Classic Android library plugin — applied by the `deferno.android.nativelib` convention for the
    // bespoke whisper JNI module (#92), so `apply false` here lands it on that module's classpath.
    alias(libs.plugins.android.library) apply false
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
    // Startup Baseline Profile (cold-start AOT): `androidx.baselineprofile` is applied to app/androidApp
    // (the consumer that bundles the profile) and to the `:baselineprofile` Macrobenchmark generator,
    // whose module type is `com.android.test`. Both are applied per-module via alias, so `apply false`
    // here lands them on those module classpaths — same pattern as roborazzi/sqldelight above.
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.androidx.baselineprofile) apply false
    // Play Asset Delivery (ADR-0019, #92): `com.android.asset-pack` is applied directly by the
    // speech-model asset-pack module via alias, so `apply false` here lands it on that module's
    // classpath — same per-module pattern as roborazzi/sqldelight/baselineprofile above.
    alias(libs.plugins.android.asset.pack) apply false

    // The repo root is Kover's aggregation point: this convention applies Kover here and
    // enforces the merged shared-core coverage gate (ADR-0006, issue #11). Kover stays
    // `apply false` above so it also lands on every subproject's classpath (the INVARIANT).
    id("deferno.coverage.aggregation")
}
