package com.circuitstitch.deferno.core.data

import com.circuitstitch.deferno.core.data.outbox.OutboxProcessor
import com.circuitstitch.deferno.core.data.outbox.OutboxRequestSender
import com.circuitstitch.deferno.core.data.outbox.OutboxStore
import com.circuitstitch.deferno.core.data.outbox.SqlDelightOutboxStore
import com.circuitstitch.deferno.core.data.plan.OfflinePlanRepository
import com.circuitstitch.deferno.core.data.plan.OutboxPlanWriter
import com.circuitstitch.deferno.core.data.plan.PlanLocalStore
import com.circuitstitch.deferno.core.data.plan.PlanRemoteSource
import com.circuitstitch.deferno.core.data.plan.PlanRepository
import com.circuitstitch.deferno.core.data.plan.PlanWriter
import com.circuitstitch.deferno.core.data.plan.SqlDelightPlanLocalStore
import com.circuitstitch.deferno.core.data.settings.OfflineSettingsRepository
import com.circuitstitch.deferno.core.data.settings.OutboxSettingsWriter
import com.circuitstitch.deferno.core.data.settings.SettingsLocalStore
import com.circuitstitch.deferno.core.data.settings.SettingsRemoteSource
import com.circuitstitch.deferno.core.data.settings.SettingsRepository
import com.circuitstitch.deferno.core.data.settings.SettingsWriter
import com.circuitstitch.deferno.core.data.settings.SqlDelightSettingsLocalStore
import com.circuitstitch.deferno.core.data.task.OfflineTaskRepository
import com.circuitstitch.deferno.core.data.task.OutboxTaskWriter
import com.circuitstitch.deferno.core.data.task.SqlDelightTaskLocalStore
import com.circuitstitch.deferno.core.data.task.TaskLocalStore
import com.circuitstitch.deferno.core.data.task.TaskRemoteSource
import com.circuitstitch.deferno.core.data.task.TaskRepository
import com.circuitstitch.deferno.core.data.task.TaskWriter
import com.circuitstitch.deferno.core.database.sql.DefernoDatabase
import com.circuitstitch.deferno.core.scopes.AccountScope
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * The per-Account data layer (AccountScope, ADR-0001/0002/0014): the local SQLDelight stores over the
 * Account's [DefernoDatabase], the offline-first repositories + writers the UI drives, and the outbox
 * replay engine. The whole subtree is torn down + rebuilt on an Active-Account switch (the
 * [AccountScope] graph is disposed), so Account B's graph can never observe Account A's rows.
 *
 * The remote sources + outbox sender it reads are AppScope (the one shared client follows the Active
 * Account by re-reading the token per request — ADR-0014). Several impls carry default-arg
 * constructors (the stores' dispatcher, the writers' clock, the processor's max-attempts/backoff), so
 * these are `@Provides` modules, not `@Inject` constructors.
 */
@ContributesTo(AccountScope::class)
interface AccountDataBindings {

    @Provides
    @SingleIn(AccountScope::class)
    fun taskLocalStore(db: DefernoDatabase): TaskLocalStore = SqlDelightTaskLocalStore(db)

    @Provides
    @SingleIn(AccountScope::class)
    fun planLocalStore(db: DefernoDatabase): PlanLocalStore = SqlDelightPlanLocalStore(db)

    @Provides
    @SingleIn(AccountScope::class)
    fun settingsLocalStore(db: DefernoDatabase): SettingsLocalStore = SqlDelightSettingsLocalStore(db)

    @Provides
    @SingleIn(AccountScope::class)
    fun outboxStore(db: DefernoDatabase): OutboxStore = SqlDelightOutboxStore(db)

    @Provides
    @SingleIn(AccountScope::class)
    fun taskRepository(
        localStore: TaskLocalStore,
        remoteSource: TaskRemoteSource,
    ): TaskRepository = OfflineTaskRepository(localStore, remoteSource)

    @Provides
    @SingleIn(AccountScope::class)
    fun planRepository(
        planStore: PlanLocalStore,
        remoteSource: PlanRemoteSource,
        taskStore: TaskLocalStore,
    ): PlanRepository = OfflinePlanRepository(planStore, remoteSource, taskStore)

    @Provides
    @SingleIn(AccountScope::class)
    fun taskWriter(localStore: TaskLocalStore, outbox: OutboxStore): TaskWriter =
        OutboxTaskWriter(localStore, outbox)

    @Provides
    @SingleIn(AccountScope::class)
    fun planWriter(planStore: PlanLocalStore, outbox: OutboxStore): PlanWriter =
        OutboxPlanWriter(planStore, outbox)

    @Provides
    @SingleIn(AccountScope::class)
    fun settingsRepository(
        localStore: SettingsLocalStore,
        remoteSource: SettingsRemoteSource,
    ): SettingsRepository = OfflineSettingsRepository(localStore, remoteSource)

    @Provides
    @SingleIn(AccountScope::class)
    fun settingsWriter(localStore: SettingsLocalStore, outbox: OutboxStore): SettingsWriter =
        OutboxSettingsWriter(localStore, outbox)

    /**
     * The outbox replay engine (#23). Its reconcile closure — run once after a successful flush —
     * re-pulls the Task snapshot to LWW-merge server truth over the optimistic local state. Plan
     * ordering reconciles per-day via the UI's `refreshPlan` (the processor is Account-scoped and has
     * no "current day" to reconcile), so the closure stays a task refresh here.
     */
    @Provides
    @SingleIn(AccountScope::class)
    fun outboxProcessor(
        store: OutboxStore,
        sender: OutboxRequestSender,
        taskRepository: TaskRepository,
    ): OutboxProcessor = OutboxProcessor(
        store = store,
        sender = sender,
        reconcile = { taskRepository.refresh() },
    )
}
