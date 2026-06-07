plugins {
    id("deferno.kmp.library")
    id("deferno.di")
}

kotlin {
    android {
        namespace = "com.circuitstitch.deferno.core.di"
    }

    sourceSets {
        commonMain.dependencies {
            // Scope markers + PlatformContext (ADR-0008 / ADR-0014) — the low-level DI vocabulary
            // the merged components and every contributor share. `api` because the merged
            // components' public surface (e.g. createAppComponent's PlatformContext) leaks it.
            api(project(":core:scopes"))
            // Account — the real AccountScope binding the graph resolves per scene (issue #14).
            implementation(project(":core:model"))
        }
    }
}
