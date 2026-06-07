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

// Surface dev-account PATs from the gitignored root local.properties into the *debug* BuildConfig
// (#68, ADR-0012): a developer pastes one or more PATs and the app seeds those Accounts on first run,
// so it shows real staging data without committing a token. Absent-safe (blank when unset) and
// debug-only — a release build's fields are empty, so no PAT can ship. Read via providers.fileContents
// to stay configuration-cache compatible (same pattern as the deferno.kmp Test wiring).
//
// Format (local.properties):
//   deferno.dev.accounts=work:Work:<PAT1>;personal:Personal:<PAT2>   (id:label:token, ';'-separated)
// Back-compat: a lone deferno.staging.apiToken (already used by the #20 integration test) seeds one
// "Dev (staging)" account, so an existing setup needs no change. See DevAccounts for the parser.
val localProperties = providers.fileContents(
    rootProject.layout.projectDirectory.file("local.properties"),
).asText.orElse("")

fun localProperty(key: String): String =
    localProperties.get().lineSequence()
        .firstOrNull { it.startsWith("$key=") }
        ?.substringAfter('=')?.trim().orEmpty()

// Render a Java string literal for buildConfigField (escape backslash + quote; PATs are single-line
// base64url/JWT, so no newline handling is needed).
fun stringLiteral(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.circuitstitch.deferno"

    defaultConfig {
        applicationId = "com.circuitstitch.deferno"
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // Dev PAT login placeholder (#68): seed Accounts from local.properties on debug builds.
            buildConfigField("String", "DEV_ACCOUNTS", stringLiteral(localProperty("deferno.dev.accounts")))
            buildConfigField("String", "DEV_STAGING_TOKEN", stringLiteral(localProperty("deferno.staging.apiToken")))
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // No PAT ever ships in a release build (#68 phase 5 verifies this).
            buildConfigField("String", "DEV_ACCOUNTS", "\"\"")
            buildConfigField("String", "DEV_STAGING_TOKEN", "\"\"")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
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
    // The navigation shell (#55, ADR-0013): the RootComponent (Auth ↔ Main shells) + Main shell's
    // Destination graph (NavigationSuiteScaffold) live in src/main/.../shell over the shared Decompose
    // components + Android Views. Android-only — a native desktop/iOS shell is its own follow-up, not
    // this phone layout stretched (ADR-0007). It is backed by the in-memory stub repositories +
    // SampleData under src/main/.../demo (TEMPORARY until DI lands, ADR-0008). core:designsystem
    // provides DefernoTheme for the Compose host.
    implementation(project(":feature:tasks"))
    implementation(project(":feature:tasks:ui"))
    implementation(project(":feature:plan"))
    implementation(project(":feature:plan:ui"))
    implementation(project(":feature:auth"))
    implementation(project(":feature:auth:ui"))
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    // The compile-time DI graph (#68, ADR-0014): the app builds AppComponent at startup and an
    // AccountComponent per Active Account; core:domain supplies the CommandExecutor + AddToPlan command
    // the shell drives for the offline write path.
    implementation(project(":core:di"))
    implementation(project(":core:domain"))
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
    // The navigation shell (#55): NavigationSuiteScaffold + the window-size-class metric it adapts to.
    implementation(libs.androidx.material3.adaptive.navigation.suite)
    implementation(libs.androidx.material3.adaptive)
    implementation(libs.androidx.material.icons.core)

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
