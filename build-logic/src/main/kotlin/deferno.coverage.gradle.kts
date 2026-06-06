// Coverage convention: Kover on every shared module (ADR-0006). This plugin only
// standardises the tool + the "measure logic, not boilerplate" exclusions so coverage
// is consistent and generated/glue code never counts against the gate.
//
// The hard gate itself (~85–90% of the shared core) is NOT wired here and does not
// exist yet: it will be enforced from the CI workflow when CI lands, not from `check`
// — a failing `koverVerify` in `check` would block the currently-empty foundation
// modules. Until then, `koverVerify` has no bound rules and is a no-op.

plugins {
    id("org.jetbrains.kotlinx.kover")
}

kover {
    reports {
        filters {
            excludes {
                // Exclude generated + boilerplate so the gate measures real logic
                // (ADR-0006). Add SQLDelight / kotlin-inject / serialization generated
                // packages here as those modules gain code; thin UI glue lives in the
                // Views and is screenshot-tested instead.
                classes("*.BuildConfig")
            }
        }
    }
}
