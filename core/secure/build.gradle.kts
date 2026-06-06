plugins {
    id("deferno.kmp.library")
}

kotlin {
    android {
        namespace = "com.circuitstitch.deferno.core.secure"
    }

    sourceSets {
        jvmMain.dependencies {
            // Desktop OS keychain (ADR-0009). JNA-backed; the Android Keystore and iOS
            // Keychain actuals use in-platform APIs and need no dependency.
            implementation(libs.java.keyring)
        }
    }
}
