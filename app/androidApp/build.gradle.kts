plugins {
    // AGP 9 compiles Kotlin itself (built-in Kotlin support), so the standalone
    // org.jetbrains.kotlin.android plugin is no longer applied. The Compose
    // compiler plugin is still applied explicitly.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.circuitstitch.deferno"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.circuitstitch.deferno"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
    }
}

// Single source of truth for the JVM target. The toolchain pins JDK 17 for both
// Java and Kotlin compilation and lets Gradle auto-provision it (via the Foojay
// resolver in settings.gradle.kts) when it isn't installed locally.
kotlin {
    jvmToolchain(17)
}

dependencies {
    // Shared KMP feature slices (resolve to their Android variants). Empty shells
    // for now — their Android Compose Views land alongside the shared presentation
    // in each feature's androidMain (ADR-0004).
    implementation(project(":feature:auth"))
    implementation(project(":feature:tasks"))
    implementation(project(":feature:plan"))
    implementation(project(":core:designsystem"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
