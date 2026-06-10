package com.circuitstitch.deferno.ios

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import com.circuitstitch.deferno.DevAccounts
import com.circuitstitch.deferno.core.di.AppComponent
import com.circuitstitch.deferno.core.di.createAccountComponent
import com.circuitstitch.deferno.core.di.createAppComponent
import com.circuitstitch.deferno.core.network.DefernoEnvironment
import com.circuitstitch.deferno.core.scopes.PlatformContext
import com.circuitstitch.deferno.shell.AccountComponentSession
import com.circuitstitch.deferno.shell.DefaultRootComponent
import com.circuitstitch.deferno.shell.RootComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.time.Clock
import platform.Foundation.NSBundle
import platform.Foundation.NSLocale
import platform.Foundation.NSURL
import platform.Foundation.preferredLanguages
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString

/**
 * The iOS app entry — the **real** shared shell over the real DI graph (#35, ADR-0008/0014/0017),
 * replacing the in-memory `DefernoDemo` scaffold (#51). It is the iOS analogue of Android's
 * `DefernoApplication` (the process-global [AppComponent]) **and** `MainActivity` (the per-scene
 * [RootComponent]) in one host object, held by SwiftUI's `@main` for the app lifetime.
 *
 * On construction it builds the process-global AppScope graph, retains an Essenty
 * [LifecycleRegistry], and constructs the [DefaultRootComponent] the SwiftUI `RootView` renders
 * (Auth ↔ Main → the Destination graph). Off the main thread it hydrates the persisted account
 * roster and seeds any optional dev-PAT Accounts; the [com.circuitstitch.deferno.core.data.account.AccountManager]'s
 * Active-Account `StateFlow` then drives the shell reactively (first paste-PAT sign-in flips it).
 *
 * Per-Account data (the encrypted SQLite DB the [AccountComponentSession] opens) requires the
 * SQLCipher dependency linked in the Xcode project; the Auth shell + paste-PAT sign-in path need only
 * the AppScope network client + the Keychain vault, so first-run login works regardless.
 */
class DefernoRoot {

    // Debug dev builds talk to staging (the dev-PAT target, ADR-0012). A real environment selector by
    // build configuration is a follow-up (mirrors DefernoApplication).
    private val environment = DefernoEnvironment.Staging

    private val appComponent: AppComponent = createAppComponent(
        platform = PlatformContext(),
        environment = environment,
    )

    // Startup work (roster hydration + dev seeding) runs off the main thread; the AccountManager's
    // StateFlows then drive the reactive shell. SupervisorJob so one failure doesn't cancel the rest.
    private val bootstrapScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val lifecycle = LifecycleRegistry()

    /** The shared navigation root the SwiftUI `RootView` renders. */
    val root: RootComponent

    init {
        bootstrapScope.launch {
            appComponent.accountManager.load()
            seedDevAccounts()
        }

        val timeZone = TimeZone.currentSystemDefault()
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
            today = Clock.System.todayIn(timeZone),
            timeZone = timeZone.id,
            // Settings → App Permissions: deep-link to this app's iOS Settings screen (#72).
            onOpenOsAppSettings = { openExternalUrl(UIApplicationOpenSettingsURLString) },
            // Settings → Data & Privacy / Help & Feedback: no client endpoint at v0.1 (ADR-0015), so
            // these are REACHABLE web actions — open the web app's surface (the origin tracks the env).
            onOpenDataExportImport = { openExternalUrl(webAppUrl(environment, "settings/data")) },
            onOpenSubmitFeedback = { openExternalUrl(webAppUrl(environment, "feedback")) },
            // Settings → Security & 2FA: open the Active Account's Zitadel console URL in Safari.
            onOpenConsoleUrl = { url -> openExternalUrl(url) },
            // Dictation (#92, ADR-0018): the AppScope speech engine + the device locale it recognizes.
            // No iOS engine ships yet (#94/#95) → the selector resolves to the Unavailable floor, so the
            // New surface's mic simply stays hidden (dictationAvailable = false).
            speechToText = appComponent.speechToText,
            locale = currentLocaleTag(),
            speechEngineCatalog = appComponent.speechEngineCatalog,
        )

        lifecycle.resume()
    }

    /** Tears down the retained component tree when the SwiftUI app scene goes away. */
    fun destroy() {
        lifecycle.destroy()
    }

    /**
     * The OAuth redirect hand-off (ADR-0026, #137): the Swift URL handler (`onOpenURL` /
     * `application(_:open:)`) forwards every incoming URL here; a custom-scheme auth redirect is
     * published into the AppScope [com.circuitstitch.deferno.core.data.auth.AuthRedirectInbox] the
     * in-flight `IosBrowserAuthenticator` awaits — the iOS twin of `MainActivity.forwardAuthRedirect`.
     * Other URLs are ignored. (Registering the `CFBundleURLTypes` scheme and wiring `onOpenURL` is
     * the macOS-gated device-verify follow-up, ADR-0006.)
     */
    fun forwardAuthRedirect(url: String) {
        if (url.startsWith("$AUTH_REDIRECT_SCHEME://")) {
            appComponent.authRedirectInbox.publish(url)
        }
    }

    private companion object {
        const val AUTH_REDIRECT_SCHEME = "com.circuitstitch.deferno"
    }

    /**
     * Idempotent dev-PAT seeding (the iOS analogue of `DefernoApplication.seedDevAccounts`): the two
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
 * Build a web-app URL for [path] from the configured backend [environment] (#72) — the iOS twin of
 * `MainActivity.webAppUrl`. The web app shares the API host; [DefernoEnvironment.baseUrl] carries the
 * `/api/` prefix, so the web origin is that base with the `/api` suffix dropped.
 */
private fun webAppUrl(environment: DefernoEnvironment, path: String): String {
    val origin = environment.baseUrl.removeSuffix("/").removeSuffix("/api")
    return "$origin/$path"
}

/** Open [urlString] in the appropriate iOS app (Safari for web, the Settings app for the settings URL). */
private fun openExternalUrl(urlString: String) {
    val url = NSURL.URLWithString(urlString) ?: return
    val application = UIApplication.sharedApplication
    if (application.canOpenURL(url)) {
        application.openURL(url, options = emptyMap<Any?, Any?>(), completionHandler = null)
    }
}

/** The device locale as a BCP-47 tag (e.g. `en-US`) for speech-engine availability + dictation. */
private fun currentLocaleTag(): String =
    (NSLocale.preferredLanguages.firstOrNull() as? String) ?: "en-US"

/** An optional Info.plist string (used for the dev-PAT seeding keys), or empty when absent. */
private fun infoPlistString(key: String): String =
    (NSBundle.mainBundle.objectForInfoDictionaryKey(key) as? String) ?: ""
