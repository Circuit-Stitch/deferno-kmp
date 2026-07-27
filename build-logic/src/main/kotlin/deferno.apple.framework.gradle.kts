import co.touchlab.skie.configuration.SuppressSkieWarning
import co.touchlab.skie.plugin.configuration.SkieExtension
import com.circuitstitch.deferno.gradle.AppleFrameworkConfig
import com.circuitstitch.deferno.gradle.ProjectConfig
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.Framework
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

// Convention for the two bespoke Apple umbrella-framework modules — app/iosApp (iosArm64 +
// iosSimulatorArm64) and app/macosApp (macosArm64, ADR-0029). Both link the SAME `Deferno.framework`,
// so what must not drift between them lives here: the JVM toolchain, the framework's
// CFBundleIdentifier, and the SKIE name-collision suppression policy (all in AppleFrameworkConfig).
//
// What deliberately stays in each module's own build file: its target set, its `export(...)` list and
// its source set's dependencies. Those are genuinely per-OS (iOS ships :feature:braindumps, macOS
// ships :core:agent) — an abstraction over them would hide the divergence rather than remove it.
//
// These modules can't apply `deferno.kmp.library` (different target set, no jvm()/android), which is
// why they were hand-written and why their shared settings drifted into two copies. The second Apple
// framework is what earns this convention (ADR-0004: abstractions are earned at the second consumer).
//
// It applies NO external Gradle plugin — the `deferno.contract-fixtures` pattern. Each module keeps
// `alias(libs.plugins.kotlin.multiplatform)` + `alias(libs.plugins.skie)` in its own plugins block and
// this convention only REACTS, so apply order is irrelevant (SKIE registers its extension eagerly and
// defers its own work to afterEvaluate). It does REFERENCE KGP + SKIE types though, so both must sit
// in the ROOT project's plugin ClassLoaderScope — see the `apply false` note in the root build.

pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
    extensions.configure<KotlinMultiplatformExtension> {
        // Keep in lockstep with every other module. Previously hardcoded `jvmToolchain(21)` in both
        // Apple build files, so a ProjectConfig bump silently missed exactly the two modules no
        // JVM/Android CI job builds.
        jvmToolchain(ProjectConfig.JVM_TOOLCHAIN)

        // Every Apple framework binary, however its target is declared (iosApp loops over two targets,
        // macosApp declares one). `configureEach` so the module's own `binaries.framework { … }` —
        // declared later, in its build file — is covered.
        targets.withType<KotlinNativeTarget>().configureEach {
            binaries.withType<Framework>().configureEach {
                binaryOption("bundleId", AppleFrameworkConfig.BUNDLE_ID)
            }
        }
    }
}

pluginManager.withPlugin("co.touchlab.skie") {
    extensions.configure<SkieExtension> {
        features {
            AppleFrameworkConfig.SKIE_SUPPRESSED_NAME_COLLISION_PREFIXES.forEach { fqNamePrefix ->
                group(fqNamePrefix) {
                    SuppressSkieWarning.NameCollision(true)
                }
            }
        }
    }
}
