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
            // The engine's credential-cache mutex + rethrown CancellationException are named directly.
            implementation(libs.kotlinx.coroutines.core)
            // Koog 1.0, stable modules only (ADR-0027): the stock Anthropic client, the executor +
            // structured-output layer, and the Ktor-backed KoogHttpClient factory. Internal to the
            // KoogInferenceEngine — the seam exposes no Koog type, so the boundary stays fake-able.
            implementation(libs.koog.prompt.executor.anthropic.client)
            implementation(libs.koog.prompt.executor.model)
            implementation(libs.koog.http.client.ktor)
            // `api`: the engine's injectable base HttpClient (the MockEngine test seam) is a public
            // constructor parameter, same rationale as core/network's client surface.
            api(libs.ktor.client.core)
        }

        // Per-target Ktor engines (ADR-0003 native-per-platform), as in core/network: Koog's
        // http-client-ktor ships no engine, and the default `HttpClient()` discovers one at runtime.
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }

        commonTest.dependencies {
            // InferenceEngine.infer is suspend; the seam tests drive it via runTest
            // (ADR-0006 JVM-fast path).
            implementation(libs.kotlinx.coroutines.test)
            // MockEngine: the in-process engine the KoogInferenceEngine tests answer with canned
            // Anthropic-format responses (no real network — ADR-0006), injected as the engine's
            // base HttpClient.
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
