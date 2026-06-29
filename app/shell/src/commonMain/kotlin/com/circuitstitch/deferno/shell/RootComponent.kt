package com.circuitstitch.deferno.shell

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.replaceAll
import com.arkivanov.decompose.value.Value
import com.circuitstitch.deferno.core.common.asStateFlow
import com.circuitstitch.deferno.core.common.componentScope
import com.circuitstitch.deferno.core.data.account.AccountManager
import com.circuitstitch.deferno.core.data.account.DefaultReauthCoordinator
import com.circuitstitch.deferno.core.data.account.ReauthRequests
import com.circuitstitch.deferno.core.data.assistant.AssistantClient
import com.circuitstitch.deferno.core.data.auth.AuthRepository
import com.circuitstitch.deferno.core.data.auth.SignInService
import com.circuitstitch.deferno.core.data.connectivity.AssumeOnlineConnectivity
import com.circuitstitch.deferno.core.data.connectivity.Connectivity
import com.circuitstitch.deferno.core.data.feedback.FeedbackRepository
import com.circuitstitch.deferno.core.domain.command.CommandResult
import com.circuitstitch.deferno.core.domain.command.CreateItem
import com.circuitstitch.deferno.core.network.dto.CreateTaskPayload
import com.circuitstitch.deferno.core.model.Account
import com.circuitstitch.deferno.core.model.AccountId
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.UserSettings
import com.circuitstitch.deferno.core.agent.InferenceEngineCatalog
import com.circuitstitch.deferno.core.data.attachment.StorageProviderCatalog
import com.circuitstitch.deferno.core.data.braindump.BrainDumpNotificationPreference
import com.circuitstitch.deferno.core.data.braindump.InMemoryBrainDumpNotificationPreference
import com.circuitstitch.deferno.core.data.braindump.InMemoryKeepBrainDumpRecordingsPreference
import com.circuitstitch.deferno.core.data.braindump.KeepBrainDumpRecordingsPreference
import com.circuitstitch.deferno.core.data.item.InMemoryShakeToUndoPreference
import com.circuitstitch.deferno.core.data.item.ShakeToUndoPreference
import com.circuitstitch.deferno.core.speech.EmptySpeechEngineCatalog
import com.circuitstitch.deferno.core.speech.SpeechEngineCatalog
import com.circuitstitch.deferno.core.speech.SpeechToText
import com.circuitstitch.deferno.core.speech.UnavailableSpeechToText
import com.circuitstitch.deferno.feature.assistant.AssistantStream
import com.circuitstitch.deferno.feature.signin.DefaultSignInComponent
import com.circuitstitch.deferno.feature.tasks.SearchTasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The per-scene navigation root (ADR-0013 / ADR-0008 / ADR-0014): a two-state [[Shell]] selected by
 * the **Active Account**. It shows the **Auth shell** (pre-[[Account]]) when there is none and the
 * **Main shell** (post-Account) when one is active, swapping reactively as the [AccountManager]'s
 * `activeAccount` changes — first sign-in, sign-out, and fast user switching all flow through it.
 *
 * The Main shell is keyed by the Active Account's id ([Config.Main]), so switching A→B re-keys the
 * child, which rebuilds the per-Account data layer ([AccountSession], from a fresh
 * [com.circuitstitch.deferno.core.di.AccountComponent]) for the new Account — the per-Account
 * isolation boundary enforced structurally by the scope graph (ADR-0002/0014). Switching needs no
 * re-auth: every Account's PAT already lives in the AppScope vault (ADR-0012).
 *
 * One root per scene/window (ADR-0008 G2/G3). It is Compose-free — the View renders it.
 */
interface RootComponent {
    /** The active shell (exactly one of [Child.Auth] / [Child.Main]). */
    val stack: Value<ChildStack<*, Child>>

    /**
     * The active shell child, mirrored as a [StateFlow] for the SwiftUI Views to observe via SKIE
     * (which bridges `StateFlow` + the sealed [Child] → a Swift enum, but not Decompose's [Value]).
     * The Compose/Android side keeps observing [stack] directly.
     */
    val activeChild: StateFlow<Child>

    /**
     * The **live** per-Account session backing the active Main shell, or `null` on the Auth shell
     * (signed out). Detached macOS detail windows (#196, ADR-0033) build their detail stack over THIS
     * session's repositories/editors — reusing the shell's one SQLite driver so a window edit and the
     * main shell observe the same query Flow (cross-window live sync). `null` drives "detail windows
     * unavailable when signed out". Not a navigation child — just the seam the window opener reads.
     */
    val activeAccountSession: AccountSession?

    /**
     * The Active Account's settings, surfaced **app-wide** so the View's `DefernoTheme` re-themes
     * live when Appearance changes (#72). It is derived at the root (above the per-Account boundary):
     * it mirrors the active Main session's settings `Flow`, and falls back to [UserSettings.Default]
     * in the no-account / Auth-shell state, re-pointing on an account switch (ADR-0002/0014).
     */
    val themeSettings: StateFlow<UserSettings>

    /** Route Android system-back to the active shell. `false` → the host lets the platform exit. */
    fun onBackClicked(): Boolean

    /**
     * Open the [Destination.Inbox] in the Main shell — the target of the Brain dump worker's "drafts
     * ready" notification (ADR-0027/#150, Stage 4). When no Account is active yet (a cold start into the
     * Auth shell), it is remembered and applied to the Main shell once one becomes active.
     */
    fun openInbox()

    /**
     * The read/navigation OS intent (ADR-0036, #248): foreground the **Plan** Destination — the target
     * of Google Assistant's "open my plan" App Action. No task content is spoken; it is a
     * state-preserving lateral switch like a nav tap. Signed out, it is a no-op beyond the Auth shell
     * already shown — Plan is the post-sign-in home Destination, so no deferral is needed (unlike
     * [openInbox], whose target is not the home).
     */
    fun openPlan()

    /**
     * The capture OS intent (ADR-0036, #249): create a one-off [com.circuitstitch.deferno.core.model.ItemKind.Task]
     * titled [title] verbatim — the slot Google Assistant's "add … to Deferno" App Action fills, with no
     * triage and no inference (App Actions BIIs pre-classify; the four-kind behavioral triage is the App
     * Functions slice, #250). Commits through the Active Account's offline-first create path (optimistic
     * apply + outbox enqueue), so the confirmation is the honest "queued, it'll sync" — never a false
     * server confirmation. Signed out, the dictated [title] is remembered and created once an Account
     * becomes active (the Auth shell opens meanwhile — never a silent drop). A blank [title] is ignored.
     */
    fun addTask(title: String)

    sealed interface Child {
        class Auth(val component: AuthShellComponent) : Child
        class Main(val component: MainShellComponent) : Child
    }
}

class DefaultRootComponent(
    componentContext: ComponentContext,
    private val accountManager: AccountManager,
    private val authRepository: AuthRepository,
    private val accountSession: (Account) -> AccountSession,
    /** The v1 sign-in seam (#15, ADR-0023): the Auth shell's [DefaultSignInComponent] validates a pasted PAT through it. */
    private val signInService: SignInService,
    private val today: LocalDate,
    private val timeZone: String,
    /** Deep-link to the OS app-settings screen (Settings → App Permissions, #72). Handled by the host. */
    private val onOpenOsAppSettings: () -> Unit = {},
    /** Open the web app's data export/import surface (Settings → Data & Privacy, #72 AC #3). Host-handled. */
    private val onOpenDataExportImport: () -> Unit = {},
    /**
     * The in-app Help → Feedback service (#375) the Settings → Feedback overlay submits through. AppScope
     * (the authed client carries the Active Account's PAT). Defaulted to a no-op so tests build without it.
     */
    private val feedbackRepository: FeedbackRepository = NoopFeedbackRepository,
    /** Open the Active Account's Zitadel console URL, if any (Settings → Security & 2FA stub, #72). */
    private val onOpenConsoleUrl: (String) -> Unit = {},
    // Dictation (#92, ADR-0018): the on-device SpeechToText (the AppScope selector) the New surface's mic
    // drives, and the device locale it recognizes. Threaded down to the Main shell. Defaulted to the
    // Unavailable floor / English so tests build without them.
    private val speechToText: SpeechToText = UnavailableSpeechToText,
    private val locale: String = "en-US",
    // The device-local speech-engine [[App setting]] (#93, ADR-0018): the AppScope catalog the Settings
    // Destination reads. Threaded down to the Main shell. Defaulted to the inert [EmptySpeechEngineCatalog]
    // (only "Automatic" → the row hides) so tests build without it; production passes the AppComponent's.
    private val speechEngineCatalog: SpeechEngineCatalog = EmptySpeechEngineCatalog,
    // The Agent inference-engine choice + entitlement gate (#150, ADR-0027): the AppScope catalog the
    // Settings Destination reads. Threaded down to the Main shell. Defaulted to the inert
    // [InferenceEngineCatalog.Inert] (no engine → the Agent row hides) so tests build without it;
    // production passes the AppComponent's.
    private val inferenceEngineCatalog: InferenceEngineCatalog = InferenceEngineCatalog.Inert,
    // The device-local storage-provider choice (#210): the AppScope catalog the Settings Destination reads.
    // Threaded down to the Main shell. Defaulted to the inert [StorageProviderCatalog.Inert] (in-memory
    // selection) so tests build without it; production passes the AppComponent's.
    private val storageProviderCatalog: StorageProviderCatalog = StorageProviderCatalog.Inert,
    // The device-local "keep brain-dump recordings" choice (#211): the AppScope preference the Settings
    // Destination reads. Threaded down to the Main shell. Defaulted to an in-memory (on) preference so tests
    // build without it; production passes the AppComponent's.
    private val keepBrainDumpRecordingsPreference: KeepBrainDumpRecordingsPreference =
        InMemoryKeepBrainDumpRecordingsPreference(),
    // The device-local "Brain dump notifications" opt-in (#266/#271): the AppScope preference the Settings
    // Destination reads. Threaded down to the Main shell. Defaulted to an in-memory (off) preference so tests
    // build without it; production passes the AppComponent's.
    private val brainDumpNotificationPreference: BrainDumpNotificationPreference =
        InMemoryBrainDumpNotificationPreference(),
    // The device-local "shake to undo" choice (#230): the AppScope preference the Tasks tree + Settings read.
    // Threaded down to the Main shell. Defaulted to an in-memory (on) preference so tests build without it;
    // production passes the AppComponent's.
    private val shakeToUndoPreference: ShakeToUndoPreference = InMemoryShakeToUndoPreference(),
    // The Brain dump's record-to-file seam (ADR-0027/#150, Stage 4): records the mic to a WAV and hands it
    // to the background worker on Stop. Threaded down to the Main shell. Android-only (Context + WorkManager);
    // desktop/tests leave it the no-op default — the recorder is inert. The worker (not the shell) now owns
    // the inference engine, so it's no longer threaded through here.
    private val recordBrainDump: suspend (LocalDate, String) -> Unit = { _, _ -> },
    /** The New surface's foreclosed-dictation-permission deep-link (#120). Host-handled, like the rest. */
    private val onOpenDictationPermissionSettings: () -> Unit = {},
    /**
     * The honest "queued, it'll sync" confirmation for an [addTask] capture OS intent (ADR-0036, #249).
     * Invoked with the Task title only after the offline-first create is [CommandResult.Accepted] — the
     * host surfaces it (a toast on Android). Defaulted to a no-op so tests / desktop build without it.
     */
    private val onTaskQueued: (title: String) -> Unit = {},
    /** The outbox driver's clock (#143) — injected so the flush timing is deterministic under test. */
    private val now: () -> Instant = { Clock.System.now() },
    /**
     * How often the outbox driver re-flushes while a session is active (#143) — the cadence that picks
     * up writes made during the session and retries after a transient failure (the per-entry backoff
     * inside the processor still governs when a failed entry is ready again).
     */
    private val outboxFlushPeriod: Duration = 30.seconds,
    /**
     * The AppScope connectivity signal (#158): the offline→online edge triggers an immediate outbox
     * flush, and a flush pass is skipped while known-offline. Defaults to assume-online — the
     * pre-#158 behaviour (no edge ever fires, no pass is ever skipped) — so tests and a platform
     * without a monitor are unchanged; production passes the AppComponent's platform monitor.
     */
    private val connectivity: Connectivity = AssumeOnlineConnectivity(),
    /**
     * The server-mediated Assistant request/response client (#282, ADR-0040) the Main shell gates the
     * Assistant Destination on and builds the chat component over. Threaded down to the Main shell.
     * Defaulted to the inert client (every call Unavailable → the Assistant Destination stays absent) so
     * tests / the Android+desktop hosts build unchanged; only the iOS host passes the AppComponent's real one.
     */
    private val assistantClient: AssistantClient = InertAssistantClient,
    /**
     * The Assistant SSE turn-stream transport (#282, ADR-0040): the iOS host bridges a Swift URLSession SSE
     * transport in; every other host leaves it the graceful no-op NONE. Threaded down to the Main shell.
     */
    private val assistantStream: AssistantStream = AssistantStream.NONE,
    /**
     * The process-wide re-auth signal (#297): its `sessionExpired` flag drives the read-surface
     * "Session expired — sign in again" banner the Main shell renders. Defaulted to a fresh, never-flagged
     * coordinator so tests build unchanged; production passes the AppComponent's instance (the one the
     * shared HttpClient sets on a 401 / clears on the next 2xx).
     */
    private val reauthRequests: ReauthRequests = DefaultReauthCoordinator(),
    coroutineContext: CoroutineContext = Dispatchers.Main,
) : RootComponent, ComponentContext by componentContext {

    private val scope = componentScope(coroutineContext)

    private val _themeSettings = MutableStateFlow(UserSettings.Default)
    override val themeSettings: StateFlow<UserSettings> = _themeSettings.asStateFlow()

    /** The collector mirroring the active session's settings into [_themeSettings]; cancelled on switch. */
    private var themeJob: Job? = null

    /** The active session's outbox driver (#143/#158): flush, settings reconcile, then the periodic loop. */
    private val outboxDriver = OutboxDriver(scope, connectivity, now, outboxFlushPeriod)

    private sealed interface Config {
        data object Auth : Config

        /** The Main shell bound to the Active Account; its id re-keys the child on a switch. */
        data class Main(val accountId: String) : Config
    }

    private val navigation = StackNavigation<Config>()

    /** The foreground Main child's session, used to apply per-Account writes (add-to-plan). */
    private var activeSession: AccountSession? = null

    // The live session, exposed read-only for detached detail windows (#196) — same instance the Main
    // child is built over, so a window's edits ride the shell's one driver (ADR-0033).
    override val activeAccountSession: AccountSession? get() = activeSession

    /** A Brain dump "open the Inbox" deep-link that arrived before a Main shell existed (#150 Stage 4). */
    private var pendingInbox = false

    /** An "add a task" capture OS intent (#249) whose title arrived before a Main shell existed (signed out). */
    private var pendingTaskTitle: String? = null

    override val stack: Value<ChildStack<*, RootComponent.Child>> =
        childStack(
            source = navigation,
            serializer = null,
            initialConfiguration = configFor(accountManager.activeAccount.value),
            key = "ShellStack",
            handleBackButton = false, // back is routed via onBackClicked(), not a stack pop
            childFactory = ::createChild,
        )

    override val activeChild: StateFlow<RootComponent.Child> =
        stack.asStateFlow(scope) { it.active.instance }

    init {
        // Follow the Active Account: swap shells (and re-key the Main child per Account) as it changes.
        // replaceAll retains the child when the target config is unchanged (data-class equality), so a
        // no-op emission doesn't rebuild the current shell.
        scope.launch {
            accountManager.activeAccount.collect { account ->
                val target = configFor(account)
                if (stack.value.active.configuration != target) {
                    navigation.replaceAll(target)
                }
            }
        }
    }

    override fun onBackClicked(): Boolean =
        when (val child = stack.value.active.instance) {
            is RootComponent.Child.Auth -> false // can't go back out of the Auth shell — exit the scene
            is RootComponent.Child.Main -> child.component.onBack()
        }

    override fun openInbox() {
        when (val child = stack.value.active.instance) {
            // A Main shell is up: switch it to the Inbox now (state-preserving, like a nav tap).
            is RootComponent.Child.Main -> child.component.selectDestination(Destination.Inbox)
            // Signed out (cold start into Auth): remember it and apply when the Main shell is built.
            is RootComponent.Child.Auth -> pendingInbox = true
        }
    }

    override fun openPlan() {
        when (val child = stack.value.active.instance) {
            // A Main shell is up: bring Plan to front (state-preserving lateral switch, no content spoken).
            is RootComponent.Child.Main -> child.component.selectDestination(Destination.Plan)
            // Signed out: the Auth shell is already shown, and Plan is the post-sign-in home Destination —
            // so there is nothing to defer (the user lands on Plan once they sign in).
            is RootComponent.Child.Auth -> Unit
        }
    }

    override fun addTask(title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return // a blank slot is ignored (no inference, no empty Task)
        when (val child = stack.value.active.instance) {
            // A Main shell is up: create through the Active Account's offline-first path now.
            is RootComponent.Child.Main -> submitTask(activeSession, trimmed)
            // Signed out: remember the dictated title and create it once an Account becomes active —
            // the Auth shell opens meanwhile, never a silent drop.
            is RootComponent.Child.Auth -> pendingTaskTitle = trimmed
        }
    }

    /**
     * Create a one-off Task titled [title] through [session]'s offline-first create seam (#249) and
     * surface the honest "queued" confirmation. A create is optimistically applied + enqueued, so
     * [CommandResult.Accepted] is the only truthful word — never "saved on the server".
     */
    private fun submitTask(session: AccountSession?, title: String) {
        val active = session ?: return
        scope.launch {
            if (active.create(CreateItem.Payload.Task(CreateTaskPayload(title = title))) is CommandResult.Accepted) {
                onTaskQueued(title)
            }
        }
    }

    private fun createChild(config: Config, childContext: ComponentContext): RootComponent.Child =
        when (config) {
            Config.Auth -> {
                activeSession = null
                // No Active Account → no outbox to drive, no per-Account settings; stop the driver and
                // fall the app-wide theme back to default.
                outboxDriver.stop()
                pointThemeAt(null)
                RootComponent.Child.Auth(
                    DefaultAuthShellComponent(
                        componentContext = childContext,
                        // The paste-PAT sign-in surface (#15, ADR-0023): on a valid token it calls
                        // addAccount, the activeAccount collector above swaps in the Main shell.
                        signIn = { ctx -> DefaultSignInComponent(ctx, signInService, scope.coroutineContext) },
                    ),
                )
            }

            is Config.Main -> {
                // The Active Account this Main child is keyed to. It is in the roster by construction
                // (the config came from an emitted activeAccount), so a miss is a broken invariant.
                val account = accountManager.accounts.value.first { it.id.value == config.accountId }
                val session = accountSession(account)
                activeSession = session
                // Pull this Account's tasks + today's plan into the local cache on open / switch
                // (offline-first: the repositories swallow failures, leaving the cache intact). The
                // Views observe the local DB, so this is what surfaces real data on first open.
                scope.launch { session.taskRepository.refresh() }
                scope.launch { session.planRepository.refreshPlan(today, timeZone) }
                // Drive this Account's outbox (#143): flush the queued offline writes, THEN pull the
                // settings snapshot (sequenced so the reconcile can't pull a server state that predates
                // the just-flushed writes), then keep flushing periodically while the session is active.
                outboxDriver.drive(session)
                // Re-point the app-wide theme `Flow` at THIS Account's settings (#72) — cancels the
                // previous account's collector, so a switch never bleeds the prior account's theme.
                pointThemeAt(session)
                val shell =
                    DefaultMainShellComponent(
                        componentContext = childContext,
                        itemRepository = session.itemRepository,
                        foldStore = session.foldStore,
                        taskRepository = session.taskRepository,
                        planRepository = session.planRepository,
                        authRepository = authRepository,
                        settingsRepository = session.settingsRepository,
                        settingsEditor = session.settingsEditor,
                        account = account,
                        today = today,
                        timeZone = timeZone,
                        // The Calendar Destination's feed source + occurrence-act seam (#74).
                        calendarRepository = session.calendarRepository,
                        occurrenceEditor = session.occurrenceEditor,
                        workingStateEditor = session.workingStateEditor,
                        // The Tasks Item-tree non-Task status seam (#299), routed through this Account's executor.
                        definitionStateEditor = session.definitionStateEditor,
                        // The Tasks Item-tree modal move seam (#228), routed through this Account's executor.
                        moveEditor = session.moveEditor,
                        taskDetailRepository = session.taskDetailRepository,
                        // The Task detail's editable-PROPERTIES write seams (DUE date + LABELS), each
                        // routed through this Account's command executor (Set/ClearTaskDeadline, SetTaskLabels).
                        setDeadline = session.setDeadline,
                        setLabels = session.setLabels,
                        // The detail's destructive Delete seam (kebab → confirm), routed through this
                        // Account's command executor (DeleteTask). The Item-tree command menu (#231) reuses it.
                        deleteTask = session.deleteTask,
                        // The Item-tree command menu's Task-only Pin + plan-toggle writes (#231), routed
                        // through this Account's command executor; add/remove pre-bind today's (date, tz).
                        setPinned = session.setPinned,
                        addToPlan = { session.addToPlan(it, today, timeZone) },
                        removeFromPlan = { session.removeFromPlan(it, today, timeZone) },
                        // On-device attachments (#211): the detail shows + plays this Account's retained recordings.
                        onDeviceAttachments = session.onDeviceAttachments,
                        // On-device storage usage (#211): the Settings > Storage read-out over those recordings.
                        onDeviceStorageUsage = session.onDeviceStorageUsage,
                        // On-device Backup export (#313, ADR-0041): the Settings export action builds the zip.
                        buildBackupZip = session::buildBackupZip,
                        // On-device Backup import (#314, ADR-0041): the Settings import action restores the zip.
                        importBackup = session::importBackup,
                        searchTasks = SearchTasks.of(session.taskRepository),
                        accounts = accountManager.accounts,
                        activeAccount = accountManager.activeAccount,
                        onSwitchAccount = ::switchAccount,
                        output = ::onMainOutput,
                        coroutineContext = scope.coroutineContext,
                        // The online-only create seam (#71, ADR-0016): the New overlay dispatches the
                        // CreateItem command through this Account's command executor.
                        create = session::create,
                        // The in-app Help → Feedback service (#375) the Feedback overlay submits through.
                        feedbackRepository = feedbackRepository,
                        // Dictation (#92, ADR-0018): the AppScope speech engine + device locale the New
                        // surface's mic drives, threaded through to DefaultNewComponent.
                        speechToText = speechToText,
                        locale = locale,
                        // The device-local speech-engine App setting (#93) the Settings Destination reads.
                        speechEngineCatalog = speechEngineCatalog,
                        // The Agent inference-engine choice + entitlement gate (#150) the Settings reads.
                        inferenceEngineCatalog = inferenceEngineCatalog,
                        // The device-local storage-provider choice (#210) the Settings Destination reads.
                        storageProviderCatalog = storageProviderCatalog,
                        // The device-local "keep brain-dump recordings" choice (#211) the Settings reads.
                        keepBrainDumpRecordingsPreference = keepBrainDumpRecordingsPreference,
                        // The device-local "Brain dump notifications" opt-in (#266/#271) the Settings reads.
                        brainDumpNotificationPreference = brainDumpNotificationPreference,
                        shakeToUndoPreference = shakeToUndoPreference,
                        // The Brain dump's record-to-file seam (ADR-0027/#150, Stage 4) the voice_chat overlay drives.
                        recordBrainDump = recordBrainDump,
                        // The foreclosed-permission deep-link (#120), threaded to the New overlay.
                        onOpenDictationPermissionSettings = onOpenDictationPermissionSettings,
                        // The Inbox Destination's draft seam (ADR-0015 Inbox amendment): this Account's
                        // local Brain dump drafts — observed for the list + nav badge, written on accept
                        // (mark Accepted) / dismiss / undo.
                        observeBrainDumpDrafts = session::observeBrainDumpDrafts,
                        upsertBrainDumpDraft = session::upsertBrainDumpDraft,
                        // The Inbox accept's recording-attach (#211): attach the retained WAV to the new Task.
                        attachBrainDumpRecording = session::attachBrainDumpRecording,
                        // The Activity Destination's reverse-chron ledger feed (#260).
                        observeActivity = session::observeActivity,
                        // The server-mediated Assistant Destination (#282, ADR-0040): the request client (gates
                        // the Destination on availability + applies proposals), this Account's Conversation
                        // cache, the SSE turn-stream transport, and the connectivity signal the composer reads.
                        assistantClient = assistantClient,
                        conversationStore = session.conversationStore,
                        assistantStream = assistantStream,
                        connectivity = connectivity,
                        // The read-surface session-expiry banner flag (#297), AppScope (set on a 401).
                        sessionExpired = reauthRequests.sessionExpired,
                    )
                // A pending Inbox deep-link (the Brain dump notification tapped on a cold start into the
                // Auth shell, ADR-0027 Stage 4): now that the Main shell exists, apply it, then consume it.
                if (pendingInbox) {
                    pendingInbox = false
                    shell.selectDestination(Destination.Inbox)
                }
                // A pending "add a task" capture OS intent dictated while signed out (#249): now that an
                // Account is active, create it through this session, then consume it.
                pendingTaskTitle?.let { title ->
                    pendingTaskTitle = null
                    submitTask(session, title)
                }
                RootComponent.Child.Main(shell)
            }
        }

    /**
     * Re-point the app-wide theme [StateFlow] at [session]'s settings (or reset to the default when
     * `null`). Cancels the prior collector first so an account switch / sign-out can never leave a
     * stale or cross-account theme (account isolation, ADR-0002/0014).
     */
    private fun pointThemeAt(session: AccountSession?) {
        themeJob?.cancel()
        if (session == null) {
            _themeSettings.value = UserSettings.Default
            themeJob = null
            return
        }
        themeJob = scope.launch {
            session.settingsRepository.observeSettings().collect { _themeSettings.value = it }
        }
    }

    private fun onMainOutput(output: MainShellComponent.Output) {
        when (output) {
            // Add-to-plan applies through the Active Account's offline write path (optimistic apply +
            // outbox enqueue), not a host mirror — the real per-Account command (ADR-0001/0007/0014).
            is MainShellComponent.Output.AddToPlanRequested -> onAddToPlan(output.id)
            // Sign out crosses the Account-isolation boundary (ADR-0002), so it lands at the root.
            MainShellComponent.Output.SignOutRequested -> onSignOut()
            // OS / browser deep-links cross the app boundary (an Android Intent), so the host issues
            // them (#72). App permissions opens the OS settings; the console URL is resolved from the
            // Active Account's /auth/me identity (only admins carry one — a no-op when absent).
            MainShellComponent.Output.OpenOsAppSettings -> onOpenOsAppSettings()
            // Export/import has no client endpoint at v0.1 — open the web app instead, the same
            // ACTION_VIEW plumbing the console URL uses (AC #3, ADR-0015). Feedback is now in-app (#375):
            // the shell handles it via the Feedback overlay, so it no longer reaches the host.
            MainShellComponent.Output.OpenDataExportImport -> onOpenDataExportImport()
            MainShellComponent.Output.OpenConsoleUrl -> onOpenConsoleUrl()
            // "View profile" is a lateral switch the shell already performed; nothing for the host.
            MainShellComponent.Output.OpenProfile -> Unit
        }
    }

    private fun onOpenConsoleUrl() {
        scope.launch {
            val url = when (val me = authRepository.loadMe()) {
                is com.circuitstitch.deferno.core.data.auth.MeResult.Authenticated -> me.user.consoleUrl
                else -> null
            }
            if (url != null) onOpenConsoleUrl(url)
        }
    }

    private fun onSignOut() {
        val id = accountManager.activeAccount.value?.id ?: return
        // Secure-wipe the Active Account locally (encrypted DB + DB key + bearer token, in the crash-safe
        // order, ADR-0009) via removeAccount, which re-points activeAccount to another Account or null.
        // The collector above then swaps the shell back to the Auth shell when it hits null, or re-keys
        // Main for the remaining sibling — the "return to Auth, or to another signed-in Account" rule.
        // Server-side PAT revocation (DELETE /auth/tokens/{id}) is deferred to in-app sign-in (#15),
        // where the token id is captured at mint time (a dev-pasted opaque PAT carries no id to revoke).
        scope.launch { accountManager.removeAccount(id) }
    }

    private fun onAddToPlan(taskId: TaskId) {
        val session = activeSession ?: return
        scope.launch { session.addToPlan(taskId, today, timeZone) }
    }

    private fun switchAccount(id: AccountId) {
        // Re-points the Active Account; the activeAccount collector above re-keys the Main child for
        // the new Account. No re-auth — the PAT is already vaulted (ADR-0002/0012).
        scope.launch { accountManager.switchTo(id) }
    }

    private fun configFor(account: Account?): Config =
        if (account != null) Config.Main(account.id.value) else Config.Auth
}
