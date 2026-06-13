plugins {
    id("deferno.kmp.library")
}

kotlin {
    android {
        namespace = "com.circuitstitch.deferno.core.common"
    }

    sourceSets {
        // The public `com.circuitstitch.deferno.core.common.log.Logger` facade is the one logging
        // API every module uses (issue: native macOS app). Its kmp-logger-backed actual is the same
        // ~25 lines on the three targets amzn/kmp-logger publishes a klib for, so it's duplicated in
        // each rather than threaded through a custom intermediate source set (that fights KMP's
        // default hierarchy template). macOS carries its own os_log actual in `macosMain` — kmp-logger
        // 0.0.1 ships no macosArm64 klib. `implementation` (internal to this module), never `api`.
        // ponytail: 3 tiny copies of trivial glue, not a hand-wired source-set graph.
        androidMain.dependencies { implementation(libs.kmp.logger.log) }
        jvmMain.dependencies { implementation(libs.kmp.logger.log) }
        iosMain.dependencies { implementation(libs.kmp.logger.log) }

        commonMain.dependencies {
            // For componentScope() (#174). `implementation`, not `api`: its callers (the feature
            // slices + app/shell) already api-expose Decompose/coroutines themselves, and the data
            // modules that depend on core/common must not inherit a presentation library.
            implementation(libs.decompose)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
