package com.circuitstitch.deferno.ios

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import com.circuitstitch.deferno.DevAccounts
import com.circuitstitch.deferno.core.di.AppComponent
import com.circuitstitch.deferno.core.di.createAccountComponent
import com.circuitstitch.deferno.core.di.createAppComponent
import com.circuitstitch.deferno.core.agent.IosOnDeviceInference
import com.circuitstitch.deferno.core.network.DefernoEnvironment
import com.circuitstitch.deferno.core.scopes.PlatformContext
import com.circuitstitch.deferno.core.speech.SpeechToText
import com.circuitstitch.deferno.feature.assistant.AssistantStream
import com.circuitstitch.deferno.ios.agent.NativeInference
import com.circuitstitch.deferno.ios.assistant.NativeAssistantStream
import com.circuitstitch.deferno.ios.assistant.NativeAssistantTransport
import com.circuitstitch.deferno.ios.agent.NativeInferenceEngine
import com.circuitstitch.deferno.ios.speech.NativeDictation
import com.circuitstitch.deferno.ios.speech.NativeFileTranscriber
import com.circuitstitch.deferno.ios.speech.NativeSpeechToText
import com.circuitstitch.deferno.shell.AccountComponentSession
import com.circuitstitch.deferno.shell.DefaultRootComponent
import com.circuitstitch.deferno.shell.RootComponent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform
import kotlin.time.Clock
import platform.BackgroundTasks.BGTask
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSBundle
import platform.Foundation.NSLocale
import platform.Foundation.NSURL
import platform.Foundation.preferredLanguages
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import software.amazon.app.kmplogger.LogLevel
import software.amazon.app.kmplogger.Logger
import software.amazon.app.kmplogger.logger

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
 *
 * [recorder] is the injected Swift mic recorder (#267, ADR-0037) the Brain dump `record` seam drives; `null`
 * (e.g. a unit host) leaves that seam the inert no-op default. [dictation] is the injected Swift on-device
 * `SFSpeechRecognizer` engine (#268, ADR-0018) the New surface's mic drives; `null` leaves the AppScope
 * Unavailable floor (so the mic stays hidden). [inference] is the injected Swift Apple Foundation Models
 * engine (#269, ADR-0037) installed into the DI graph's on-device forwarder; [fileTranscriber] is the
 * injected Swift `SpeechTranscriber` the Brain dump pipeline transcribes the WAV with. Both `null` (a unit
 * host, or a device without Apple Intelligence) leave the take to salvage — input is never wasted.
 */
class DefernoRoot(
    private val recorder: NativeAudioRecorder? = null,
    dictation: NativeDictation? = null,
    inference: NativeInference? = null,
    private val fileTranscriber: NativeFileTranscriber? = null,
    // The injected Swift SSE turn-stream transport (#282, ADR-0040). `null` (a unit host) leaves the
    // graceful no-op AssistantStream.NONE, so a turn says "not available here" rather than hanging.
    transport: NativeAssistantTransport? = null,
    // The backend environment, INJECTED per Xcode build configuration (ADR-0047), decoupled from
    // `Platform.isDebugBinary`. The real Swift entry point reads the `DefernoEnv` Info.plist value (fed
    // by the per-config `DEFERNO_ENV` build setting) and resolves it via [defernoEnvironment] /
    // [DefernoEnvironment.fromName], which fails safe to Production for an unknown/absent value. This
    // Staging default is ONLY the fallback for a unit host that constructs DefernoRoot without an env
    // (matching the prior debug-binary test behaviour) — shipping builds always pass one explicitly.
    private val environment: DefernoEnvironment = DefernoEnvironment.Staging,
) {

    init {
        // Configure the shared logger ONCE per process, before the DI graph builds or anything logs
        // (amzn/kmp-logger). This `init` is declared first so it runs ahead of the `appComponent`
        // property initializer below. The `prefix` makes tags "Deferno: <ClassName>"; logs route to
        // os_log (Console.app / Xcode). A Release framework binary emits only WARN + ERROR; a Debug
        // binary keeps DEBUG (see startupLogLevel). DefernoRoot is constructed once by SwiftUI's
        // @main, so `configure` (which throws if called twice) is only ever called once.
        Logger.configure(minLogLevel = startupLogLevel(), prefix = "Deferno")
        logger.i { "Deferno (iOS) starting" }
    }

    private val appComponent: AppComponent = createAppComponent(
        platform = PlatformContext(),
        environment = environment,
    )

    // Startup work (roster hydration + dev seeding) runs off the main thread; the AccountManager's
    // StateFlows then drive the reactive shell. SupervisorJob so one failure doesn't cancel the rest.
    private val bootstrapScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // The device time zone the shell + the #270 sweep/backstop reconstruct each take's date context from.
    private val systemTimeZone = TimeZone.currentSystemDefault()

    // One-time startup hydration — the account roster load + dev seeding (#270). A memoized [Deferred] so the
    // single [DefaultAccountManager.load] (not thread-safe across concurrent callers) runs exactly once, and
    // EVERY brain-dump sweep path (the relaunch sweep + the BGProcessingTask backstop) awaits the same result
    // before touching the pipeline — so a sweep never races the load and sees a null Active Account (which
    // would otherwise strand the take). Eager (async starts now); failures surface on await, caught there.
    private val bootstrapped: Deferred<Unit> = bootstrapScope.async {
        appComponent.accountManager.load()
        seedDevAccounts()
    }

    // Dictation (#268, ADR-0018): wrap the injected Swift SFSpeech engine, else the AppScope selector
    // (which resolves to the Unavailable floor until an iOS engine is bound — the New mic stays hidden).
    private val speechToText: SpeechToText =
        dictation?.let { NativeSpeechToText(it) } ?: appComponent.speechToText

    // The Assistant SSE turn-stream (#282, ADR-0040): wrap the injected Swift transport with Kotlin-owned
    // base URL + the Active-Account Bearer PAT (read fresh per turn); a null transport leaves the graceful
    // NONE. Paired with the live appComponent.assistantClient passed below.
    private val assistantStream: AssistantStream =
        transport?.let { NativeAssistantStream(it, environment.baseUrl, appComponent.bearerTokenProvider::currentToken) }
            ?: AssistantStream.NONE

    init {
        // On-device inference (#269, ADR-0037): install the Swift Foundation Models engine into the DI graph's
        // OnDeviceFoundationModels forwarder, so the routed appComponent.inferenceEngine (the iOS default)
        // reaches it. A null engine (a unit host) or a non-Apple-Intelligence device leaves the NotConfigured
        // floor, so the Brain dump pipeline salvages rather than silently producing nothing.
        inference?.let { IosOnDeviceInference.install(NativeInferenceEngine(it)) }
    }

    // Brain dump's record→Inbox seam (#267, ADR-0037): records the mic to a durable WAV, then on Stop hands
    // the take to the shared pipeline on an app-scope coroutine — the WorkManager-less iOS twin of Android's
    // background worker. A null recorder keeps the inert no-op default (the desktop/unit behaviour).
    private val recordBrainDump: suspend (LocalDate, String) -> Unit = { today, timeZone ->
        recorder?.let { recordBrainDumpTake(it, today, timeZone) }
    }

    private val lifecycle = LifecycleRegistry()

    /** The shared navigation root the SwiftUI `RootView` renders. */
    val root: RootComponent

    init {
        // #270 relaunch sweep: after the roster has hydrated (so processBrainDumpTake sees the Active Account),
        // recover any take whose processing was killed mid-flight — its drafts or salvage land now.
        bootstrapScope.launch {
            runCatching { bootstrapped.await() }
            sweepPendingBrainDumps(appComponent, currentLocaleTag(), fileTranscriber, systemTimeZone.id)
        }
        // #270 backstop: register the BGProcessingTask launch handler before the app finishes launching, so a
        // take whose grace window expired while backgrounded can be finished when iOS later wakes the app.
        registerBrainDumpBackstop()

        val timeZone = systemTimeZone
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
            // Settings → Data & Privacy: no client endpoint at v0.1 (ADR-0015), so it stays a REACHABLE
            // web action — open the web app's surface (the origin tracks the env).
            onOpenDataExportImport = { openExternalUrl(webAppUrl(environment, "settings/data")) },
            // Settings → Help & Feedback (#375): an in-app shell overlay now (the `Feedback` OverlayChild,
            // surfaced to SwiftUI via ShellBridge.overlayFeedback). Submits through this AppScope service.
            feedbackRepository = appComponent.feedbackRepository,
            // Settings → Security & 2FA: open the Active Account's Zitadel console URL in Safari.
            onOpenConsoleUrl = { url -> openExternalUrl(url) },
            // Dictation (#92/#268, ADR-0018): the injected on-device SFSpeech engine (or the AppScope
            // Unavailable floor when none is injected — the mic stays hidden) + the device locale it
            // recognizes. The New surface's per-field mic drives listen() through this seam.
            speechToText = speechToText,
            locale = currentLocaleTag(),
            speechEngineCatalog = appComponent.speechEngineCatalog,
            // Agent inference-engine choice + entitlement gate (#150): threaded from the AppScope graph; iOS
            // has no Agent Settings surface yet, but the gate exists app-wide (the selection defaults to Off).
            inferenceEngineCatalog = appComponent.inferenceEngineCatalog,
            // The device-local "Brain dump notifications" opt-in (#266/#271): the SAME NSUserDefaults-backed
            // preference the pipeline's notifier reads, so the Settings toggle and the notifier agree.
            brainDumpNotificationPreference = appComponent.brainDumpNotificationPreference,
            // Brain dump's record→Inbox seam (#267, ADR-0037): records the mic to a durable WAV and hands
            // the take to the shared pipeline on Stop (salvage-only until #269 wires the file transcriber).
            recordBrainDump = recordBrainDump,
            // The AppScope connectivity monitor (#158): the outbox driver flushes on the
            // offline→online edge and skips passes while known-offline.
            connectivity = appComponent.connectivity,
            // The server-mediated Assistant (ADR-0040, #282): the AppScope request/response client gates the
            // entitled-only Destination and drives availability / enable+consent / apply / conversations —
            // all live the moment the Org is entitled. The SSE turn stream rides the injected Swift
            // URLSession transport (Deferno#485), wrapped as [assistantStream] above; a unit host with no
            // transport leaves the graceful NONE, so a turn says "not available here" rather than hanging.
            assistantClient = appComponent.assistantClient,
            assistantStream = assistantStream,
            // The read-surface session-expiry banner flag (#297): the shared client sets it on a 401.
            reauthRequests = appComponent.reauthRequests,
            // The outbox flush does synchronous SQLite I/O — keep it off the Main lifecycle scope.
            // (Dispatchers.IO is internal on Kotlin/Native in coroutines 1.11; Default is the public off-main pool.)
            outboxFlushContext = Dispatchers.Default,
        )

        lifecycle.resume()
    }

    /** Tears down the retained component tree when the SwiftUI app scene goes away. */
    fun destroy() {
        lifecycle.destroy()
    }

    /**
     * Open the Inbox in the Main shell (#271) — the target of the Brain dump completion notification's tap.
     * The Swift `UNUserNotificationCenterDelegate` calls this on a `didReceive` for the brain-dump category;
     * [RootComponent.openInbox] switches the shell to the Inbox now, or defers if the Auth shell is up.
     */
    fun forwardOpenInbox() = root.openInbox()

    /**
     * Register the #270 BGProcessingTask launch handler. When iOS later runs the task (a grace-expired take
     * scheduled it via [scheduleBrainDumpBackstop]), sweep the durable pending dir to finish any leftover
     * take, then mark the task complete; the expiration handler cancels the sweep if the OS reclaims the
     * time. Best-effort (runCatching) — registration only throws if called after the app finishes launching,
     * in which case the relaunch sweep still recovers every take on the next cold start.
     */
    private fun registerBrainDumpBackstop() {
        runCatching {
            BGTaskScheduler.sharedScheduler.registerForTaskWithIdentifier(
                identifier = BRAIN_DUMP_BG_TASK_ID,
                usingQueue = null,
            ) { task: BGTask? ->
                val bgTask = task ?: return@registerForTaskWithIdentifier
                val job = bootstrapScope.launch {
                    // Await the same one-time hydration the relaunch sweep does: on a cold BACKGROUND launch
                    // for this task, the roster may not be loaded yet, and sweeping with a null Active Account
                    // would strand the take. Then finish any leftover take and mark the task complete.
                    runCatching { bootstrapped.await() }
                    sweepPendingBrainDumps(appComponent, currentLocaleTag(), fileTranscriber, systemTimeZone.id)
                    bgTask.setTaskCompletedWithSuccess(true)
                }
                bgTask.expirationHandler = { job.cancel() }
            }
        }
    }

    /**
     * The iOS recorder seam (#267, ADR-0037): start the mic, suspend until either the overlay's Stop cancels
     * this job **or** the mic engine fails to open, then — under [NonCancellable] so a closing overlay never
     * half-writes — finalize the WAV and, on a real Stop, launch the shared pipeline on the app-lifetime
     * [bootstrapScope] (so processing outlives the overlay). A mic-open failure rethrows, so the shared
     * `DefaultBrainDumpComponent` flips to its gentle Failed state (Android parity — a take is never silently
     * lost), and the empty WAV is dropped.
     *
     * Threading: this seam runs on the Decompose component context, which is [Dispatchers.Main] (the root's
     * default, threaded through the overlay scope) — so [NativeAudioRecorder.stop]'s synchronous Swift
     * teardown runs **on the main thread**. The Swift `Thread.isMainThread` guard (which runs the teardown
     * inline rather than `main.sync`-ing onto the queue it is already on) is what keeps it deadlock-free — it
     * is load-bearing, not defensive. The heavy pipeline work runs off-main on [bootstrapScope]
     * ([Dispatchers.Default]). [createdAt] is the take's single instant — the retained recording's key —
     * captured here at the recorder boundary (the host's job; the pipeline itself stays clock-free).
     */
    private suspend fun recordBrainDumpTake(recorder: NativeAudioRecorder, today: LocalDate, timeZone: String) {
        val createdAt = Clock.System.now()
        val wavPath = brainDumpPendingWavPath(createdAt)
        ensureBrainDumpPendingDir()
        // Completed exceptionally only if the mic engine never opened (start() is async, so it can't throw).
        // On a normal Stop the job is cancelled, this stays incomplete, and the take is handed off.
        val micFailed = CompletableDeferred<Unit>()
        recorder.start(wavPath) {
            micFailed.completeExceptionally(IllegalStateException("brain-dump mic engine failed to start"))
        }
        try {
            micFailed.await() // suspends until Stop cancels this job; throws if the mic never opened
        } finally {
            withContext(NonCancellable) {
                recorder.stop() // finalizes the WAV synchronously (inline on main — see the threading note)
                if (micFailed.isCompleted) {
                    deleteFile(wavPath) // mic never opened — nothing captured; the rethrow flips Phase.Failed
                } else {
                    bootstrapScope.launch {
                        // #270: atomically claim the finalized WAV (rename to .processing) so the relaunch
                        // sweep / BGProcessingTask backstop can't also grab it; if the claim is lost (a sweep
                        // already took it), this in-process run stands down.
                        val claimed = claimPendingTake(wavPath) ?: return@launch
                        processBrainDumpTake(
                            appComponent = appComponent,
                            wavPath = claimed,
                            locale = currentLocaleTag(),
                            transcriber = fileTranscriber,
                            today = today,
                            timeZone = timeZone,
                            createdAt = createdAt,
                        )
                    }
                }
            }
        }
    }

    /**
     * The OAuth redirect hand-off **fallback** (ADR-0026, #137): the Swift URL handler (`onOpenURL` /
     * `application(_:open:)`) forwards every incoming URL here; a custom-scheme auth redirect is
     * published into the AppScope [com.circuitstitch.deferno.core.data.auth.AuthRedirectInbox] — the
     * iOS twin of `MainActivity.forwardAuthRedirect`. Other URLs are ignored. Since the
     * `IosBrowserAuthenticator` moved to `ASWebAuthenticationSession` (which captures its own
     * redirect in-sheet), nothing awaits the inbox in the normal flow — publishing is then a no-op;
     * this path only matters if the auth redirect ever arrives from an externally-opened browser.
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
     * is seeded (no PAT ships). Re-seeds on every launch so rotating a dev PAT in `Secrets.xcconfig`
     * takes effect without a clean reinstall: [addAccount] upserts the token (`vault.putBearerToken`)
     * and leaves the active account untouched once one is set, so the refresh is idempotent.
     */
    private suspend fun seedDevAccounts() {
        val manager = appComponent.accountManager
        DevAccounts.from(infoPlistString("DevAccounts"), infoPlistString("DevStagingToken"))
            .forEach { devAccount -> manager.addAccount(devAccount.account, devAccount.token) }
    }
}

/**
 * The startup minimum log level: WARN in a Release framework binary so only warnings + errors reach
 * os_log in prod, DEBUG in a Debug binary. [Platform.isDebugBinary] reflects the build configuration
 * (Xcode Debug vs Release) the framework was linked for.
 */
@OptIn(ExperimentalNativeApi::class)
private fun startupLogLevel(): LogLevel =
    if (Platform.isDebugBinary) LogLevel.DEBUG else LogLevel.WARN

/**
 * Maps the Info.plist `DefernoEnv` string to the backend enum (ADR-0047), failing safe to Production.
 * Kept as a top-level function purely to give the Swift entry point a clean `DefernoRootKt`-namespaced
 * call site (`DefernoRootKt.defernoEnvironment(name:)`); the policy itself lives in the single source of
 * truth [DefernoEnvironment.fromName], shared with the Android entry point (and, later, macOS/desktop).
 */
fun defernoEnvironment(name: String?): DefernoEnvironment = DefernoEnvironment.fromName(name)

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
