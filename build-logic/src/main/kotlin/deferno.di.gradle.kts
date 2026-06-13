import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

// Compile-time DI convention (ADR-0003 / ADR-0008): wires kotlin-inject +
// kotlin-inject-anvil into a shared KMP module. Compose it ON TOP of
// `deferno.kmp.library` (which supplies the KMP targets this reads) — apply both:
//
//   plugins { id("deferno.kmp.library"); id("deferno.di") }
//
// It adds the DI runtimes to commonMain and BOTH KSP processors to every per-target
// KSP configuration. Per-target (NOT kspCommonMainMetadata) is required: anvil emits
// each merged component's create() per platform via @MergeComponent.CreateComponent
// (expect/actual), so the processors must run on each target. kspCommonMainMetadata
// cannot emit the per-target actuals and is unsupported under AGP 9's KMP library
// plugin (google/ksp#2476) — which is why this stays per-target.
//
// The KSP plugin is applied here from build-logic, so per this project's INVARIANT it
// is also declared `apply false` in the root build.gradle.kts and `compileOnly` in
// build-logic/build.gradle.kts.

plugins {
    id("com.google.devtools.ksp")
}

private val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
private fun lib(alias: String) = catalog.findLibrary(alias).get()

dependencies {
    // DI runtimes — shared, cross-platform (commonMain).
    add("commonMainImplementation", lib("kotlin-inject-runtime"))
    add("commonMainImplementation", lib("kotlin-inject-anvil-runtime"))
    add("commonMainImplementation", lib("kotlin-inject-anvil-runtimeOptional"))

    // Both processors on every target. `kspJvm` drives the commonTest JVM-fast path;
    // `kspAndroid` covers `check`'s Android host tests; the iOS + macOS (ADR-0029) configs cover the
    // native klibs. anvil emits each merged component's create() per platform via expect/actual, so a
    // missing per-target KSP config means a missing `actual fun create…Component` on that target.
    listOf("kspJvm", "kspAndroid", "kspIosArm64", "kspIosSimulatorArm64", "kspMacosArm64")
        .forEach { config ->
            add(config, lib("kotlin-inject-compiler"))
            add(config, lib("kotlin-inject-anvil-compiler"))
        }
}
