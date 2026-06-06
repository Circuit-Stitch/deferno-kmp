# Testing strategy & coverage policy

**Context.** "Full test coverage for everything" is a headline goal. The KMP shared-presentation core
(ADR-0003) means the bulk of logic — domain, data, sync, **and presentation** — is testable once, on
the fast JVM path.

**Decision.**
- **`commonTest`-centric:** kotlin.test · kotlinx-coroutines-test (`runTest`) · **Turbine** (Flow) ·
  **SQLDelight in-memory JDBC driver** (real DB tests on JVM) · **Ktor `MockEngine`** (canned
  envelopes) · fakes for capability ports. ViewModels + Decompose components are covered here.
- **Versioning shim** guarded by **golden-envelope contract fixtures** (captured from the live API /
  OpenAPI examples) parsed through the tolerant reader + adapters and asserted against the canonical
  domain model — catches backend drift.
- **Android Views:** Compose UI tests (`createComposeRule`) + **Roborazzi** screenshot tests.
- **iOS Views:** XCTest (+ optional snapshot testing).
- **Coverage:** **Kover** with a **hard CI gate at ~85–90% of the shared core**, **excluding**
  generated code (SQLDelight, kotlin-inject, kotlinx.serialization) and thin UI glue. Explicitly
  **not 100%** — we measure logic, not boilerplate, and avoid tests-written-for-coverage.
- **Workflow:** **TDD** (red-green-refactor) for the shared core; pragmatic test-after for Views.
- **CI:** GitHub Actions — JVM-fast path every PR (commonTest + Android unit + Roborazzi + Kover);
  macOS runner for iOS.

**Considered & rejected.** A 100% gate (diminishing returns, incentivizes hollow tests); **Paparazzi**
for screenshots (weaker Compose-Multiplatform / KMP support than Roborazzi).

**Consequences.** Views are kept deliberately thin so the dominant share of coverage is
platform-agnostic and runs without a device or simulator.
