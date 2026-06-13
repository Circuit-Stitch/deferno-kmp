import org.gradle.api.tasks.testing.Test

plugins {
    id("deferno.kmp.library")
    // kotlinx.serialization compiler plugin for the typed structured-output schemas (ADR-0027):
    // the seam's result contracts are @Serializable types the engines round-trip. Applied
    // per-module via alias — same pattern as core/network's wire DTOs.
    alias(libs.plugins.kotlin.serialization)
    // This module contributes the AppScope InferenceEngine binding via a @ContributesTo
    // module, so it hosts kotlin-inject + anvil.
    id("deferno.di")
}

kotlin {
    android {
        namespace = "com.circuitstitch.deferno.core.agent"
    }

    sourceSets {
        commonMain.dependencies {
            // The DI scope markers (AppScope) the @ContributesTo InferenceEngine binding references.
            api(project(":core:scopes"))
            // Extractor proposals reuse the shared Item vocabulary and date/time domain types.
            api(project(":core:model"))
            // `api`: KSerializer appears on the seam's public surface (InferenceSchema), so the
            // serialization runtime must reach every consumer (the json artifact api-exposes core).
            api(libs.kotlinx.serialization.json)
            // InferenceEngine.infer is suspend; the seam + NotConfiguredInferenceEngine name it.
            implementation(libs.kotlinx.coroutines.core)
            // DefernoEnvironment: the engine catalog reads the relay base URL from it (#150).
            implementation(project(":core:network"))
            // The device-local Agent opt-in [[App setting]] (#150) over multiplatform-settings, the
            // same commonMain `Settings` API core/speech introduced — each platform supplies the store.
            implementation(libs.multiplatform.settings)
        }

        // The Koog-backed engine (KoogInferenceEngine + the AppScope AgentBindings) lives in the
        // shared `src/hosted` directory, compiled into Android/JVM/iOS only — Koog publishes no
        // macosArm64 klib (ADR-0029), so macOS gets the NotConfiguredInferenceEngine floor + its own
        // MacosAgentBindings (src/macosMain) until the Swift FoundationModels engine is injected at
        // the seam (Phase 3). Sharing one srcDir across the three targets keeps a single copy without
        // a custom intermediate source set (which fights KMP's default hierarchy template).
        // ponytail: one shared dir, not three copies and not a hand-wired hierarchy.
        androidMain {
            kotlin.srcDir("src/hosted/kotlin")
            // Per-target Ktor engine (ADR-0003) + the Koog 1.0 stable set + the injectable base
            // HttpClient the engine + its MockEngine test seam name.
            dependencies {
                implementation(libs.ktor.client.okhttp)
                implementation(libs.ktor.client.core)
                implementation(libs.koog.prompt.executor.anthropic.client)
                implementation(libs.koog.prompt.executor.model)
                implementation(libs.koog.http.client.ktor)
            }
        }
        jvmMain {
            kotlin.srcDir("src/hosted/kotlin")
            dependencies {
                implementation(libs.ktor.client.okhttp)
                implementation(libs.ktor.client.core)
                implementation(libs.koog.prompt.executor.anthropic.client)
                implementation(libs.koog.prompt.executor.model)
                implementation(libs.koog.http.client.ktor)
            }
        }
        iosMain {
            kotlin.srcDir("src/hosted/kotlin")
            dependencies {
                implementation(libs.ktor.client.darwin)
                implementation(libs.ktor.client.core)
                implementation(libs.koog.prompt.executor.anthropic.client)
                implementation(libs.koog.prompt.executor.model)
                implementation(libs.koog.http.client.ktor)
            }
        }

        commonTest.dependencies {
            // The seam tests (FakeInferenceEngine, NotConfiguredInferenceEngine) drive the suspend
            // infer() via runTest (ADR-0006 JVM-fast path); they name no Koog type, so they stay
            // multiplatform (and compile on macOS).
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            // The KoogInferenceEngine tests (the hosted engine) run on the JVM-fast path: MockEngine
            // answers canned Anthropic-format responses (no real network — ADR-0006).
            implementation(libs.ktor.client.mock)
        }
    }
}

// Surface a developer's own Anthropic-format endpoint + key from the gitignored root
// `local.properties` to unit tests as system properties — the ADR-0012 staging-token pattern
// (`deferno.kmp` wires `deferno.staging.*` the same way) — so the live structured-output tracer
// (#147) can hit a real endpoint without committing a credential. Absent-safe: blank when the keys
// are missing (CI, devs without a key), and the tracer skips itself via assumeTrue.
val localProperties = providers.fileContents(
    rootProject.layout.projectDirectory.file("local.properties"),
).asText.orElse("")

tasks.withType<Test>().configureEach {
    fun localProperty(key: String): String =
        localProperties.get().lineSequence()
            .firstOrNull { it.startsWith("$key=") }
            ?.substringAfter('=')?.trim().orEmpty()

    systemProperty("deferno.anthropic.apiKey", localProperty("deferno.anthropic.apiKey"))
    systemProperty("deferno.anthropic.baseUrl", localProperty("deferno.anthropic.baseUrl"))
}
