plugins {
    id("deferno.kmp.library")
    // kotlinx.serialization compiler plugin for the Envelope / ErrorEnvelope wire DTOs
    // (issue #17). Applied per-module via alias — same pattern as kotlin-compose in the
    // app modules — since only the modules with @Serializable types need it.
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    android {
        namespace = "com.circuitstitch.deferno.core.network"
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:model"))
            implementation(project(":core:common"))

            // Engine-agnostic Ktor client + JSON content negotiation, and the
            // kotlinx.serialization runtime the Envelope DTOs + tolerant reader use.
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.kotlinx.serialization.json)
        }

        // Per-target engines (ADR-0003 native-per-platform). OkHttp drives Android + the
        // desktop/JVM target; Darwin (NSURLSession) drives iOS. The commonMain config is
        // engine-agnostic, so these supply only the engine.
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
            // MockEngine: the in-process engine the success / error-path tests answer with
            // canned responses (no real network — ADR-0006 JVM-fast path). runTest drives
            // the suspend client calls.
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
