package com.circuitstitch.deferno.core.data

import com.circuitstitch.deferno.core.data.account.AccountBearerTokenProvider
import com.circuitstitch.deferno.core.data.account.AccountContext
import com.circuitstitch.deferno.core.data.account.AccountDataStore
import com.circuitstitch.deferno.core.data.account.AccountManager
import com.circuitstitch.deferno.core.data.account.AccountRegistry
import com.circuitstitch.deferno.core.data.account.DefaultAccountManager
import com.circuitstitch.deferno.core.data.account.DefaultReauthCoordinator
import com.circuitstitch.deferno.core.data.account.ReauthRequester
import com.circuitstitch.deferno.core.data.account.ReauthRequests
import com.circuitstitch.deferno.core.data.auth.AuthRedirectInbox
import com.circuitstitch.deferno.core.data.auth.AuthRemoteSource
import com.circuitstitch.deferno.core.data.auth.AuthRepository
import com.circuitstitch.deferno.core.data.auth.BrowserAuthenticator
import com.circuitstitch.deferno.core.data.auth.DefaultAuthRepository
import com.circuitstitch.deferno.core.data.auth.DefaultSignInService
import com.circuitstitch.deferno.core.data.auth.DeviceName
import com.circuitstitch.deferno.core.data.auth.InMemoryOAuthClientStore
import com.circuitstitch.deferno.core.data.auth.KtorAuthRemoteSource
import com.circuitstitch.deferno.core.data.auth.KtorNativeAuthRemoteSource
import com.circuitstitch.deferno.core.data.auth.NativeAuthRemoteSource
import com.circuitstitch.deferno.core.data.auth.OAuthClientStore
import com.circuitstitch.deferno.core.data.auth.SignInService
import com.circuitstitch.deferno.core.data.attachment.StorageProviderCatalog
import com.circuitstitch.deferno.core.data.attachment.StorageProviderPreference
import com.circuitstitch.deferno.core.data.calendar.CalendarRemoteSource
import com.circuitstitch.deferno.core.data.calendar.KtorCalendarRemoteSource
import com.circuitstitch.deferno.core.data.create.ItemRemoteSource
import com.circuitstitch.deferno.core.data.create.KtorItemRemoteSource
import com.circuitstitch.deferno.core.data.feedback.FeedbackRepository
import com.circuitstitch.deferno.core.data.feedback.KtorFeedbackRepository
import com.circuitstitch.deferno.core.data.outbox.KtorOutboxRequestSender
import com.circuitstitch.deferno.core.data.outbox.OutboxRequestSender
import com.circuitstitch.deferno.core.data.plan.KtorPlanRemoteSource
import com.circuitstitch.deferno.core.data.plan.PlanRemoteSource
import com.circuitstitch.deferno.core.data.settings.KtorSettingsRemoteSource
import com.circuitstitch.deferno.core.data.settings.SettingsRemoteSource
import com.circuitstitch.deferno.core.data.task.KtorTaskDetailRepository
import com.circuitstitch.deferno.core.data.item.ItemSnapshotSource
import com.circuitstitch.deferno.core.data.item.KtorItemSnapshotSource
import com.circuitstitch.deferno.core.data.task.KtorTaskRemoteSource
import com.circuitstitch.deferno.core.data.task.TaskDetailRepository
import com.circuitstitch.deferno.core.data.task.TaskRemoteSource
import com.circuitstitch.deferno.core.network.BearerTokenProvider
import com.circuitstitch.deferno.core.network.DefernoEnvironment
import com.circuitstitch.deferno.core.network.UploadHttpClient
import com.circuitstitch.deferno.core.scopes.AppScope
import com.circuitstitch.deferno.core.secure.SecretVault
import io.ktor.client.HttpClient
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * The platform-neutral half of the AppScope spine (ADR-0008/0014): the cross-Account identity +
 * network infrastructure that every scene shares and that survives a fast Account switch untouched.
 * The per-platform vault / registry / data-store actuals are contributed from each target's own
 * `*DataBindings` module; the per-Account data layer (DB + repositories + outbox) lives one scope
 * down ([AccountComponent]).
 *
 * Several impls have default-arg constructors or are factory-built, so these are `@Provides` modules
 * rather than `@Inject` constructors (kotlin-inject default-arg resolution is fiddly — ADR-0014).
 */
@ContributesTo(AppScope::class)
interface DataBindings {

    /**
     * The multi-account authority ([DefaultAccountManager]), a process-singleton. Bound both as the
     * mutating [AccountManager] and — re-exposed from the *same* instance — as the read-only
     * [AccountContext] the token provider / repositories resolve the Active Account through, so a
     * `switchTo` re-points every collaborator at once.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun accountManager(
        registry: AccountRegistry,
        vault: SecretVault,
        dataStore: AccountDataStore,
        // Lazy breaks the cycle AuthRemoteSource → HttpClient → BearerTokenProvider → AccountContext
        // (= this manager); the manager only touches it for the sign-out token revoke (ADR-0026).
        authRemoteSource: Lazy<AuthRemoteSource>,
    ): AccountManager = DefaultAccountManager(registry, vault, dataStore, authRemoteSource)

    @Provides
    fun accountContext(manager: AccountManager): AccountContext = manager

    /**
     * The re-auth coordinator (#20), one buffered [kotlinx.coroutines.flow.SharedFlow] per process.
     * Bound as a single singleton and re-exposed as both the producer [ReauthRequester] and the
     * observable [ReauthRequests] — never two instances, or the emit side and the listen side would
     * back different flows.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun reauthCoordinator(): DefaultReauthCoordinator = DefaultReauthCoordinator()

    @Provides
    fun reauthRequester(coordinator: DefaultReauthCoordinator): ReauthRequester = coordinator

    @Provides
    fun reauthRequests(coordinator: DefaultReauthCoordinator): ReauthRequests = coordinator

    /**
     * The [BearerTokenProvider] (core:network port) — resolves the Active Account's PAT fresh per
     * request from the [AccountContext] + [SecretVault] (ADR-0012). The shared HttpClient consumes it.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun bearerTokenProvider(
        accountContext: AccountContext,
        vault: SecretVault,
    ): BearerTokenProvider = AccountBearerTokenProvider(accountContext, vault)

    /** The `/auth/me` identity repository (#20), routing a 401 to re-auth for the Active Account. */
    @Provides
    @SingleIn(AppScope::class)
    fun authRepository(
        remoteSource: AuthRemoteSource,
        accountContext: AccountContext,
        reauth: ReauthRequester,
    ): AuthRepository = DefaultAuthRepository(remoteSource, accountContext, reauth)

    /**
     * The sign-in service (#15, ADR-0012/0023/0026): the browser OAuth + PKCE flow ([BrowserAuthenticator]
     * + [NativeAuthRemoteSource], minting a per-device PAT) and the dev paste fallback, both converging on
     * [AccountManager]. AppScope — pre-Account, like the remote sources it composes, and the convergence
     * seam the Auth shell drives. [browserAuthenticator] + [deviceName] are per-platform bindings,
     * contributed from each target's `*DataBindings` module.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun signInService(
        remoteSource: AuthRemoteSource,
        accountManager: AccountManager,
        nativeAuth: NativeAuthRemoteSource,
        browserAuthenticator: BrowserAuthenticator,
        clientStore: OAuthClientStore,
        deviceName: DeviceName,
    ): SignInService =
        DefaultSignInService(remoteSource, accountManager, nativeAuth, browserAuthenticator, clientStore, deviceName)

    /**
     * The one-shot redirect rendezvous (ADR-0026, #137): the host OS layer publishes the captured
     * redirect URI, the in-flight mobile [BrowserAuthenticator] awaits it. One AppScope instance so
     * both ends meet; bound here (not per-platform) since the type is platform-neutral — desktop's
     * loopback authenticator simply never consumes it.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun authRedirectInbox(): AuthRedirectInbox = AuthRedirectInbox()

    /** The native browser-OAuth remote source (register / authorize-url / token-exchange, ADR-0026). */
    @Provides
    @SingleIn(AppScope::class)
    fun nativeAuthRemoteSource(
        client: HttpClient,
        environment: DefernoEnvironment,
    ): NativeAuthRemoteSource = KtorNativeAuthRemoteSource(client, environment)

    /** Per-process cache of the registered OAuth client_id (a persistent store is a follow-up, ADR-0026). */
    @Provides
    @SingleIn(AppScope::class)
    fun oauthClientStore(): OAuthClientStore = InMemoryOAuthClientStore()

    // The Ktor remote sources over the one shared client — AppScope, since the client is (ADR-0014).
    @Provides
    @SingleIn(AppScope::class)
    fun taskRemoteSource(client: HttpClient): TaskRemoteSource = KtorTaskRemoteSource(client)

    // The item-wide cold-snapshot source (`GET /items`, ADR-0034 #226) — the successor to the legacy
    // task-only `TaskRemoteSource.fetchAll`. AppScope, like the other Ktor sources.
    @Provides
    @SingleIn(AppScope::class)
    fun itemSnapshotSource(client: HttpClient): ItemSnapshotSource = KtorItemSnapshotSource(client)

    /**
     * The Task detail's online-only comments + attachments source over the shared authed [client]
     * (read-on-open) plus the bare [uploadClient] for the presigned attachment PUTs (no base/no auth —
     * an extra Authorization header would break S3 SigV4, same as the feedback upload above).
     */
    @Provides
    @SingleIn(AppScope::class)
    fun taskDetailRepository(
        client: HttpClient,
        uploadClient: UploadHttpClient,
    ): TaskDetailRepository = KtorTaskDetailRepository(client, uploadClient)

    @Provides
    @SingleIn(AppScope::class)
    fun planRemoteSource(client: HttpClient): PlanRemoteSource = KtorPlanRemoteSource(client)

    /** The windowed Calendar feed source (#74) over the shared client — `GET /tasks/calendar`. */
    @Provides
    @SingleIn(AppScope::class)
    fun calendarRemoteSource(client: HttpClient): CalendarRemoteSource = KtorCalendarRemoteSource(client)

    @Provides
    @SingleIn(AppScope::class)
    fun settingsRemoteSource(client: HttpClient): SettingsRemoteSource = KtorSettingsRemoteSource(client)

    @Provides
    @SingleIn(AppScope::class)
    fun authRemoteSource(client: HttpClient): AuthRemoteSource = KtorAuthRemoteSource(client)

    /** The online-only create/convert remote source (#71, ADR-0016) over the shared client. */
    @Provides
    @SingleIn(AppScope::class)
    fun itemRemoteSource(client: HttpClient): ItemRemoteSource = KtorItemRemoteSource(client)

    /** The outbox's network sender (#23), replaying queued requests over the shared client. */
    @Provides
    @SingleIn(AppScope::class)
    fun outboxRequestSender(client: HttpClient): OutboxRequestSender = KtorOutboxRequestSender(client)

    /**
     * In-app Help → Feedback (#375): presign → byte-exact PUT → submit. Rides the shared authed
     * [client] (the bearer plugin attaches the Active Account's PAT) plus the bare [uploadClient] for
     * the presigned-URL PUTs (no base/no auth — an extra Authorization header would break S3 SigV4).
     */
    @Provides
    @SingleIn(AppScope::class)
    fun feedbackRepository(
        client: HttpClient,
        uploadClient: UploadHttpClient,
    ): FeedbackRepository = KtorFeedbackRepository(client, uploadClient)

    /**
     * The device-local storage-provider choice the Settings Destination renders (#210) — the providers a
     * *user/task* attachment can be stored in (on-device default, backend, cloud coming-later) over the
     * platform-backed [StorageProviderPreference]. An [[App setting]]: device-local, never synced, never
     * per-Account (AppScope, ADR-0014) — the direct analogue of the speech/inference engine catalogs.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun storageProviderCatalog(preference: StorageProviderPreference): StorageProviderCatalog =
        StorageProviderCatalog(preference)
}
