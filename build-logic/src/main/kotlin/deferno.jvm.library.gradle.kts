import com.circuitstitch.deferno.gradle.ProjectConfig

// Convention plugin for a pure-Kotlin/JVM library module — the no-Android, no-multiplatform
// sibling of `deferno.kmp.library`, anticipated by the note in `deferno.kmp` ("usable on its own
// for a future pure-Kotlin (no-Android) shared module"). Applied by modules that are deliberately
// JVM-only *by design*, not just for now — the first is `core/sidecar`, the OS-agnostic JVM half of
// the native-sidecar substrate (ADR-0024/0025): its peers are native Helper processes (Swift on
// macOS now; Windows/Linux later), not Kotlin, so KMP targets would be permanent dead weight.
//
// It composes the Kotlin/JVM plugin + the shared JVM toolchain (routed through ProjectConfig — the
// same source the KMP + Android conventions use, so a bump lands in one place) + `deferno.coverage`
// (Kover with the shared-core exclusions, ADR-0006), and wires the kotlin.test framework into the
// `test` source set, mirroring what `deferno.kmp` does for `commonTest`. The merged coverage gate
// (`deferno.coverage.aggregation`, at the root) picks up any `:core:*` / `:feature:*` module
// automatically, so a JVM-only core module is gated exactly like its KMP siblings.

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("deferno.coverage")
}

kotlin {
    jvmToolchain(ProjectConfig.JVM_TOOLCHAIN)
}

dependencies {
    "testImplementation"(kotlin("test"))
}
