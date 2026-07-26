package com.circuitstitch.deferno.core.data.create

import com.circuitstitch.deferno.core.data.activity.ActivityActionKind
import com.circuitstitch.deferno.core.data.activity.ActivityEntry
import com.circuitstitch.deferno.core.data.activity.ActivityLedgerStore
import com.circuitstitch.deferno.core.data.activity.ActivitySource
import com.circuitstitch.deferno.core.data.activity.ActivityStamp
import com.circuitstitch.deferno.core.data.activity.RemoteActivityEntry
import com.circuitstitch.deferno.core.data.chore.ChoreLocalStore
import com.circuitstitch.deferno.core.data.connectivity.Connectivity
import com.circuitstitch.deferno.core.data.event.EventLocalStore
import com.circuitstitch.deferno.core.data.habit.HabitLocalStore
import com.circuitstitch.deferno.core.data.outbox.OutboxRequest
import com.circuitstitch.deferno.core.model.Chore
import com.circuitstitch.deferno.core.model.ChoreId
import com.circuitstitch.deferno.core.model.Event
import com.circuitstitch.deferno.core.model.EventId
import com.circuitstitch.deferno.core.model.Habit
import com.circuitstitch.deferno.core.model.HabitId
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.network.ApiError
import com.circuitstitch.deferno.core.network.ApiResult
import com.circuitstitch.deferno.core.network.dto.ConvertItemPayload
import com.circuitstitch.deferno.core.network.dto.CreateChorePayload
import com.circuitstitch.deferno.core.network.dto.CreateEventPayload
import com.circuitstitch.deferno.core.network.dto.CreateHabitPayload
import com.circuitstitch.deferno.core.network.dto.CreateTaskPayload
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlin.time.Instant

/** A [Connectivity] whose online/offline state is set by the test (`online.value = …`). */
class FakeConnectivity(online: Boolean = true) : Connectivity {
    override val online = MutableStateFlow(online)
}

/**
 * An [ItemRemoteSource] whose responses the test configures, recording the payloads it received so a
 * test can assert "offline enqueued/called nothing" (ADR-0016). Each create returns its configured
 * [ApiResult]; unset defaults to a transport failure.
 */
class FakeItemRemoteSource : ItemRemoteSource {
    var taskResult: ApiResult<Task> = ApiResult.Failure(ApiError.Transport(RuntimeException("unset")))
    var habitResult: ApiResult<Habit> = ApiResult.Failure(ApiError.Transport(RuntimeException("unset")))
    var choreResult: ApiResult<Chore> = ApiResult.Failure(ApiError.Transport(RuntimeException("unset")))
    var eventResult: ApiResult<Event> = ApiResult.Failure(ApiError.Transport(RuntimeException("unset")))
    var convertResult: ApiResult<ConvertedItem> = ApiResult.Failure(ApiError.Transport(RuntimeException("unset")))

    val calls = mutableListOf<String>()

    override suspend fun createTask(payload: CreateTaskPayload): ApiResult<Task> {
        calls += "createTask"; return taskResult
    }

    override suspend fun createHabit(payload: CreateHabitPayload): ApiResult<Habit> {
        calls += "createHabit"; return habitResult
    }

    override suspend fun createChore(payload: CreateChorePayload): ApiResult<Chore> {
        calls += "createChore"; return choreResult
    }

    override suspend fun createEvent(payload: CreateEventPayload): ApiResult<Event> {
        calls += "createEvent"; return eventResult
    }

    override suspend fun convert(
        id: String,
        payload: ConvertItemPayload,
        stamp: ActivityStamp?,
    ): ApiResult<ConvertedItem> {
        calls += "convert:$id"; return convertResult
    }
}

/**
 * In-memory [HabitLocalStore] backed by a re-emitting [MutableStateFlow] (mirrors FakeTaskLocalStore).
 * [transaction] stages mutations on a working copy and publishes once at commit, like SQLDelight — so
 * the `/items` reconcile (#226) re-emits the observed list exactly once.
 */
class FakeHabitLocalStore(initial: Map<HabitId, Habit> = emptyMap()) : HabitLocalStore {
    private val rows = MutableStateFlow(initial)
    private var inTransaction = false
    private var staged: MutableMap<HabitId, Habit> = mutableMapOf()
    val all: Map<HabitId, Habit> get() = rows.value
    override fun observeActive(): Flow<List<Habit>> = rows.map { it.values.filterNot { h -> h.isDeleted }.sortedBy { h -> h.sequence } }
    override fun observe(id: HabitId): Flow<Habit?> = rows.map { it[id] }
    override suspend fun allIds(): Set<HabitId> = current().keys
    override suspend fun get(id: HabitId): Habit? = current()[id]
    override suspend fun upsert(habit: Habit) = mutate { it[habit.id] = habit }
    override suspend fun delete(id: HabitId) = mutate { it.remove(id) }
    override suspend fun transaction(block: suspend (HabitLocalStore) -> Unit) = runStaged { block(this) }
    private fun current(): Map<HabitId, Habit> = if (inTransaction) staged else rows.value
    private fun mutate(edit: (MutableMap<HabitId, Habit>) -> Unit) {
        if (inTransaction) edit(staged) else rows.value = rows.value.toMutableMap().also(edit)
    }
    private suspend fun runStaged(block: suspend () -> Unit) {
        inTransaction = true; staged = rows.value.toMutableMap()
        try { block(); rows.value = staged.toMap() } finally { inTransaction = false }
    }
}

/** In-memory [ChoreLocalStore] (transaction stages + commits once, like SQLDelight; #226). */
class FakeChoreLocalStore(initial: Map<ChoreId, Chore> = emptyMap()) : ChoreLocalStore {
    private val rows = MutableStateFlow(initial)
    private var inTransaction = false
    private var staged: MutableMap<ChoreId, Chore> = mutableMapOf()
    val all: Map<ChoreId, Chore> get() = rows.value
    override fun observeActive(): Flow<List<Chore>> = rows.map { it.values.filterNot { c -> c.isDeleted }.sortedBy { c -> c.sequence } }
    override fun observe(id: ChoreId): Flow<Chore?> = rows.map { it[id] }
    override suspend fun allIds(): Set<ChoreId> = current().keys
    override suspend fun get(id: ChoreId): Chore? = current()[id]
    override suspend fun upsert(chore: Chore) = mutate { it[chore.id] = chore }
    override suspend fun delete(id: ChoreId) = mutate { it.remove(id) }
    override suspend fun transaction(block: suspend (ChoreLocalStore) -> Unit) = runStaged { block(this) }
    private fun current(): Map<ChoreId, Chore> = if (inTransaction) staged else rows.value
    private fun mutate(edit: (MutableMap<ChoreId, Chore>) -> Unit) {
        if (inTransaction) edit(staged) else rows.value = rows.value.toMutableMap().also(edit)
    }
    private suspend fun runStaged(block: suspend () -> Unit) {
        inTransaction = true; staged = rows.value.toMutableMap()
        try { block(); rows.value = staged.toMap() } finally { inTransaction = false }
    }
}

/** In-memory [EventLocalStore] (transaction stages + commits once, like SQLDelight; #226). */
class FakeEventLocalStore(initial: Map<EventId, Event> = emptyMap()) : EventLocalStore {
    private val rows = MutableStateFlow(initial)
    private var inTransaction = false
    private var staged: MutableMap<EventId, Event> = mutableMapOf()
    val all: Map<EventId, Event> get() = rows.value
    override fun observeActive(): Flow<List<Event>> = rows.map { it.values.filterNot { e -> e.isDeleted }.sortedBy { e -> e.sequence } }
    override fun observe(id: EventId): Flow<Event?> = rows.map { it[id] }
    override suspend fun allIds(): Set<EventId> = current().keys
    override suspend fun get(id: EventId): Event? = current()[id]
    override suspend fun upsert(event: Event) = mutate { it[event.id] = event }
    override suspend fun delete(id: EventId) = mutate { it.remove(id) }
    override suspend fun transaction(block: suspend (EventLocalStore) -> Unit) = runStaged { block(this) }
    private fun current(): Map<EventId, Event> = if (inTransaction) staged else rows.value
    private fun mutate(edit: (MutableMap<EventId, Event>) -> Unit) {
        if (inTransaction) edit(staged) else rows.value = rows.value.toMutableMap().also(edit)
    }
    private suspend fun runStaged(block: suspend () -> Unit) {
        inTransaction = true; staged = rows.value.toMutableMap()
        try { block(); rows.value = staged.toMap() } finally { inTransaction = false }
    }
}

/**
 * A recording [ActivityLedgerStore] for the create tests. Only [recordLocal] is exercised — convert is
 * the one write here that bypasses the outbox choke-point and so records its own row (#364) — but the
 * whole port is implemented so a new method can't silently go untested by these fixtures.
 */
class FakeActivityLedgerStore : ActivityLedgerStore {
    val recorded = mutableListOf<Triple<ActivitySource, String, ActivityStamp?>>()
    val merged = mutableListOf<RemoteActivityEntry>()
    var cursor: String? = null

    override suspend fun recordLocal(
        source: ActivitySource,
        target: String,
        request: OutboxRequest,
        before: String?,
        now: Instant,
        stamp: ActivityStamp?,
        actionKind: ActivityActionKind?,
    ) {
        recorded += Triple(source, target, stamp)
    }

    override suspend fun upsertRemote(entries: List<RemoteActivityEntry>) {
        merged += entries
    }

    override fun recent(limit: Long): Flow<List<ActivityEntry>> = flowOf(emptyList())

    override suspend fun pruneOlderThan(cutoff: Instant) = Unit

    override suspend fun syncCursor(): String? = cursor

    override suspend fun setSyncCursor(cursor: String?, now: Instant) {
        this.cursor = cursor
    }

    override suspend fun clear() {
        recorded.clear()
        merged.clear()
        cursor = null
    }
}
