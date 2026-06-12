package com.circuitstitch.deferno.core.data

import com.circuitstitch.deferno.core.data.calendar.CalendarLocalStore
import com.circuitstitch.deferno.core.data.calendar.CalendarRemoteSource
import com.circuitstitch.deferno.core.data.calendar.CalendarRepository
import com.circuitstitch.deferno.core.data.calendar.OccurrenceWriter
import com.circuitstitch.deferno.core.data.calendar.OfflineCalendarRepository
import com.circuitstitch.deferno.core.data.calendar.OutboxOccurrenceWriter
import com.circuitstitch.deferno.core.data.calendar.LocalStoreSeriesKindSource
import com.circuitstitch.deferno.core.data.calendar.SeriesKindSource
import com.circuitstitch.deferno.core.data.calendar.SqlDelightCalendarLocalStore
import com.circuitstitch.deferno.core.data.chore.ChoreLocalStore
import com.circuitstitch.deferno.core.data.chore.SqlDelightChoreLocalStore
import com.circuitstitch.deferno.core.data.connectivity.Connectivity
import com.circuitstitch.deferno.core.data.create.CreateWriter
import com.circuitstitch.deferno.core.data.create.ItemRemoteSource
import com.circuitstitch.deferno.core.data.create.OnlineCreateWriter
import com.circuitstitch.deferno.core.data.event.EventLocalStore
import com.circuitstitch.deferno.core.data.event.SqlDelightEventLocalStore
import com.circuitstitch.deferno.core.data.habit.HabitLocalStore
import com.circuitstitch.deferno.core.data.habit.SqlDelightHabitLocalStore
import com.circuitstitch.deferno.core.data.occurrence.OccurrenceLocalStore
import com.circuitstitch.deferno.core.data.occurrence.SqlDelightOccurrenceLocalStore
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

    // The recurring-kind local stores (#71): the per-Account SQLDelight caches a created Habit/Chore/
    // Event seeds into, so the row joins the observe Flow exactly as a Task does (ADR-0001).
    @Provides
    @SingleIn(AccountScope::class)
    fun habitLocalStore(db: DefernoDatabase): HabitLocalStore = SqlDelightHabitLocalStore(db)

    @Provides
    @SingleIn(AccountScope::class)
    fun choreLocalStore(db: DefernoDatabase): ChoreLocalStore = SqlDelightChoreLocalStore(db)

    @Provides
    @SingleIn(AccountScope::class)
    fun eventLocalStore(db: DefernoDatabase): EventLocalStore = SqlDelightEventLocalStore(db)

    // The Occurrence (firing-level) local store (#71, AC #4): the per-Account SQLDelight cache an
    // occurrence read from the kind-scoped endpoint seeds into, so it joins the observe Flow (ADR-0001).
    @Provides
    @SingleIn(AccountScope::class)
    fun occurrenceLocalStore(db: DefernoDatabase): OccurrenceLocalStore = SqlDelightOccurrenceLocalStore(db)

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

    // The Calendar feed cache + series->kind index (#74): the local source of truth the month grid +
    // day agenda observe; a window refresh full-replaces the span and a write applies optimistically here.
    @Provides
    @SingleIn(AccountScope::class)
    fun calendarLocalStore(db: DefernoDatabase): CalendarLocalStore = SqlDelightCalendarLocalStore(db)

    // Snapshots the locally-known Habit/Chore/Event definitions into the series_id -> kind index (#74),
    // so a kind-less feed firing resolves the recurring kind its occurrence write needs.
    @Provides
    @SingleIn(AccountScope::class)
    fun seriesKindSource(
        habits: HabitLocalStore,
        chores: ChoreLocalStore,
        events: EventLocalStore,
    ): SeriesKindSource = LocalStoreSeriesKindSource(habits, chores, events)

    @Provides
    @SingleIn(AccountScope::class)
    fun calendarRepository(
        localStore: CalendarLocalStore,
        remoteSource: CalendarRemoteSource,
        seriesKindSource: SeriesKindSource,
    ): CalendarRepository = OfflineCalendarRepository(localStore, remoteSource, seriesKindSource)

    // The Occurrence (firing) write seam (#74): optimistic CalendarItem apply + outbox enqueue. Offline-
    // first like the Task writer (these target an existing firing — not online-only like create).
    @Provides
    @SingleIn(AccountScope::class)
    fun occurrenceWriter(calendarStore: CalendarLocalStore, outbox: OutboxStore): OccurrenceWriter =
        OutboxOccurrenceWriter(calendarStore, outbox)

    /**
     * The online-only create + convert writer (#71, ADR-0016). It bypasses the outbox entirely (a
     * create is never enqueued — no server idempotency key in v0.1) and seeds the server-assigned-id
     * row into the matching local store. The remote source + connectivity it reads are AppScope (the
     * shared client follows the Active Account per request — ADR-0014); the local stores are this scope.
     */
    @Provides
    @SingleIn(AccountScope::class)
    fun createWriter(
        connectivity: Connectivity,
        remoteSource: ItemRemoteSource,
        taskStore: TaskLocalStore,
        habitStore: HabitLocalStore,
        choreStore: ChoreLocalStore,
        eventStore: EventLocalStore,
    ): CreateWriter = OnlineCreateWriter(connectivity, remoteSource, taskStore, habitStore, choreStore, eventStore)

    @Provides
    @SingleIn(AccountScope::class)
    fun taskWriter(localStore: TaskLocalStore, outbox: OutboxStore): TaskWriter =
        OutboxTaskWriter(localStore, outbox)

    @Provides
    @SingleIn(AccountScope::class)
    fun planWriter(planStore: PlanLocalStore, outbox: OutboxStore): PlanWriter =
        OutboxPlanWriter(planStore, outbox)

    // The settings reconcile reads the outbox so a refresh can't clobber an un-synced optimistic
    // settings change (#143) — a pending settings mutation is newer than the server snapshot (LWW).
    @Provides
    @SingleIn(AccountScope::class)
    fun settingsRepository(
        localStore: SettingsLocalStore,
        remoteSource: SettingsRemoteSource,
        outbox: OutboxStore,
    ): SettingsRepository = OfflineSettingsRepository(localStore, remoteSource, outbox)

    @Provides
    @SingleIn(AccountScope::class)
    fun settingsWriter(localStore: SettingsLocalStore, outbox: OutboxStore): SettingsWriter =
        OutboxSettingsWriter(localStore, outbox)

    /**
     * The outbox replay engine (#23), driven by the app on session activation + periodically while a
     * session is active (#143 — the shell's RootComponent owns the triggers). Its reconcile closure —
     * run once after a successful flush — re-pulls the Task, Calendar, and Settings snapshots to
     * LWW-merge server truth over the optimistic local state. Plan ordering reconciles per-day via
     * the UI's `refreshPlan` (the processor is Account-scoped and has no "current day" to reconcile).
     */
    @Provides
    @SingleIn(AccountScope::class)
    fun outboxProcessor(
        store: OutboxStore,
        sender: OutboxRequestSender,
        taskRepository: TaskRepository,
        calendarRepository: CalendarRepository,
        settingsRepository: SettingsRepository,
    ): OutboxProcessor = OutboxProcessor(
        store = store,
        sender = sender,
        // After a successful flush, LWW-reconcile the Task snapshot, re-pull the last Calendar window
        // (so an occurrence mark/reschedule converges on server truth, #74), and re-pull the settings
        // bag (so a flushed settings PATCH converges, #143 — its refresh skips the upsert if more
        // settings mutations are still queued). Plan ordering reconciles per-day via the UI's
        // refreshPlan (the processor has no "current day").
        reconcile = {
            taskRepository.refresh()
            calendarRepository.reconcile()
            settingsRepository.refresh()
        },
    )
}
