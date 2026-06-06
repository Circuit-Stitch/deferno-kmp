package com.circuitstitch.deferno.gradle

/**
 * Single source of truth for the coverage gate (ADR-0006). It defines what counts as
 * "logic" (vs generated code / boilerplate) and the minimum line-coverage bound, shared
 * by the two coverage conventions so they always measure exactly the same thing:
 *
 * - `deferno.coverage` standardises these exclusions on every shared module's *own* Kover
 *   report (so a local `:core:di:koverHtmlReport` matches the gate).
 * - `deferno.coverage.aggregation` enforces the hard gate over the *merged* shared-core
 *   report. In Kover's `kover(project(...))` model the merged report uses the aggregating
 *   project's filters — not the per-module ones — so the gate needs its own copy of this
 *   list; sharing it here keeps the two from drifting.
 *
 * Dependency *versions* live in `gradle/libs.versions.toml`; SDK levels / the JVM toolchain
 * live in [ProjectConfig]. This is the coverage policy.
 */
object CoverageConfig {
    /**
     * The hard CI gate: minimum % of LINE coverage across the merged shared core. ADR-0006
     * puts it at ~85–90%; 85 is the floor of that band — raise it as the core matures.
     * Deliberately not 100%: we measure logic, not boilerplate, and avoid tests written
     * only for coverage.
     */
    const val GATE_MIN_LINE_PERCENT = 85

    /**
     * Class-name globs excluded from coverage — generated code + compiler boilerplate, so
     * the gate measures hand-written logic rather than plumbing (ADR-0006).
     */
    val EXCLUDED_CLASSES: List<String> = listOf(
        "*.BuildConfig",
        // kotlin-inject + kotlin-inject-anvil generated DI graph (issue #10):
        "*KotlinInject*",    // anvil merged components + kotlin-inject component impls
        "*CreateComponent*", // anvil @CreateComponent (KMP create) factories
        // Kotlin interface default-method JVM bridges — compiler artifacts, never invoked
        // (the real provider on the interface is what runs), so uncoverable boilerplate.
        "*DefaultImpls",
        // DI scope-key marker objects (ADR-0008): they exist only as annotation arguments
        // (@MergeComponent(AppScope::class), @SingleIn(...)) and are never instantiated, so
        // their synthetic <init> can't be covered without a hollow test. Not logic.
        "com.circuitstitch.deferno.core.di.*Scope",
    )

    /** Package globs excluded from coverage (generated DI contribution hints). */
    val EXCLUDED_PACKAGES: List<String> = listOf(
        "amazon.lastmile.inject", // anvil contribution-hint classes
    )
}
