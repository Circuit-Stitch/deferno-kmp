import com.circuitstitch.deferno.gradle.ProjectConfig

// The shacl-aio deterministic-floor native library (the repo's first Rust native code). It
// cross-compiles the vendored, pinned shacl-aio crate (third_party/shacl-aio) into
// libshacl_aio.so via cargo-ndk and exposes a thin JNA-backed Kotlin API (ShaclFloor) that
// turns a transcript into draft Tasks. Android-only for now (the same C ABI is reusable on
// jvm/Apple later — JNA on jvmMain, cinterop on appleMain).
//
// Uses deferno.android.nativelib (the classic com.android.library + pinned SDK/NDK) like
// :speech-whisper-jni — but there is NO CMake/externalNativeBuild: cargo-ndk does the
// cross-compile and drops the .so into a generated build/ dir registered as a jniLibs srcDir,
// which AGP packages automatically (and `clean` wipes — nothing to .gitignore).
//
// Prerequisites (one-time, same NDK as :speech-whisper-jni):
//   cargo install cargo-ndk
//   rustup target add aarch64-linux-android x86_64-linux-android
//   (NDK ${ProjectConfig.NDK_VERSION}, installed at $ANDROID_HOME/ndk/<version>)

plugins {
    id("deferno.android.nativelib")
    // kotlinx.serialization for the JSON the C ABI returns — same per-module apply as core/network.
    alias(libs.plugins.kotlin.serialization)
}

// cargo-ndk drops libshacl_aio.so/<abi>/ here — the default jniLibs dir AGP packages automatically
// (generated, .gitignored). No custom srcDir registration (the AGP 9 sourceSets DSL mis-casts it).
val rustJniLibs = layout.projectDirectory.dir("src/main/jniLibs")

// The Android SDK location, so cargo-ndk can find the pinned NDK (local.properties wins, env fallback).
val androidSdkDir: String =
    rootProject.file("local.properties").takeIf { it.exists() }
        ?.readLines()?.firstOrNull { it.startsWith("sdk.dir=") }?.substringAfter("=")
        ?: System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: error("Android SDK not found (set sdk.dir in local.properties or ANDROID_HOME)")

android {
    namespace = "com.circuitstitch.deferno.shacl"

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            // 64-bit only, matching :speech-whisper-jni: arm64-v8a (devices) + x86_64 (emulators).
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }
}

dependencies {
    // JNA over the crate's C ABI (libshacl_aio.so). `@aar` pulls JNA's Android dispatcher natives;
    // the version is centralised in the catalog (no clean catalog accessor for the @aar notation).
    implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")
    implementation(libs.kotlinx.serialization.json)

    // On-device check (the headless JVM gate can't load the .so), mirroring core:speech's whisper test.
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

// Build the Rust cdylib for each Android ABI (cargo-ndk reads the pinned NDK via ANDROID_NDK_HOME).
// Wired ahead of preBuild so the .so exists before AGP merges jniLibs.
val buildRustFloor by tasks.registering(Exec::class) {
    group = "build"
    description = "Cross-compiles third_party/shacl-aio into libshacl_aio.so for the Android ABIs."
    workingDir = rootProject.file("third_party/shacl-aio")
    environment("ANDROID_NDK_HOME", "$androidSdkDir/ndk/${ProjectConfig.NDK_VERSION}")
    commandLine(
        "cargo", "ndk",
        "-t", "arm64-v8a",
        "-t", "x86_64",
        "-o", rustJniLibs.asFile.absolutePath,
        "build", "--release",
    )
}

tasks.named("preBuild") { dependsOn(buildRustFloor) }
