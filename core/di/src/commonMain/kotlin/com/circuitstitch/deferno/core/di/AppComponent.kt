package com.circuitstitch.deferno.core.di

import com.circuitstitch.deferno.core.data.account.AccountContext
import com.circuitstitch.deferno.core.data.account.AccountManager
import com.circuitstitch.deferno.core.data.account.ReauthRequester
import com.circuitstitch.deferno.core.data.account.ReauthRequests
import com.circuitstitch.deferno.core.data.auth.AuthRedirectInbox
import com.circuitstitch.deferno.core.data.auth.AuthRepository
import com.circuitstitch.deferno.core.data.auth.SignInService
import com.circuitstitch.deferno.core.data.assistant.AssistantClient
import com.circuitstitch.deferno.core.data.attachment.AttachmentBytesStore
import com.circuitstitch.deferno.core.data.attachment.StorageProviderCatalog
import com.circuitstitch.deferno.core.data.braindump.BrainDumpNotificationPreference
import com.circuitstitch.deferno.core.data.braindump.BrainDumpSalvageCounter
import com.circuitstitch.deferno.core.data.braindump.KeepBrainDumpRecordingsPreference
import com.circuitstitch.deferno.core.data.calendar.CalendarRemoteSource
import com.circuitstitch.deferno.core.data.connectivity.Connectivity
import com.circuitstitch.deferno.core.data.create.ItemRemoteSource
import com.circuitstitch.deferno.core.data.feedback.FeedbackRepository
import com.circuitstitch.deferno.core.data.item.ItemFoldStore
import com.circuitstitch.deferno.core.data.item.ItemSnapshotSource
import com.circuitstitch.deferno.core.data.item.ShakeToUndoPreference
import com.circuitstitch.deferno.core.data.outbox.OutboxRequestSender
import com.circuitstitch.deferno.core.data.plan.PlanRemoteSource
import com.circuitstitch.deferno.core.data.settings.SettingsRemoteSource
import com.circuitstitch.deferno.core.data.task.TaskDetailRepository
import com.circuitstitch.deferno.core.data.task.TaskRemoteSource
import com.circuitstitch.deferno.core.agent.InferenceEngine
import com.circuitstitch.deferno.core.agent.InferenceEngineCatalog
import com.circuitstitch.deferno.core.database.AccountDatabaseFactory
import com.circuitstitch.deferno.core.network.BearerTokenProvider
import com.circuitstitch.deferno.core.network.DefernoEnvironment
import com.circuitstitch.deferno.core.scopes.AppScope
import com.circuitstitch.deferno.core.speech.DictationPermissionSettings
import com.circuitstitch.deferno.core.speech.SpeechEngineCatalog
import com.circuitstitch.deferno.core.speech.SpeechToText
import com.circuitstitch.deferno.core.scopes.PlatformContext
import io.ktor.client.HttpClient
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.MergeComponent
import software.amazon.lastmile.kotlin.inject.anvil.MergeComponent.CreateComponent
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Process-global root scope ([AppScope], ADR-0008/0014). Its bindings are process-singletons shared
 * across every window/scene — the cross-Account infrastructure: the network client + token provider,
 * the [AccountManager]/secure vault, the [DatabaseKeyStore], the [AccountRegistry]/[AccountDataStore],
 * and the reauth coordinator. The per-Account data layer lives one scope down ([AccountComponent]).
 *
 * Created from common code (KMP) with two host-supplied values, both re-exposed as graph bindings:
 *  - [platform] — the opaque per-platform handle ([PlatformContext]) AppScope bindings extract their
 *    host dependencies from (Android `Context`, desktop databases dir, nothing on iOS). The platform
 *    `@Provides` in this module unwrap it (e.g. the Android `context` binding below).
 *  - [environment] — which Deferno backend this process talks to (ADR-0005). Threaded from the app so
 *    a debug build can select Staging and a release build Production without the shared graph baking
 *    one in.
 */
data class AppScaffold(val value: String)

@ContributesTo(AppScope::class)
interface AppScaffoldBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun provideAppScaffold(): AppScaffold = AppScaffold("app")
}

@MergeComponent(AppScope::class)
@SingleIn(AppScope::class)
abstract class AppComponent(
    @get:Provides val platform: PlatformContext,
    @get:Provides val environment: DefernoEnvironment,
) {
    abstract val appScaffold: AppScaffold

    /**
     * The process-global multi-account authority (ADR-0002/0008). The app holds this to `load()` the
     * persisted roster at startup, add/switch/remove Accounts, and observe the Active Account it binds
     * each [AccountComponent] over.
     */
    abstract val accountManager: AccountManager

    /**
     * The `/auth/me` identity repository (#20). Exposed here both for the app's auth surface and to
     * anchor the network half of the spine in the merged graph: resolving it validates the whole
     * HttpClient → BearerTokenProvider → AccountContext chain at compile time.
     */
    abstract val authRepository: AuthRepository

    /**
     * The v1 sign-in service (#15, ADR-0023): validate a pasted PAT against `/auth/me` and establish
     * the Account. The Auth shell drives it; exposing it here also compile-validates its
     * AuthRemoteSource + AccountManager dependencies in the merged graph.
     */
    abstract val signInService: SignInService

    /**
     * The one-shot OAuth redirect rendezvous (ADR-0026, #137). The host OS layer resolves it here to
     * publish the redirect URI the system browser returned with — Android's `MainActivity` from its
     * custom-scheme intent, the iOS Swift URL handler via `DefernoRoot.forwardAuthRedirect` — and the
     * in-flight mobile `BrowserAuthenticator` (same AppScope instance) awaits it. Exposed instead of
     * reached as a static `object` so the hand-off flows through DI like everything else.
     */
    abstract val authRedirectInbox: AuthRedirectInbox

    /**
     * On-device speech-to-text (#92, ADR-0018): the [SpeechToText] selector over every registered engine
     * (the `Set<SpeechToText>` multibinding) honouring the device-local engine preference. An AppScope
     * **device capability** — identity-independent, like the secure vault (ADR-0014), not per-Account.
     * The **New** surface's [[Dictation]] drives it; resolving it here also compile-validates the
     * multibinding (and its per-platform engine + preference contributions) on every target.
     */
    abstract val speechToText: SpeechToText

    /**
     * The device-local **speech-engine choice** read model the Settings Destination renders (#93,
     * ADR-0018): the registered engines + their availability + the device-local preference, over the same
     * AppScope multibinding the [speechToText] selector reads. An [[App setting]] — device-local, never
     * synced, never per-Account — so switching Accounts never changes it. Surfaced here for the Settings
     * surface and to compile-validate its binding on every target.
     */
    abstract val speechEngineCatalog: SpeechEngineCatalog

    /**
     * The Agent's device-local **inference-engine choice** + cloud gate the Settings Destination renders
     * and every Agent surface consults (#150, ADR-0027): the engine selection [[App setting]] (Off /
     * on-device / Deferno-cloud) over the per-Account relay entitlement (relay base URL from the
     * [environment]). An AppScope **device capability** — the selection is device-local (never synced), the
     * entitlement is enforced server-side, not by a graph scope. On-device engines are ungated; only the
     * cloud relay is entitlement-gated. Surfaced here for the Settings surface and to compile-validate the
     * binding on every target (macOS included, where the inference floor is NotConfigured).
     */
    abstract val inferenceEngineCatalog: InferenceEngineCatalog

    /**
     * The device-local **storage-provider choice** the Settings Destination renders (#210): the providers a
     * *user/task* attachment can be stored in (on-device default, Deferno backend, user-owned cloud
     * coming-later) over the device-local preference. An AppScope [[App setting]] — device-local, never
     * synced, never per-Account. Surfaced here for the Settings surface and to compile-validate the binding
     * on every target. Feedback attachments are unaffected — they always use the backend (a fixed provider).
     */
    abstract val storageProviderCatalog: StorageProviderCatalog

    /**
     * The device-local **"keep brain-dump recordings"** choice the Settings Destination renders (#211):
     * whether a brain-dump's source recording is retained as an on-device Task attachment (#210) on accept.
     * An AppScope [[App setting]] — device-local, never synced. Read by the Android brain-dump worker (which
     * gates retention on it) and the Settings surface; surfaced here to compile-validate the binding on
     * every target. Defaults to on.
     */
    abstract val keepBrainDumpRecordingsPreference: KeepBrainDumpRecordingsPreference

    /**
     * The device-local monotonic counter behind a **[[Salvage draft]]**'s `Brain dump #n` title (#265, ADR-0037).
     * An AppScope [[App setting]] — device-local, never synced, survives relaunch. Read by the shared brain-dump
     * pipeline when it writes a salvage draft; surfaced here to compile-validate the binding on every target.
     */
    abstract val brainDumpSalvageCounter: BrainDumpSalvageCounter

    /**
     * The device-local **"Brain dump notifications"** opt-in (#266, ADR-0037): whether a completion notification
     * fires when a Brain dump finishes. An AppScope [[App setting]] — device-local, never synced, **default off**.
     * Read by the shared brain-dump pipeline to gate the notifier; surfaced here to compile-validate the binding
     * on every target.
     */
    abstract val brainDumpNotificationPreference: BrainDumpNotificationPreference

    /**
     * The device-local **"shake to undo"** choice the Tasks tree + Settings render (ADR-0034 decision 8,
     * #230): whether a phone shake raises the "Undo [operation]?" confirm that reverts the last Move. An
     * AppScope [[App setting]] — device-local, never synced. Defaults to on; shake is never the only undo
     * path (the snackbar + menu remain). Surfaced here to compile-validate the binding on every target.
     */
    abstract val shakeToUndoPreference: ShakeToUndoPreference

    /**
     * The device-local **Item-tree fold-override store** (ADR-0034, #227): the per-device expand/collapse
     * overrides the Tasks Item tree and the detail subtask outline share, keyed by item id. An AppScope
     * [[App setting]] — device-local, never synced, never per-Account. Re-exposed here so the child
     * AccountScope can consume it (its [AccountComponent.foldStore] resolves through this parent accessor)
     * and to compile-validate the binding on every target.
     */
    abstract val foldStore: ItemFoldStore

    /**
     * The on-device attachment **byte store** (#210): the AppScope filesystem store the per-Account
     * [com.circuitstitch.deferno.core.data.attachment.LocalAttachmentRepository] writes/reads attachment bytes
     * through. Re-exposed here so the child AccountScope can consume it (kotlin-inject-anvil does not
     * auto-propagate a parent's contributed @Provides — see the re-exposed bindings below).
     */
    abstract val attachmentBytesStore: AttachmentBytesStore

    /**
     * The one shared Deferno [HttpClient] (ADR-0005/0014). Re-exposed so the child AccountScope can
     * consume it (same reason as [attachmentBytesStore]) — the per-Account `SecurityRemoteSource`
     * wraps this shared client while keeping its step-up cookie Account-scoped.
     */
    abstract val httpClient: HttpClient

    /**
     * The read-only Active-Account resolver + the per-Account re-auth raiser (ADR-0002). Re-exposed
     * (like [attachmentBytesStore]) so the child AccountScope's `SecurityRepository` can route a PAT
     * 401 to re-auth exactly as the AppScope `DefaultAuthRepository` does.
     */
    abstract val accountContext: AccountContext
    abstract val reauthRequester: ReauthRequester

    /**
     * The app-facing **inference engine** the propose-only Agent runs through (ADR-0027): the AppScope
     * `RoutingInferenceEngine` that, per call, dispatches to whichever engine the [inferenceEngineCatalog]
     * selection names (the on-device shacl floor on Android, the cloud relay when entitled) — or answers
     * `NotConfigured` when the Agent is Off. The Brain dump surface wraps it in an `Extractor`; surfaced
     * here so the shell can drive extraction without reaching into the graph, and to compile-validate the
     * binding on every target (macOS binds `NotConfigured`, where there is no on-device floor).
     */
    abstract val inferenceEngine: InferenceEngine

    /**
     * Where the OS lets the person flip a foreclosed [[Dictation]] permission (#120): the desktop New
     * surface's "Open System Settings" affordance resolves through it (live Sidecar introspection →
     * the blocked capability's macOS Privacy pane); the View-owned-permission hosts (Android, iOS)
     * bind a null deep-link. An [[App setting]]-shaped device capability — AppScope, never per-Account.
     * Surfaced here for the desktop shell and to compile-validate the binding on every target.
     */
    abstract val dictationPermissionSettings: DictationPermissionSettings

    /**
     * In-app Help → Feedback (#375): presign attachments → byte-exact PUT → submit the comment. An
     * AppScope service (it rides the shared authed client whose bearer plugin attaches the Active
     * Account's PAT per request), surfaced here so the Android feedback screen can drive it without
     * per-Account wiring.
     */
    abstract val feedbackRepository: FeedbackRepository

    /**
     * The server-mediated [[Assistant]] request/response client (#282, ADR-0040): availability /
     * enablement / apply / conversations over the shared authed client (the SSE turn stream is a separate
     * per-platform seam). An AppScope service like [feedbackRepository] — the bearer plugin attaches the
     * Active Account's PAT per request. Surfaced here so the shell can gate the Assistant Destination on
     * availability and build the chat component, and to compile-validate the binding on every target.
     */
    abstract val assistantClient: AssistantClient

    /**
     * The [BearerTokenProvider] port (#282, ADR-0040): the Active Account's PAT, resolved fresh per call.
     * Surfaced here so the iOS SSE turn-stream transport — which streams over a raw Swift `URLSession`
     * outside the shared Ktor client (NSURLSession SSE buffering, ADR-0040) — can attach the same bearer
     * the request/response client does. Never logged (ADR-0009).
     */
    abstract val bearerTokenProvider: BearerTokenProvider

    // --- Bindings re-exposed for the child AccountScope (ADR-0014) ---
    // kotlin-inject-anvil does not auto-propagate a parent's contributed @Provides into a child merge;
    // a child (AccountComponent, via @Component val app) can only consume parent bindings the parent
    // exposes as accessors. These AppScope bindings feed the per-Account data layer one scope down:
    // the offline-first repositories pull through the shared remote sources, the outbox replays through
    // the shared sender, and the per-Account DB is opened by the platform AccountDatabaseFactory (which
    // closes over the host Context / databases dir / key provider inside AppScope). They are plumbing,
    // not part of the app-facing surface — the app uses AccountComponent.taskRepository et al.
    abstract val taskRemoteSource: TaskRemoteSource
    // The item-wide cold-snapshot source (`GET /items`, ADR-0034 #226): the AccountScope ItemSync pulls
    // the windowed snapshot through it, reconciling every kind into its store on refresh.
    abstract val itemSnapshotSource: ItemSnapshotSource
    // Online-only Task detail extras (comments + attachments); the child AccountComponent re-exposes it.
    abstract val taskDetailRepository: TaskDetailRepository
    abstract val planRemoteSource: PlanRemoteSource
    // The windowed Calendar feed source (#74): the OfflineCalendarRepository (AccountScope) refreshes through it.
    abstract val calendarRemoteSource: CalendarRemoteSource
    abstract val settingsRemoteSource: SettingsRemoteSource
    abstract val outboxRequestSender: OutboxRequestSender
    abstract val accountDatabaseFactory: AccountDatabaseFactory
    // The online-only create flow (#71, ADR-0016): the CreateWriter (AccountScope) gates on this
    // process-global connectivity + POSTs through this shared remote source, so both are re-exposed
    // here for the child AccountScope to consume. The connectivity monitor is also what the shell's
    // outbox driver observes for the reconnect-triggered flush (#158).
    abstract val itemRemoteSource: ItemRemoteSource
    abstract val connectivity: Connectivity

    // The process-wide re-auth signal (#20/#297): the shell folds its `sessionExpired` flag into the
    // read-surface "Session expired — sign in again" banner. AppScope, set/cleared by the shared client.
    abstract val reauthRequests: ReauthRequests
}

// Creation from common code (KMP); anvil generates the per-platform `actual`. One
// @CreateComponent per file — anvil names the generated actual after the containing file.
@CreateComponent
expect fun createAppComponent(
    platform: PlatformContext,
    environment: DefernoEnvironment,
): AppComponent
