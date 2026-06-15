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
        // Instrumented (on-device) tests for the native whisper engine (#92, ADR-0018): the
        // androidDeviceTest source set runs the JNI native-correctness check on a real device/emulator
        // (the headless JVM gate can't load the .so). Validated locally now; CI runs it once a
        // real-hardware runner lands (ADR-0006/0018).
        @Suppress("UnstableApiUsage")
        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
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
            // The uniform logging facade (amzn/kmp-logger via core/common) for the whisper engine's
            // capture→transcribe trace logs — that native path is otherwise invisible on device.
            // Privacy (ADR-0009/0018): the traces log only structure (event types, sample/char counts),
            // never the audio or the transcript text.
            implementation(project(":core:common"))
            // The native whisper.cpp JNI library (#92, ADR-0018): supplies libwhisper_jni.so (the
            // symbols WhisperBridge binds to) and packages it into Android consumers. Android-only — the
            // KMP android-library target can't host externalNativeBuild, so the native build is a sibling
            // com.android.library module (deferno.android.nativelib).
            implementation(project(":speech-whisper-jni"))
            // Play Asset Delivery (#92, ADR-0019): resolves the install-time whisper-model pack's
            // on-device path (PlayAssetDeliveryModelLocator) so the engine reports Available once present.
            implementation(libs.play.asset.delivery)
        }

        jvmMain.dependencies {
            // The uniform logging facade (amzn/kmp-logger via core/common) for the whisper engine's
            // capture→transcribe trace logs (privacy: structure only, never audio/transcript — ADR-0009/0018).
            implementation(project(":core:common"))
            // The maintained desktop whisper engine (#94, ADR-0018): the `whisper-jni` Maven artifact the
            // jvmMain WhisperSpeechToText drives (TargetDataLine capture → EnergyVad → whisper-jni). Its jar
            // embeds the prebuilt native libs for the desktop OSes (loaded via WhisperJNI.loadLibrary()), so
            // — unlike Android's vendored NDK build — desktop needs no native toolchain. `implementation`:
            // the engine is internal to this module; the native jar still reaches the desktop app's runtime
            // classpath transitively, and the model ships in the installer (ADR-0019).
            implementation(libs.whisper.jni)
            // The Sidecar substrate (#119, ADR-0024/0025): SidecarSpeechToText consumes the typed
            // SidecarSpeechPort (the capability-port seam — wire mechanics stay in core/sidecar).
            // `api` because the JvmSpeechBindings @Provides signatures name SidecarClient and
            // DefaultSidecarSpeechPort, which the merged AppComponent (core/di) compiles against.
            // JVM-only by design — the Helpers are native processes (ADR-0025).
            api(project(":core:sidecar"))
        }

        commonTest.dependencies {
            // The selector + seam are measured on the headless JVM gate (ADR-0006): runTest drives the
            // suspend availability()/select(), Turbine asserts the listen() Flow<TranscriptEvent>.
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
        }

        // On-device native-correctness test (#92): runs the whisper JNI over a bundled jfk.wav and
        // asserts the transcript — proving the real .so transcribes on the device ABI (the JVM gate
        // can't load native code). Not part of `check`; run via :core:speech:connectedAndroidDeviceTest.
        getByName("androidDeviceTest").dependencies {
            implementation(libs.androidx.junit)
            implementation(libs.androidx.test.runner)
        }
    }
}

// The Linux stub Helper (#118/#119): the SidecarSpeechToText + selector tests drive the real client
// against it over a real socket — the engine's Linux fast-path proof (ADR-0024). Declared here, not in
// kotlin.sourceSets, because the `testFixtures(...)` capability notation is a DependencyHandler
// extension the KMP source-set DSL doesn't expose.
dependencies {
    "jvmTestImplementation"(testFixtures(project(":core:sidecar")))
}
