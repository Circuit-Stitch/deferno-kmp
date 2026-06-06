// Convention plugin for a shared Kotlin Multiplatform library module (every core/*
// and feature/* module). It is a thin *composition* of three single-responsibility
// conventions (ADR-0004: "convention plugins from day one"):
//
//   deferno.kmp       — cross-platform targets (jvm + ios) + JVM toolchain + commonTest
//   deferno.android   — the Android library target + SDK levels  (pulls in deferno.kmp)
//   deferno.coverage  — Kover, with the shared-core exclusions    (ADR-0006)
//
// Each module supplies only its android `namespace` and its module-to-module
// dependencies — no per-module build config drift.

plugins {
    id("deferno.android")
    id("deferno.coverage")
}
