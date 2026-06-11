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
        }
    }
}
