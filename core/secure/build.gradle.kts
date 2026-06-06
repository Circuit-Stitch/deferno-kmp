plugins {
    id("deferno.kmp.library")
}

kotlin {
    android {
        namespace = "com.circuitstitch.deferno.core.secure"
    }

    sourceSets {
        commonMain.dependencies {
            // AccountId — the identity the vault keys on (relocated to core:model, issue #14).
            implementation(project(":core:model"))
        }

        jvmMain.dependencies {
            // Desktop OS keychain (ADR-0009). JNA-backed; the Android Keystore and iOS
            // Keychain actuals use in-platform APIs and need no dependency.
            implementation(libs.java.keyring)
        }
    }
}
