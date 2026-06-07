plugins {
    id("deferno.kmp.library")
    // This module contributes AppScope DI bindings (the SecretVault per-platform actuals,
    // ADR-0014) via distributed @ContributesTo modules, so it hosts kotlin-inject + anvil.
    id("deferno.di")
}

kotlin {
    android {
        namespace = "com.circuitstitch.deferno.core.secure"
    }

    sourceSets {
        commonMain.dependencies {
            // AccountId — the identity the vault keys on (relocated to core:model, issue #14).
            implementation(project(":core:model"))
            // The DI scope markers (AppScope) the @ContributesTo bindings reference (ADR-0014).
            // `api` so the contributions' scope key stays on the merged-component classpath.
            api(project(":core:scopes"))
        }

        jvmMain.dependencies {
            // Desktop OS keychain (ADR-0009). JNA-backed; the Android Keystore and iOS
            // Keychain actuals use in-platform APIs and need no dependency.
            implementation(libs.java.keyring)
        }
    }
}
