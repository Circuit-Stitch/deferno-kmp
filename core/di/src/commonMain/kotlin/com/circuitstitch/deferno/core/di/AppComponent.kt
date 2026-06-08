package com.circuitstitch.deferno.core.di

import com.circuitstitch.deferno.core.data.account.AccountManager
import com.circuitstitch.deferno.core.data.auth.AuthRepository
import com.circuitstitch.deferno.core.data.outbox.OutboxRequestSender
import com.circuitstitch.deferno.core.data.plan.PlanRemoteSource
import com.circuitstitch.deferno.core.data.settings.SettingsRemoteSource
import com.circuitstitch.deferno.core.data.task.TaskRemoteSource
import com.circuitstitch.deferno.core.database.AccountDatabaseFactory
import com.circuitstitch.deferno.core.network.DefernoEnvironment
import com.circuitstitch.deferno.core.scopes.AppScope
import com.circuitstitch.deferno.core.scopes.PlatformContext
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

    // --- Bindings re-exposed for the child AccountScope (ADR-0014) ---
    // kotlin-inject-anvil does not auto-propagate a parent's contributed @Provides into a child merge;
    // a child (AccountComponent, via @Component val app) can only consume parent bindings the parent
    // exposes as accessors. These AppScope bindings feed the per-Account data layer one scope down:
    // the offline-first repositories pull through the shared remote sources, the outbox replays through
    // the shared sender, and the per-Account DB is opened by the platform AccountDatabaseFactory (which
    // closes over the host Context / databases dir / key provider inside AppScope). They are plumbing,
    // not part of the app-facing surface — the app uses AccountComponent.taskRepository et al.
    abstract val taskRemoteSource: TaskRemoteSource
    abstract val planRemoteSource: PlanRemoteSource
    abstract val settingsRemoteSource: SettingsRemoteSource
    abstract val outboxRequestSender: OutboxRequestSender
    abstract val accountDatabaseFactory: AccountDatabaseFactory
}

// Creation from common code (KMP); anvil generates the per-platform `actual`. One
// @CreateComponent per file — anvil names the generated actual after the containing file.
@CreateComponent
expect fun createAppComponent(
    platform: PlatformContext,
    environment: DefernoEnvironment,
): AppComponent
