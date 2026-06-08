plugins {
    id("deferno.kmp.library")
    // This module contributes AppScope DI bindings (the per-platform SpeechToText engines, the
    // SpeechToTextSelector, and the device-local SpeechEnginePreference — ADR-0018) via distributed
    // @ContributesTo modules, so it hosts kotlin-inject + anvil. Mirrors core:secure.
    id("deferno.di")
}

kotlin {
    android {
        namespace = "com.circuitstitch.deferno.core.speech"
    }

    sourceSets {
        commonMain.dependencies {
            // The DI scope markers (AppScope) the @ContributesTo bindings reference (ADR-0014).
            // `api` so the contributions' scope key stays on the merged-component classpath.
            api(project(":core:scopes"))
            // The seam sits at the Transcript altitude (ADR-0018): listen() streams a
            // Flow<TranscriptEvent>. `api` because that Flow return type is part of the public surface.
            api(libs.kotlinx.coroutines.core)
            // Device-local speech-engine choice — an App setting, never synced (ADR-0018). The
            // SettingsSpeechEnginePreference wraps a multiplatform-settings `Settings`; each platform
            // binding supplies the platform store (SharedPreferences / java.util.prefs / NSUserDefaults).
            // This is the repo's first multiplatform-settings use; core:speech owns the dependency.
            implementation(libs.multiplatform.settings)
        }

        androidMain.dependencies {
            // The native whisper.cpp JNI library (#92, ADR-0018): supplies libwhisper_jni.so (the
            // symbols WhisperBridge binds to) and packages it into Android consumers. Android-only — the
            // KMP android-library target can't host externalNativeBuild, so the native build is a sibling
            // com.android.library module (deferno.android.nativelib).
            implementation(project(":speech-whisper-jni"))
            // Play Asset Delivery (#92, ADR-0019): resolves the install-time whisper-model pack's
            // on-device path (PlayAssetDeliveryModelLocator) so the engine reports Available once present.
            implementation(libs.play.asset.delivery)
        }

        commonTest.dependencies {
            // The selector + seam are measured on the headless JVM gate (ADR-0006): runTest drives the
            // suspend availability()/select(), Turbine asserts the listen() Flow<TranscriptEvent>.
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
        }
    }
}
