import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.gradle.api.provider.Provider
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JvmVendorSpec
// Imported (not fully-qualified) because this JVM-plugin build script binds `java` to the Java extension
// accessor, which shadows the `java.*` package — `java.net.URI(...)` would parse as `(java).net`.
import java.net.URI

plugins {
    // Kotlin/JVM + the shared JVM toolchain (from ProjectConfig). This convention no
    // longer applies Gradle's `application` plugin — Compose Desktop supplies `run`.
    id("deferno.jvm.application")
    // Compose Desktop (ADR-0003: desktop View = Compose Desktop): the runtime + the
    // `compose.desktop.application` packaging/run DSL.
    alias(libs.plugins.compose.multiplatform)
    // The Compose *compiler* plugin (pinned to the Kotlin version via the catalog).
    alias(libs.plugins.kotlin.compose)
    // Hydraulic Conveyor (ADR-0021, #102): the desktop packager + auto-updater. Applied here because
    // it extracts its inputs from THIS module's `compose.desktop.application {}` block (main class,
    // JVM args, vendor) and from Gradle `project.version` → `app.version`, exposing them to the
    // root `conveyor.conf` via the `printConveyorConfig` task. It also adds the per-platform input
    // configurations (`linuxAmd64`, …) wired in `dependencies {}` below.
    alias(libs.plugins.conveyor)
}

// The desktop app runs on, and ships with, **Eclipse Temurin** — the project's standard JDK, everywhere
// (ADR-0021). We previously ran on the JetBrains Runtime for nicer Linux window chrome, but that blame
// was misplaced: the theming gaps were the dark-mode *detection* gap (fixed separately via the XDG
// portal, see Main.kt) and the Skiko/Wayland resize white-bleed (a GPU-surface issue, not a JDK-vendor
// one). So the desktop standardizes on Temurin — no JBR. `compose.desktop.application.javaHome` (set
// below) is what Compose uses for BOTH the dev `run` task and the jpackage/jlink packaging, so the
// bundled distribution runtime is Temurin too — matching the Conveyor package, which jlinks the same
// auto-imported Temurin (conveyor.conf). Resolved (and auto-provisioned) via the Foojay resolver at the
// project's Java 17 level; compile/test already use the shared toolchain. A full JDK ships jmods, so
// jlink/jpackage can build the native distributions.
val temurinRuntimeHome: String = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(17))
    vendor.set(JvmVendorSpec.ADOPTIUM)
}.get().metadata.installationPath.asFile.absolutePath

// --- Dev-PAT codegen (the desktop analog of Android's BuildConfig, #68/#83, ADR-0012) ----------------
// Surface dev-account PATs from the gitignored root local.properties into a generated Kotlin constant
// the app reads at startup, so a developer can open real staging data without committing a token. It
// reads the SAME keys Android does (deferno.dev.accounts / deferno.staging.apiToken) and the shared
// DevAccounts.from(...) parses them — blank when unset, so nothing is seeded. Read via
// providers.fileContents to stay configuration-cache compatible and re-run when the token changes
// (mirrors the deferno.kmp Test wiring). There is no debug/release split on desktop: a distributed
// build simply has no keys configured on the build machine, so no PAT ships.
val localProperties = providers.fileContents(
    rootProject.layout.projectDirectory.file("local.properties"),
).asText.orElse("")

fun devProperty(key: String): Provider<String> =
    localProperties.map { text ->
        text.lineSequence()
            .firstOrNull { it.startsWith("$key=") }
            ?.substringAfter('=')?.trim().orEmpty()
    }

val generatedDevConfigDir = layout.buildDirectory.dir("generated/devconfig/kotlin")

val generateDesktopDevConfig = tasks.register("generateDesktopDevConfig") {
    val devAccounts = devProperty("deferno.dev.accounts")
    val stagingToken = devProperty("deferno.staging.apiToken")
    val outDir = generatedDevConfigDir
    inputs.property("devAccounts", devAccounts)
    inputs.property("stagingToken", stagingToken)
    outputs.dir(outDir)
    doLast {
        // Render a Kotlin string literal (escape backslash + quote; PATs are single-line base64url/JWT).
        fun literal(value: String): String =
            "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        val pkgDir = outDir.get().asFile.resolve("com/circuitstitch/deferno/desktop")
        pkgDir.mkdirs()
        pkgDir.resolve("DesktopDevConfig.kt").writeText(
            """
            |package com.circuitstitch.deferno.desktop
            |
            |/**
            | * Dev-account PATs surfaced from the gitignored `local.properties` at build time (#68/#83,
            | * ADR-0012) — the desktop analog of Android's `BuildConfig`. Both fields are blank when the
            | * keys are unset, so the shared [com.circuitstitch.deferno.DevAccounts].from(...) seeds
            | * nothing. GENERATED by the `generateDesktopDevConfig` Gradle task — do not edit.
            | */
            |internal object DesktopDevConfig {
            |    const val DEV_ACCOUNTS: String = ${literal(devAccounts.get())}
            |    const val DEV_STAGING_TOKEN: String = ${literal(stagingToken.get())}
            |}
            |
            """.trimMargin(),
        )
    }
}

// --- App-version codegen (#103) ---------------------------------------------------------------------
// Surface ProjectConfig.APP_VERSION (= Gradle project.version, the single version SoT, #101) into a
// runtime-readable Kotlin constant — the desktop analog of Android's BuildConfig.VERSION_NAME. The
// in-app "Check for updates" UI uses it to show the running version when the app is NOT launched from a
// Conveyor package (in a real package, Conveyor injects the authoritative `app.version` system property
// and SoftwareUpdateController reports it; this is the dev/unpackaged fallback so the same value shows
// either way). Capturing the version into a plain String at configuration time keeps the task
// configuration-cache compatible (no Project reference reaches the task action).
val generatedBuildConfigDir = layout.buildDirectory.dir("generated/buildconfig/kotlin")

val generateDesktopBuildConfig = tasks.register("generateDesktopBuildConfig") {
    val appVersion = project.version.toString()
    val outDir = generatedBuildConfigDir
    inputs.property("appVersion", appVersion)
    outputs.dir(outDir)
    doLast {
        val pkgDir = outDir.get().asFile.resolve("com/circuitstitch/deferno/desktop")
        pkgDir.mkdirs()
        pkgDir.resolve("DesktopBuildConfig.kt").writeText(
            """
            |package com.circuitstitch.deferno.desktop
            |
            |/**
            | * Build-time constants for the desktop app — the desktop analog of Android's `BuildConfig`.
            | * [APP_VERSION] is [com.circuitstitch.deferno.gradle.ProjectConfig].APP_VERSION (= Gradle
            | * `project.version`, the single version source of truth, #101). GENERATED by the
            | * `generateDesktopBuildConfig` Gradle task — do not edit.
            | */
            |internal object DesktopBuildConfig {
            |    const val APP_VERSION: String = "$appVersion"
            |}
            |
            """.trimMargin(),
        )
    }
}

// Add the generated sources to the main Kotlin source set; passing the TaskProvider makes the Kotlin
// compile task depend on each generator automatically.
kotlin {
    sourceSets.named("main") {
        kotlin.srcDir(generateDesktopDevConfig)
        kotlin.srcDir(generateDesktopBuildConfig)
        // The brand dir rides the classpath so the window/taskbar icon single-sources the same
        // flame.svg conveyor.conf ships as the packaged app icon — no duplicated asset.
        resources.srcDir(rootProject.layout.projectDirectory.dir("core/designsystem/brand"))
    }
}

// Compose Desktop's own application DSL (replaces Gradle's built-in `application`):
// it owns `mainClass` and the `run` task and wires Skiko onto the runtime classpath.
compose.desktop.application {
    mainClass = "com.circuitstitch.deferno.desktop.MainKt"
    // Run + package on Eclipse Temurin (see note above); bundles Temurin into the distribution.
    javaHome = temurinRuntimeHome
    // This `nativeDistributions {}` block drives the legacy jpackage path (`./gradlew packageDeb`),
    // kept as a fallback. The release pipeline is **Conveyor** (ADR-0021, #102), which does its own
    // jlink/packaging and IGNORES these jpackage-only keys (targetFormats/packageName/packageVersion);
    // it imports only `mainClass` + the runtime from this module, and reads the app version from
    // Gradle `project.version` (→ `app.version`), not from `packageVersion`. `conveyor.conf` at the
    // repo root configures the Conveyor outputs.
    nativeDistributions {
        // Each installer format only builds on its host OS; configuring all three is
        // harmless cross-platform (Deb is the one this Linux box can actually produce).
        targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
        packageName = "Deferno"
        // Single version source of truth (ADR-0021, #101): project.version is set from
        // ProjectConfig.APP_VERSION by the deferno.jvm.application convention — the same source the
        // Android versionName/versionCode AND Conveyor's app.version derive from — so nothing is
        // hardcoded here.
        packageVersion = project.version.toString()
        // Bundle the whisper `small.en` weights into the installer (#94, ADR-0019: desktop delivery is
        // installer-bundled, not store-hosted / not downloaded at runtime). Everything under
        // desktopResources/{common,<os>,<os>-<arch>} is packaged next to the app; the platform-agnostic
        // model lives in `common/models/`. At runtime Compose exposes the (flattened) dir via the
        // `compose.application.resources.dir` system property, which BundledModelLocator reads.
        appResourcesRootDir.set(project.layout.projectDirectory.dir("desktopResources"))
    }
}

// --- Bundled whisper model (#94, ADR-0019) -----------------------------------------------------------
// The desktop whisper engine (core:speech jvmMain) loads `small.en` from the packaged extra-resources dir
// (appResourcesRootDir above). The ~190 MB weights are NOT committed — gitignored and fetched on demand so
// a clean checkout stays small and git-clean (the desktop mirror of :speech-model-pack on Android). This
// task downloads the pinned small.en q5_1 weights from Hugging Face into the resources tree if absent. It
// is best-effort: an offline/CI build without the model still produces an app — the whisper engine just
// reports ModelMissing (no mic) until the file is present, never a hard build failure.
val whisperModelFileName = "ggml-small.en-q5_1.bin"

val downloadWhisperModel = tasks.register("downloadWhisperModel") {
    group = "speech"
    description = "Fetches the small.en q5_1 whisper model into the desktop installer resources (ADR-0019)."
    // Capture plain File + String locals (not script-object references) so the task stays
    // configuration-cache compatible (mirrors :speech-model-pack:downloadWhisperModel).
    val target = layout.projectDirectory.file("desktopResources/common/models/$whisperModelFileName").asFile
    val url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$whisperModelFileName"
    outputs.file(target)
    // Treat an already-present, plausibly-complete file as up to date (avoid re-downloading ~190 MB).
    onlyIf { !(target.exists() && target.length() > 150_000_000L) }
    doLast {
        target.parentFile.mkdirs()
        logger.lifecycle("Downloading whisper model from $url …")
        runCatching {
            URI(url).toURL().openStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }.onFailure { e ->
            // Don't fail the build offline; remove any partial file so a later build retries.
            target.delete()
            logger.warn(
                "Could not download the whisper model ($url): ${e.message}. The desktop app will build, " +
                    "but on-device dictation reports ModelMissing until the model is present.",
            )
        }.onSuccess {
            logger.lifecycle("Downloaded ${target.length()} bytes to $target")
        }
    }
}

// Stage the model before it is consumed: `prepareAppResources` copies appResourcesRootDir into the
// packaged distribution (and runDistributable), and the dev `run` reads the source desktopResources/common
// tree directly. Compile/test do NOT depend on it, so a plain build never triggers the ~190 MB download.
tasks.matching { it.name == "prepareAppResources" || it.name == "run" }
    .configureEach { dependsOn(downloadWhisperModel) }

dependencies {
    // Compose Desktop runtime for the host OS (bundles Skiko) + Material 3 + the core Material icon
    // set the nav rail/drawer uses (the androidx adaptive nav-suite the Android shell uses is an
    // Android-only artifact, so desktop builds its own native rail/drawer here).
    implementation(compose.desktop.currentOs)

    // Conveyor per-platform inputs (ADR-0021, #103): the Conveyor Gradle plugin adds a configuration
    // per target machine (linuxAmd64, macAmd64, macAarch64, windowsAmd64); it does NOT auto-collect the
    // Skiko-bearing jars, so the per-OS Compose artifact is declared into each. Linux + Windows + macOS
    // (Intel + Apple Silicon) all cross-build from the single Linux runner — every platform's Skiko
    // natives are prebuilt in these jars, so no per-OS toolchain is needed. macOS is self-signed for now
    // (#103; production signing is #104). `compose.desktop.currentOs` above stays for the dev
    // `./gradlew :app:desktopApp:run` path.
    linuxAmd64(libs.compose.desktop.linux.x64)
    windowsAmd64(libs.compose.desktop.windows.x64)
    macAmd64(libs.compose.desktop.macos.x64)
    macAarch64(libs.compose.desktop.macos.arm64)

    // Conveyor in-app self-update control (#103, ADR-0021): SoftwareUpdateController lets the app read
    // its running + published version and trigger the OS-native update/restart UI (the desktop "Check
    // for updates" menu, see the `update/` package). Returns a no-op (getInstance() == null) when the
    // app is NOT launched from a Conveyor package — so `./gradlew run` and the unit tests degrade
    // gracefully. Desktop-only by decision (self-update is Windows/macOS; ADR-0021).
    implementation(libs.conveyor.control)
    implementation(libs.compose.material3)
    // Just the core Material icon set (the glyphs the nav rail/drawer uses) — not the ≈37 MB
    // `materialIconsExtended` set, which the bundled desktop distribution would otherwise ship.
    implementation(libs.compose.material.icons.core)
    // Compose Resources runtime: Main.kt decodes the shared brand flame.svg window/taskbar icon via
    // `decodeToSvgPainter`. (The New surface's mic glyph moved with the shared form atoms to
    // :app:shell:ui, #175.)
    implementation(libs.compose.components.resources)

    // The shared, Compose-free app Shell (ADR-0017): the desktop renders these components
    // (RootComponent: Auth ↔ Main; the Main shell's Destination graph; AccountSession +
    // AccountComponentSession; DevAccounts) with its own native Views (rail/drawer + in-app menu bar).
    // It replaces the retired desktop `shell/*.kt` component duplicates. app:shell api-exposes
    // Decompose + coroutines + datetime, but its core/feature deps are `implementation`, so the ones
    // the desktop host references directly are named below.
    implementation(project(":app:shell"))
    // The shell's shared New-form atoms (#175): the desktop NewDesktopScreen is chrome (width-capped
    // window layout) around the stateless commonMain atoms — the ADR-0004 #27 pattern.
    implementation(project(":app:shell:ui"))

    // The compile-time DI graph (#68, ADR-0014): the desktop builds the process-global AppComponent and
    // a per-Account AccountComponent off the JVM graph (createAppComponent / createAccountComponent),
    // exactly as DefernoApplication + MainActivity do on Android (ADR-0017).
    implementation(project(":core:di"))
    // PlatformContext(databasesDir) — the per-Account file-backed SQLite location handed to the graph.
    implementation(project(":core:scopes"))
    // DefernoEnvironment selects the backend (Staging) at startup and derives the web-app origin for
    // the Settings deep-links (data export/import, feedback, console URL).
    implementation(project(":core:network"))
    // AccountManager (the RootComponent keys off its activeAccount) + AuthRepository (the Profile
    // Destination's /auth/me), both read off the AppComponent and threaded into the shared root.
    implementation(project(":core:data"))
    // CreateItem.Payload / CommandResult appear in the shared AccountSession API surface the desktop
    // adapts (AccountComponentSession), so they must be resolvable on the desktop compile classpath.
    implementation(project(":core:domain"))
    // Account / AccountId for the in-app Account switcher; ThemeFamily for live theming.
    implementation(project(":core:model"))
    // DefernoTheme + palette for the Compose host (live-themed off the shared root, ADR-0017).
    implementation(project(":core:designsystem"))

    // The feature slices the shared Main shell composes — each renders its existing desktop View (the
    // :ui submodule's jvmMain), the desktop counterpart of the Android screen (ADR-0017). Plan, Calendar
    // (#74), Tasks, Profile (#84), and Settings (#85) all have desktop Views now.
    implementation(project(":feature:plan"))
    implementation(project(":feature:plan:ui"))
    implementation(project(":feature:calendar"))
    // The Calendar Destination's desktop View (#74): the shared Main shell renders it in the content
    // area (its jvmMain CalendarDesktopScreen — the month grid + day agenda, two-pane on a wide window).
    implementation(project(":feature:calendar:ui"))
    implementation(project(":feature:tasks"))
    implementation(project(":feature:tasks:ui"))
    // The paste-PAT sign-in slice the desktop RootShell renders for the Auth shell (#15, ADR-0023): the
    // logic module supplies the SignInComponent type the shell exposes, the :ui submodule the screen.
    implementation(project(":feature:signin"))
    implementation(project(":feature:signin:ui"))
    implementation(project(":feature:profile"))
    // The Profile Destination's desktop View (#84): the shared Main shell renders it in the content
    // area (its jvmMain ProfileDesktopScreen — the identity hub + co-located Account controls).
    implementation(project(":feature:profile:ui"))
    implementation(project(":feature:settings"))
    // The Settings Destination's desktop View (#85): the shared Main shell renders it in the content
    // area (its jvmMain SettingsDesktopScreen, the desktop counterpart of the Android settings screen).
    implementation(project(":feature:settings:ui"))

    // Decompose: the shell/Destination component tree + `subscribeAsState()` Compose bindings, and
    // `LifecycleController`, which drives Essenty's LifecycleRegistry off the desktop window state
    // (the desktop counterpart of Android's `retainedComponent`).
    implementation(libs.decompose)
    implementation(libs.decompose.extensions.compose)
    // The shared root collects AccountManager.activeAccount to drive Decompose navigation, which must
    // run on the UI (AWT EDT) thread — Dispatchers.Swing supplies that dispatcher. The shell observes
    // Flows; the host computes today's date.
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.datetime)
    // The shared logger: main() configures it + emits the first log. Declared directly here (not
    // relied upon transitively from core/common's `api`) because this module names the Logger type
    // itself (amzn/kmp-logger).
    implementation(libs.kmp.logger.log)

    // The pure desktop-View breakpoint test (DesktopNavKindTest) + the update-manager state-machine
    // test (UpdateManagerTest, #103). The shared shell *components* are tested in app/shell's commonTest
    // (ADR-0017), so the desktop no longer duplicates those tests.
    testImplementation(libs.junit)
    // coroutines-test (runTest + TestScope) drives UpdateManagerTest's StateFlow transitions off a
    // controlled dispatcher (the update fetch is a suspend HTTP call in the real backend, #103).
    testImplementation(libs.kotlinx.coroutines.test)
    // The desktop New-overlay render/screenshot test (#87, cf. #39): a Compose-Multiplatform UI test
    // on the JVM-fast path (no device). `uiTestJUnit4` brings `runComposeUiTest` (+ the desktop Skiko
    // renderer transitively) so the test renders the New View over a real DefaultNewComponent.
    testImplementation(libs.compose.ui.test.junit4)
}
