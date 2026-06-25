plugins {
    id("deferno.kmp.library")
}

kotlin {
    android {
        namespace = "com.circuitstitch.deferno.core.common"
    }

    sourceSets {
        // The public `com.circuitstitch.deferno.core.common.log.Logger` facade is the one logging
        // API every module uses. Backed by amzn/kmp-logger, which now ships a klib for every target
        // this module builds — including macosArm64 — so a single delegating actual in `appleMain`
        // covers iOS + macOS (the old bespoke macOS os_log copy is gone). Android/JVM keep their own
        // ~25-line delegating actuals: there's no shared JVM source set, and a top-level native facade
        // would clash with commonMain's `LoggerKt`. `implementation` (internal to this module), never `api`.
        androidMain.dependencies { implementation(libs.kmp.logger.log) }
        jvmMain.dependencies { implementation(libs.kmp.logger.log) }
        appleMain.dependencies { implementation(libs.kmp.logger.log) }

        commonMain.dependencies {
            // For componentScope() (#174). `implementation`, not `api`: its callers (the feature
            // slices + app/shell) already api-expose Decompose/coroutines themselves, and the data
            // modules that depend on core/common must not inherit a presentation library.
            implementation(libs.decompose)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
