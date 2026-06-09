import com.circuitstitch.deferno.gradle.ProjectConfig
import org.gradle.api.tasks.testing.Test

// KMP foundation convention: the cross-platform target set, the JVM toolchain, and
// the commonTest framework — everything that is *not* Android-specific (ADR-0004).
// Composable: `deferno.android` layers the Android library target on top, and
// `deferno.kmp.library` bundles kmp + android + coverage for core/* and feature/*.
// Usable on its own for a future pure-Kotlin (no-Android) shared module.

plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

kotlin {
    jvmToolchain(ProjectConfig.JVM_TOOLCHAIN)

    // expect/actual *classes* (e.g. core/scopes' PlatformContext) are still compiler-flagged as Beta
    // (KT-61573). The feature is load-bearing for this KMP project, so opt in across every target —
    // the JetBrains-recommended way to silence the per-`actual` Beta warning, rather than annotating
    // each declaration. Applies to all compilations configured below (commonMain + jvm + iOS, and the
    // Android target that `deferno.android` layers on top).
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    // Desktop / JVM-fast test path (ADR-0006: the bulk of logic is tested here).
    jvm()

    // Apple targets. Klibs cross-compile on any host; running iOS tests / linking
    // device frameworks happens on a macOS runner (ADR-0006).
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// Surface the staging API base URL + personal access token (PAT) from the gitignored
// root `local.properties` to unit tests as system properties, so a live integration
// test (e.g. the /auth/me tracer, #20) can hit a real backend without committing the
// token. Absent-safe: blank when the keys are missing, so CI and other devs are never
// blocked — such tests skip themselves (e.g. `assumeTrue(token.isNotBlank())`).
// Read via `providers.fileContents` to stay configuration-cache compatible and to
// re-run when the token changes. See ADR-0012 + `contracts/CONTRACT-NOTES.md`.
val localProperties = providers.fileContents(
    rootProject.layout.projectDirectory.file("local.properties"),
).asText.orElse("")

tasks.withType<Test>().configureEach {
    fun localProperty(key: String): String =
        localProperties.get().lineSequence()
            .firstOrNull { it.startsWith("$key=") }
            ?.substringAfter('=')?.trim().orEmpty()

    systemProperty("deferno.staging.baseUrl", localProperty("deferno.staging.baseUrl"))
    systemProperty("deferno.staging.apiToken", localProperty("deferno.staging.apiToken"))
}
