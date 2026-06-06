import com.circuitstitch.deferno.gradle.CoverageConfig

// Coverage convention: Kover on every shared module (ADR-0006). This plugin standardises
// the tool + the "measure logic, not boilerplate" exclusions ([CoverageConfig]) on each
// module's own report, so generated/glue code never counts and a local module report
// matches the CI gate.
//
// The hard gate itself (~85–90% of the shared core) is NOT enforced here: a per-module
// `koverVerify` would be brittle (a single stand-in data class can sink one module) and
// would block the still-scaffold foundation modules. The gate is enforced over the MERGED
// shared-core report by the `deferno.coverage.aggregation` convention (applied at the
// repo root), which CI runs — so per-module `koverVerify` stays rule-free (a no-op) here.

plugins {
    id("org.jetbrains.kotlinx.kover")
}

kover {
    reports {
        filters {
            excludes {
                // Shared with the aggregation gate so per-module and merged reports agree.
                classes(*CoverageConfig.EXCLUDED_CLASSES.toTypedArray())
                packages(*CoverageConfig.EXCLUDED_PACKAGES.toTypedArray())
            }
        }
    }
}
