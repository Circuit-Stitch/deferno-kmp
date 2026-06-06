import com.circuitstitch.deferno.gradle.CoverageConfig
import kotlinx.kover.gradle.plugin.dsl.AggregationType

// Coverage gate (ADR-0006): the hard ~85–90% line-coverage bound over the MERGED
// shared-core report. Applied once, at the repo root, which becomes Kover's aggregation
// point — it pulls each shared module's coverage in via `kover(project(...))` and the
// `total` report set merges them (commonMain measured through both the JVM and Android
// unit-test variants). App entry points (app/*) are platform glue and stay out of the
// logic gate. CI runs `:koverVerify`; the gate is deliberately NOT wired into `check`
// (see `deferno.coverage`).

plugins {
    id("org.jetbrains.kotlinx.kover")
}

// The shared core (core/*) + feature slices (feature/*) feed the merged report; app/*
// entry points are platform glue and stay out of the logic gate. The list is DERIVED from
// the project tree (settings.gradle.kts stays the single source of truth), so a new
// core/* or feature/* module is gated automatically — there is no second list to drift
// out of sync. This reads subproject *paths* at configuration time (config-cache-safe)
// and adds explicit `kover(project(...))` dependencies; it is NOT Kover's
// `merge { subprojects() }`, which applies the plugin across the tree and is config-cache-hostile.
subprojects
    .map { it.path }
    .filter { it.startsWith(":core:") || it.startsWith(":feature:") }
    .sorted()
    .forEach { path -> dependencies.add("kover", project(path)) }

kover {
    reports {
        // Same exclusions as the per-module convention; the merged report uses the
        // aggregating project's filters, so the gate needs its own copy (CoverageConfig).
        filters {
            excludes {
                classes(*CoverageConfig.EXCLUDED_CLASSES.toTypedArray())
                packages(*CoverageConfig.EXCLUDED_PACKAGES.toTypedArray())
            }
        }
        total {
            verify {
                rule("shared-core line coverage (ADR-0006)") {
                    minBound(CoverageConfig.GATE_MIN_LINE_PERCENT)
                }
                rule("shared core is actually measured") {
                    // minBound passes vacuously when nothing is measured (covered=0,
                    // total=0 → no violation; Kover #675). Require at least one covered
                    // line so a hollowed-out gate — broadened excludes, a module dropped
                    // from the list above, or a test suite that never ran — fails loudly
                    // instead of going green on an empty measurement.
                    bound {
                        minValue = 1
                        aggregationForGroup = AggregationType.COVERED_COUNT
                    }
                }
            }
        }
    }
}
