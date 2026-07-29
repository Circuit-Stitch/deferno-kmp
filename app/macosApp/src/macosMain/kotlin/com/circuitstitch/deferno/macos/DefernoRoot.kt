package com.circuitstitch.deferno.macos

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import com.circuitstitch.deferno.DevAccounts
import com.circuitstitch.deferno.core.agent.MacosOnDeviceInference
import com.circuitstitch.deferno.core.common.log.LogLevel
import com.circuitstitch.deferno.core.common.log.Logger
import com.circuitstitch.deferno.core.di.AppComponent
import com.circuitstitch.deferno.core.di.createAccountComponent
import com.circuitstitch.deferno.core.di.createAppComponent
import com.circuitstitch.deferno.core.network.DefernoEnvironment
import com.circuitstitch.deferno.core.scopes.PlatformContext
import com.circuitstitch.deferno.core.speech.SpeechToText
import com.circuitstitch.deferno.feature.assistant.AssistantStream
import com.circuitstitch.deferno.macos.agent.DraftTasksBridge
import com.circuitstitch.deferno.macos.agent.NativeInference
import com.circuitstitch.deferno.macos.agent.NativeInferenceEngine
import com.circuitstitch.deferno.macos.assistant.NativeAssistantStream
import com.circuitstitch.deferno.macos.assistant.NativeAssistantTransport
import com.circuitstitch.deferno.macos.speech.NativeDictation
import com.circuitstitch.deferno.macos.speech.NativeFileTranscriber
import com.circuitstitch.deferno.macos.speech.NativeSpeechToText
import com.circuitstitch.deferno.shell.AccountComponentSession
import com.circuitstitch.deferno.shell.DefaultRootComponent
import com.circuitstitch.deferno.shell.RootComponent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
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
 * (Phase 3) — installed into the DI graph's on-device forwarder so the routed AppScope `InferenceEngine`
 * resolves to it for the Brain-dump pipeline (#368 Tranche 5), and exposed to SwiftUI's dev Extractor
 * panel as [draftTasks]. Both are optional — `null` falls back to the AppScope speech engine / leaves the
 * NotConfigured inference floor + a `null` [draftTasks], so the host still runs without them.
 *
 * [recorder] is the injected Swift `AVAudioEngine` mic recorder (#368 Tranche 5, ADR-0037) the Brain dump
 * `record` seam drives; `null` (a unit host) leaves that seam the inert no-op default. [fileTranscriber] is
 * the injected Swift macOS-26 `SpeechTranscriber` the pipeline transcribes the finalized WAV with; `null`
 * (a pre-macOS-26 Mac, or a unit host) leaves the take to **salvage** — input is never wasted (ADR-0037).
 *
 * Per-Account data (the encrypted SQLite DB the [AccountComponentSession] opens) needs SQLCipher linked
 * in the Xcode app (project.yml); the Auth shell + paste-PAT sign-in path need only the AppScope network
 * client + the Keychain vault, so first-run login works regardless.
 */
class DefernoRoot(
    private val recorder: NativeAudioRecorder? = null,
    dictation: NativeDictation? = null,
    inference: NativeInference? = null,
    private val fileTranscriber: NativeFileTranscriber? = null,
    // The injected Swift SSE turn-stream transport (#282, ADR-0040). `null` (a unit host) leaves the
    // graceful no-op AssistantStream.NONE, so a turn says "not available here" rather than hanging.
    transport: NativeAssistantTransport? = null,
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

    // One-time startup hydration — the account roster load + dev seeding. A memoized [Deferred] so the single
    // [DefaultAccountManager.load] (not thread-safe across concurrent callers) runs exactly once, and the
    // brain-dump relaunch sweep awaits the same result before touching the pipeline — so a sweep never races
    // the load and sees a null Active Account (which would otherwise strand the take, #270/#368 Tranche 5).
    // Eager (async starts now); failures surface on await, caught there.
    private val bootstrapped: Deferred<Unit> = bootstrapScope.async {
        appComponent.accountManager.load()
        seedDevAccounts()
    }

    private val lifecycle = LifecycleRegistry()
    private val timeZone = TimeZone.currentSystemDefault()
    private val today = Clock.System.todayIn(timeZone)

    // In-process dictation (Phase 2): wrap the injected Swift transcriber, else the AppScope engine
    // (which resolves to the Unavailable floor until a macOS engine is bound — the mic stays hidden).
    private val speechToText: SpeechToText =
        dictation?.let { NativeSpeechToText(it) } ?: appComponent.speechToText

    // The Assistant SSE turn-stream (#282, ADR-0040): wrap the injected Swift transport with Kotlin-owned
    // base URL + the Active-Account Bearer PAT (read fresh per turn); a null transport leaves the graceful
    // NONE. Paired with the live appComponent.assistantClient passed below.
    private val assistantStream: AssistantStream =
        transport?.let { NativeAssistantStream(it, environment.baseUrl, appComponent.bearerTokenProvider::currentToken) }
            ?: AssistantStream.NONE

    init {
        // In-process inference (ADR-0029 Phase 3, ADR-0037): install the Swift Foundation Models engine into
        // the DI graph's OnDeviceFoundationModels forwarder, so the **routed** appComponent.inferenceEngine
        // resolves to a real engine. That seam is on the live product path since #368 Tranche 5: the Brain-dump
        // pipeline builds `Extractor(appComponent.inferenceEngine)` (see BrainDumpRecording.kt), so this install
        // is what turns a transcript into real drafts rather than a salvage. `MacosAgentBindings` binds the
        // router and this forwarder; the Apple-wide persisted default is already OnDeviceFoundationModels, so a
        // fresh Mac routes here with no setting touched. A null engine (a unit host) or a Mac without Apple
        // Intelligence leaves the NotConfigured floor — a typed failure the caller salvages from, never a
        // silent nothing. The macOS twin of iOS's `IosOnDeviceInference.install`.
        inference?.let { MacosOnDeviceInference.install(NativeInferenceEngine(it)) }
    }

    /**
     * The Phase-3 Extractor **dev surface** (`AI/DraftExtractorView.swift`): the on-device Brain-dump
     * Extractor over the injected engine, or `null` when none is injected. It deliberately holds the
     * engine directly rather than the routed seam — it is a developer probe of *this* engine, unaffected
     * by the inference-engine App setting (#150). The product path now goes through the graph (above): the
     * Brain-dump pipeline landed in #368 Tranche 5 and reads the routed seam.
     */
    val draftTasks: DraftTasksBridge? =
        inference?.let { DraftTasksBridge(it, today, timeZone.id) }

    // Brain dump's record→Inbox seam (#368 Tranche 5, ADR-0037): records the mic to a durable WAV, then on
    // Stop hands the take to the shared pipeline on an app-scope coroutine — the WorkManager-less macOS twin
    // of Android's background worker, and the smaller twin of iOS's (no grace window, no BGTask backstop —
    // see BrainDumpRecording.kt). A null recorder keeps the inert no-op default (the unit-host behaviour).
    // The lambda params are named for the CAPTURE context rather than `today`/`timeZone`, which would shadow
    // this class's own properties of those names (iOS has neither, so its twin can use the plain names).
    private val recordBrainDump: suspend (LocalDate, String) -> Unit = { captureDay, captureZone ->
        recorder?.let { recordBrainDumpTake(it, captureDay, captureZone) }
    }

    /** The shared navigation root the SwiftUI `RootView` renders (Auth ↔ Main → the Destination graph). */
    val root: RootComponent

    init {
        // #270 relaunch sweep: after the roster has hydrated (so processBrainDumpTake sees the Active Account),
        // recover any take whose processing was killed mid-flight — its drafts or salvage land now. Awaiting the
        // memoized [bootstrapped] is what makes this safe: a sweep that raced the load would see a null Active
        // Account and strand the take. On macOS this is the ONLY recovery path — iOS also has a BGProcessingTask
        // backstop, which does not exist on this target (see BrainDumpRecording.kt for why).
        bootstrapScope.launch {
            runCatching { bootstrapped.await() }
            sweepPendingBrainDumps(appComponent, currentLocaleTag(), fileTranscriber, timeZone.id)
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
            // The Brain dump overlay's foreclosed-mic arm (#368 Tranche 5b). This is a SEPARATE seam from
            // `onOpenOsAppSettings` above, and leaving it at its `{}` default compiles silently — the button
            // is simply inert. That matters more here than on iOS: macOS TCC never re-prompts once a person
            // denies the mic, so `Phase.PermissionPermanentlyDenied` is genuinely reachable on a Mac (the
            // Swift mapping treats `.denied`/`.restricted` as permanent), and this button is then the only
            // route out. Deep-link straight to the Microphone list rather than the generic Privacy pane.
            // (iOS's own `DefernoRoot` never passes this parameter either — this is a macOS improvement,
            // not a port. Worth filing back against iOS.)
            onOpenDictationPermissionSettings = { openExternalUrl(MACOS_MICROPHONE_PANE_URL) },
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
            // Agent inference-engine choice + entitlement gate (#150): threaded from the AppScope graph. On macOS
            // the catalog offers Apple Foundation Models (on-device, ungated, the default) plus the cloud relay
            // as a disabled premium row — no Koog klib here, so a cloud selection routes to NotConfigured.
            inferenceEngineCatalog = appComponent.inferenceEngineCatalog,
            // The two device-local brain-dump [[App setting]]s Settings → Storage surfaces (#368 G5).
            // Both default to an IN-MEMORY stub inside DefaultRootComponent, so without threading them the
            // toggles would write to a throwaway that dies with the process while the NSUserDefaults-backed
            // DI binding silently held a different value — two sources of truth disagreeing. Since #368
            // Tranche 5 that would be a live bug, not a latent one: the capture pipeline reads the SAME
            // bindings (`keepRecordings` / `notifications` in BrainDumpPipeline), so threading them here is
            // what keeps the Settings toggles and the pipeline one source of truth.
            // (iOS omits `keepBrainDumpRecordingsPreference` from its own call — a pre-existing iOS defect,
            // not a template to copy.)
            keepBrainDumpRecordingsPreference = appComponent.keepBrainDumpRecordingsPreference,
            brainDumpNotificationPreference = appComponent.brainDumpNotificationPreference,
            // Brain dump's record→Inbox seam (#368 Tranche 5, ADR-0037): records the mic to a durable WAV and
            // hands the take to the shared pipeline on Stop. Real drafts when the Mac is macOS 26 + Apple
            // Intelligence (the injected file transcriber + the routed on-device engine); otherwise the take
            // salvages to the Inbox — never wasted.
            recordBrainDump = recordBrainDump,
            // The AppScope connectivity monitor (#158): the outbox driver flushes on the
            // offline→online edge and skips passes while known-offline.
            connectivity = appComponent.connectivity,
            // The server-mediated Assistant (ADR-0040, #282): the AppScope request/response client gates the
            // entitled-only Destination and drives availability / enable+consent / apply / conversations. The
            // SSE turn stream rides the injected Swift URLSession transport (wrapped as [assistantStream]
            // above); a unit host with no transport leaves the graceful NONE.
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
     * The Swift `UNUserNotificationCenterDelegate` (`BrainDumpNotificationDelegate` in `DefernoApp.swift`)
     * calls this on a `didReceive` whose category is [BRAIN_DUMP_NOTIFICATION_CATEGORY];
     * [RootComponent.openInbox] switches the shell to the Inbox now, or defers if the Auth shell is up.
     */
    fun forwardOpenInbox() = root.openInbox()

    /**
     * The macOS recorder seam (#368 Tranche 5, ADR-0037): start the mic, suspend until either the overlay's
     * Stop cancels this job **or** the mic engine fails to open, then — under [NonCancellable] so a closing
     * overlay never half-writes — finalize the WAV and, on a real Stop, launch the shared pipeline on the
     * app-lifetime [bootstrapScope] (so processing outlives the overlay). A mic-open failure rethrows, so the
     * shared `DefaultBrainDumpComponent` flips to its gentle Failed state (Android/iOS parity — a take is
     * never silently lost), and the empty WAV is dropped.
     *
     * Threading: this seam runs on the Decompose component context, which is [Dispatchers.Main] (the root's
     * default, threaded through the overlay scope) — so [NativeAudioRecorder.stop]'s synchronous Swift
     * teardown runs **on the main thread**. The Swift `Thread.isMainThread` guard (which runs the teardown
     * inline rather than `DispatchQueue.main.sync`-ing onto the queue it is already on) is what keeps it
     * deadlock-free — it is load-bearing, not defensive, and a hard contract on `MacBrainDumpRecorder`, which
     * must reproduce it exactly as iOS's `BrainDumpRecorder.swift` does. The heavy pipeline work runs off-main
     * on [bootstrapScope] ([Dispatchers.Default]). [createdAt] is the take's single instant — the retained
     * recording's key — captured here at the recorder boundary (the host's job; the pipeline stays clock-free).
     */
    private suspend fun recordBrainDumpTake(
        recorder: NativeAudioRecorder,
        captureDay: LocalDate,
        captureZone: String,
    ) {
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
                        // sweep can't also grab it; if the claim is lost (a sweep already took it), this
                        // in-process run stands down.
                        val claimed = claimPendingTake(wavPath) ?: return@launch
                        processBrainDumpTake(
                            appComponent = appComponent,
                            wavPath = claimed,
                            locale = currentLocaleTag(),
                            transcriber = fileTranscriber,
                            today = captureDay,
                            timeZone = captureZone,
                            createdAt = createdAt,
                        )
                    }
                }
            }
        }
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

        // …and this lands directly on its Microphone list — where a person whose mic grant is foreclosed has
        // to go to re-enable Deferno (#368 Tranche 5b). Worth the second constant: the generic pane above
        // leaves them hunting through a dozen categories at the exact moment the app has told them what to do.
        const val MACOS_MICROPHONE_PANE_URL =
            "x-apple.systempreferences:com.apple.preference.security?Privacy_Microphone"
    }

    /**
     * Idempotent dev-PAT seeding (the macOS analogue of `DefernoApplication.seedDevAccounts`): the two
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
