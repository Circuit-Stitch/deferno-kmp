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
import com.circuitstitch.deferno.core.data.auth.AuthRemoteSource
import com.circuitstitch.deferno.core.data.auth.AuthRepository
import com.circuitstitch.deferno.core.data.auth.DefaultAuthRepository
import com.circuitstitch.deferno.core.data.auth.KtorAuthRemoteSource
import com.circuitstitch.deferno.core.data.connectivity.AssumeOnlineConnectivity
import com.circuitstitch.deferno.core.data.connectivity.Connectivity
import com.circuitstitch.deferno.core.data.create.ItemRemoteSource
import com.circuitstitch.deferno.core.data.create.KtorItemRemoteSource
import com.circuitstitch.deferno.core.data.outbox.KtorOutboxRequestSender
import com.circuitstitch.deferno.core.data.outbox.OutboxRequestSender
import com.circuitstitch.deferno.core.data.plan.KtorPlanRemoteSource
import com.circuitstitch.deferno.core.data.plan.PlanRemoteSource
import com.circuitstitch.deferno.core.data.settings.KtorSettingsRemoteSource
import com.circuitstitch.deferno.core.data.settings.SettingsRemoteSource
import com.circuitstitch.deferno.core.data.task.KtorTaskRemoteSource
import com.circuitstitch.deferno.core.data.task.TaskRemoteSource
import com.circuitstitch.deferno.core.network.BearerTokenProvider
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
    ): AccountManager = DefaultAccountManager(registry, vault, dataStore)

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

    // The Ktor remote sources over the one shared client — AppScope, since the client is (ADR-0014).
    @Provides
    @SingleIn(AppScope::class)
    fun taskRemoteSource(client: HttpClient): TaskRemoteSource = KtorTaskRemoteSource(client)

    @Provides
    @SingleIn(AppScope::class)
    fun planRemoteSource(client: HttpClient): PlanRemoteSource = KtorPlanRemoteSource(client)

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

    /**
     * The connectivity seam the online-only create gate consults (#71, ADR-0016). The v1 default
     * assumes online and lets the create call's own transport failure be the signal; a platform-aware
     * actual is a non-breaking follow-up. AppScope — connectivity is a process concern, not per-Account.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun connectivity(): Connectivity = AssumeOnlineConnectivity()

    /** The outbox's network sender (#23), replaying queued requests over the shared client. */
    @Provides
    @SingleIn(AppScope::class)
    fun outboxRequestSender(client: HttpClient): OutboxRequestSender = KtorOutboxRequestSender(client)
}
