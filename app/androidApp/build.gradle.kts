plugins {
    // SDK levels + JVM toolchain come from the deferno.android.application convention
    // (shared with the library modules via ProjectConfig). The Compose compiler plugin
    // is applied explicitly; AGP 9 compiles Kotlin itself, so no kotlin-android plugin.
    id("deferno.android.application")
    alias(libs.plugins.kotlin.compose)
    // Screenshot tests (#27): Roborazzi renders the feature Compose Views through Robolectric on the
    // JVM and adds the record/verify tasks. This is the well-trodden Roborazzi home (a
    // com.android.application unit-test source set), chosen over the feature modules' KMP-android host
    // test to reuse the Compose-BOM test infra and avoid the KMP/androidx test-artifact friction.
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "com.circuitstitch.deferno"

    defaultConfig {
        applicationId = "com.circuitstitch.deferno"
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

    testOptions {
        // Robolectric/Roborazzi render against the merged Android resources (incl. the design
        // system's Compose resources/fonts) on the JVM-fast unit-test path (#27, ADR-0006).
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // The demo host (#27, TEMPORARY): the Tasks + Plan Android Compose Views over in-memory sample
    // data, wired in src/main/.../demo until auth + DI provide the real scene graph. Android-only —
    // a native desktop/iOS shell is its own follow-up, not this phone layout stretched (ADR-0007).
    // feature:auth is still a shell; core:designsystem provides DefernoTheme for the Compose host.
    implementation(project(":feature:tasks"))
    implementation(project(":feature:tasks:ui"))
    implementation(project(":feature:plan"))
    implementation(project(":feature:plan:ui"))
    implementation(project(":feature:auth"))
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))
    // Decompose: `retainedComponent { }` builds the demo root so it survives configuration changes
    // (rotation) + back-press routing; `subscribeAsState()` observes the tab/slots from Compose.
    implementation(libs.decompose)
    implementation(libs.decompose.extensions.compose)
    // The demo repositories expose Kotlin Flows; the Plan root computes today's date.
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.datetime)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    // JVM-fast unit + screenshot tests for the Views (#27): Robolectric runs the Compose UI tests +
    // Roborazzi screenshots without a device, rendering the Views through their public
    // `*Screen(component)` entry points driven by tiny fakes (see src/test). The feature/UI + data
    // modules they touch are already on the main classpath above (testImplementation extends it).
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.ui.test.junit4)
    testImplementation(libs.androidx.ui.test.manifest)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
