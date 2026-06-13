package com.circuitstitch.deferno.macos

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import com.circuitstitch.deferno.DevAccounts
import com.circuitstitch.deferno.core.common.log.LogLevel
import com.circuitstitch.deferno.core.common.log.Logger
import com.circuitstitch.deferno.core.di.AppComponent
import com.circuitstitch.deferno.core.di.createAccountComponent
import com.circuitstitch.deferno.core.di.createAppComponent
import com.circuitstitch.deferno.core.network.DefernoEnvironment
import com.circuitstitch.deferno.core.scopes.PlatformContext
import com.circuitstitch.deferno.core.speech.SpeechToText
import com.circuitstitch.deferno.macos.agent.DraftTasksBridge
import com.circuitstitch.deferno.macos.agent.NativeInference
import com.circuitstitch.deferno.macos.speech.NativeDictation
import com.circuitstitch.deferno.macos.speech.NativeSpeechToText
import com.circuitstitch.deferno.shell.AccountComponentSession
import com.circuitstitch.deferno.shell.DefaultRootComponent
import com.circuitstitch.deferno.shell.RootComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform
import kotlin.time.Clock
import platform.AppKit.NSWorkspace
import platform.Foundation.NSBundle
import platform.Foundation.NSLocale
import platform.Foundation.NSURL
import platform.Foundation.preferredLanguages

/**
 * The macOS app entry — the **real** shared shell over the real DI graph (ADR-0029 Phase 1b, #188),
 * replacing the in-memory `DefernoDemoRoot` scaffold (Phase 1). It is the macOS twin of iOS's
 * `DefernoRoot` (and Android's `DefernoApplication` + `MainActivity`): one host object, held by
 * SwiftUI's `@main` for the app lifetime, that owns the process-global [AppComponent] **and** the
 * per-scene [RootComponent].
 *
 * On construction it builds the AppScope graph, retains an Essenty [LifecycleRegistry], and constructs
 * the [DefaultRootComponent] the SwiftUI `RootView` renders (Auth ↔ Main → the Destination graph). Off
 * the main thread it hydrates the persisted account roster and seeds any optional dev-PAT Accounts; the
 * [com.circuitstitch.deferno.core.data.account.AccountManager]'s Active-Account `StateFlow` then drives
 * the shell reactively (first paste-PAT sign-in flips it).
 *
 * The macOS native capabilities run **in-process** (ADR-0029): [dictation] wraps `SidecarKit`'s
 * on-device `SpeechTranscriber` (Phase 2) and [inference] wraps Apple Intelligence's Foundation Models
 * (Phase 3, exposed to SwiftUI as [draftTasks]). Both are optional — `null` falls back to the AppScope
 * speech engine / leaves [draftTasks] `null`, so the host still runs without them.
 *
 * Per-Account data (the encrypted SQLite DB the [AccountComponentSession] opens) needs SQLCipher linked
 * in the Xcode app (project.yml); the Auth shell + paste-PAT sign-in path need only the AppScope network
 * client + the Keychain vault, so first-run login works regardless.
 */
class DefernoRoot(
    dictation: NativeDictation? = null,
    inference: NativeInference? = null,
) {

    init {
        // Configure the uniform logger ONCE per process, before the DI graph builds or anything logs.
        // os_log-backed on macOS via the core.common.log facade (ADR-0029) — NOT kmp-logger, which
        // ships no macosArm64 klib. A Release framework binary emits only WARN+ERROR; Debug keeps DEBUG.
        // DefernoRoot is constructed once by SwiftUI's @main, so configure runs exactly once.
        Logger.configure(minLogLevel = startupLogLevel(), prefix = "Deferno")
        Logger("DefernoRoot").i { "Deferno (macOS) starting" }
    }

    // Environment by build configuration: Debug dev builds talk to staging (the dev-PAT target,
    // ADR-0012); Release builds talk to production. The Xcode build phase builds the Kotlin framework
    // for the current Xcode CONFIGURATION, so `Platform.isDebugBinary` tracks Debug↔Release exactly.
    @OptIn(ExperimentalNativeApi::class)
    private val environment =
        if (Platform.isDebugBinary) DefernoEnvironment.Staging else DefernoEnvironment.Production

    private val appComponent: AppComponent = createAppComponent(
        platform = PlatformContext(),
        environment = environment,
    )

    // Startup work (roster hydration + dev seeding) runs off the main thread; the AccountManager's
    // StateFlows then drive the reactive shell. SupervisorJob so one failure doesn't cancel the rest.
    private val bootstrapScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val lifecycle = LifecycleRegistry()
    private val timeZone = TimeZone.currentSystemDefault()
    private val today = Clock.System.todayIn(timeZone)

    // In-process dictation (Phase 2): wrap the injected Swift transcriber, else the AppScope engine
    // (which resolves to the Unavailable floor until a macOS engine is bound — the mic stays hidden).
    private val speechToText: SpeechToText =
        dictation?.let { NativeSpeechToText(it) } ?: appComponent.speechToText

    /**
     * In-process inference (Phase 3): the on-device Brain-dump Extractor over the injected Foundation
     * Models engine, or `null` when no engine is injected. Bridge injection for now — the real DI graph
     * binds the same engine via `MacosAgentBindings` once the engine-choice App setting lands (#150).
     */
    val draftTasks: DraftTasksBridge? =
        inference?.let { DraftTasksBridge(it, today, timeZone.id) }

    /** The shared navigation root the SwiftUI `RootView` renders (Auth ↔ Main → the Destination graph). */
    val root: RootComponent

    init {
        bootstrapScope.launch {
            appComponent.accountManager.load()
            seedDevAccounts()
        }

        root = DefaultRootComponent(
            componentContext = DefaultComponentContext(lifecycle),
            accountManager = appComponent.accountManager,
            // The Profile Destination's /auth/me identity fetch (#70), AppScope + Active-Account-aware.
            authRepository = appComponent.authRepository,
            // Build the per-Account data layer for an Active Account from the DI graph (ADR-0014).
            accountSession = { account ->
                AccountComponentSession(createAccountComponent(appComponent, account))
            },
            // The paste-PAT sign-in service (#15, ADR-0023) the Auth shell drives.
            signInService = appComponent.signInService,
            today = today,
            timeZone = timeZone.id,
            // Settings → App Permissions: macOS has no per-app settings deep-link, so open the
            // system Privacy & Security pane (the closest equivalent of iOS's per-app Settings screen).
            onOpenOsAppSettings = { openExternalUrl(MACOS_PRIVACY_PANE_URL) },
            // Settings → Data & Privacy: no client endpoint at v0.1 (ADR-0015), so open the web app's
            // surface (origin tracks the env), mirroring iOS.
            onOpenDataExportImport = { openExternalUrl(webAppUrl(environment, "settings/data")) },
            // Settings → Help & Feedback (#375): the in-app `Feedback` overlay submits through this service.
            feedbackRepository = appComponent.feedbackRepository,
            // Settings → Security & 2FA: open the Active Account's Zitadel console URL in the browser.
            onOpenConsoleUrl = { url -> openExternalUrl(url) },
            // Dictation (#92, ADR-0018): the in-process engine (Phase 2) or the AppScope fallback, plus
            // the device locale it recognizes and the engine catalog the selector reads.
            speechToText = speechToText,
            locale = currentLocaleTag(),
            speechEngineCatalog = appComponent.speechEngineCatalog,
            // Agent inference-engine choice + entitlement gate (#150): threaded from the AppScope graph; macOS
            // has no Agent Settings surface yet and the inference floor is NotConfigured, but the gate exists app-wide.
            inferenceEngineCatalog = appComponent.inferenceEngineCatalog,
            // The AppScope connectivity monitor (#158): the outbox driver flushes on the
            // offline→online edge and skips passes while known-offline.
            connectivity = appComponent.connectivity,
        )

        lifecycle.resume()
    }

    /** Tears down the retained component tree when the SwiftUI app scene goes away. */
    fun destroy() {
        lifecycle.destroy()
    }

    /**
     * The OAuth redirect hand-off **fallback** (ADR-0026, #137): the Swift URL handler (`onOpenURL`)
     * forwards every incoming URL here; a custom-scheme auth redirect is published into the AppScope
     * [com.circuitstitch.deferno.core.data.auth.AuthRedirectInbox]. The macOS twin of iOS's
     * `forwardAuthRedirect` — paste-PAT sign-in doesn't await it; this path only matters once the real
     * browser OAuth leg lands (#189) and the redirect arrives from an externally-opened browser.
     */
    fun forwardAuthRedirect(url: String) {
        if (url.startsWith("$AUTH_REDIRECT_SCHEME://")) {
            appComponent.authRedirectInbox.publish(url)
        }
    }

    private companion object {
        const val AUTH_REDIRECT_SCHEME = "com.circuitstitch.deferno"

        // macOS has no per-app Settings deep-link; this opens System Settings' Privacy & Security pane.
        const val MACOS_PRIVACY_PANE_URL =
            "x-apple.systempreferences:com.apple.preference.security?Privacy"
    }

    /**
     * Idempotent dev-PAT seeding (the macOS analogue of `DefernoApplication.seedDevAccounts`): the two
     * optional Info.plist strings (`DevAccounts` / `DevStagingToken`) feed [DevAccounts.from] so a
     * developer can open on real staging data without typing. Both absent in a normal build → nothing
     * is seeded (no PAT ships). Only Accounts not already in the roster are added.
     */
    private suspend fun seedDevAccounts() {
        val manager = appComponent.accountManager
        val existing = manager.accounts.value.map { it.id }.toSet()
        DevAccounts.from(infoPlistString("DevAccounts"), infoPlistString("DevStagingToken"))
            .filter { it.account.id !in existing }
            .forEach { devAccount -> manager.addAccount(devAccount.account, devAccount.token) }
    }
}

/**
 * The startup minimum log level: WARN in a Release framework binary so only warnings + errors reach
 * os_log in prod, DEBUG in a Debug binary. [Platform.isDebugBinary] reflects the build configuration
 * the framework was linked for.
 */
@OptIn(ExperimentalNativeApi::class)
private fun startupLogLevel(): LogLevel =
    if (Platform.isDebugBinary) LogLevel.DEBUG else LogLevel.WARN

/**
 * Build a web-app URL for [path] from the configured backend [environment] (#72) — the macOS twin of
 * iOS's `webAppUrl`. The web app shares the API host; [DefernoEnvironment.baseUrl] carries the `/api/`
 * prefix, so the web origin is that base with the `/api` suffix dropped.
 */
private fun webAppUrl(environment: DefernoEnvironment, path: String): String {
    val origin = environment.baseUrl.removeSuffix("/").removeSuffix("/api")
    return "$origin/$path"
}

/** Open [urlString] via [NSWorkspace] — Safari for web URLs, System Settings for the Privacy pane URL. */
private fun openExternalUrl(urlString: String) {
    val url = NSURL.URLWithString(urlString) ?: return
    NSWorkspace.sharedWorkspace.openURL(url)
}

/** The device locale as a BCP-47 tag (e.g. `en-US`) for speech-engine availability + dictation. */
private fun currentLocaleTag(): String =
    (NSLocale.preferredLanguages.firstOrNull() as? String) ?: "en-US"

/** An optional Info.plist string (used for the dev-PAT seeding keys), or empty when absent. */
private fun infoPlistString(key: String): String =
    (NSBundle.mainBundle.objectForInfoDictionaryKey(key) as? String) ?: ""
