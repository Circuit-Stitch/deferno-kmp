plugins {
    id("deferno.kmp.library")
}

kotlin {
    android {
        namespace = "com.circuitstitch.deferno.core.common"
    }

    sourceSets {
        commonMain.dependencies {
            // Amazon's KMP logging facade (amzn/kmp-logger). `api` (not `implementation`) so every
            // downstream core/* + feature/* module can `logger { }` / `Logger("Tag")` without
            // re-declaring it — the default strategy logs to each platform's native console
            // (Logcat / os_log / println). Configure once at app startup via `Logger.configure(…)`.
            api(libs.kmp.logger.log)
            // For componentScope() (#174). `implementation`, not `api`: its callers (the feature
            // slices + app/shell) already api-expose Decompose/coroutines themselves, and the data
            // modules that depend on core/common must not inherit a presentation library.
            implementation(libs.decompose)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
