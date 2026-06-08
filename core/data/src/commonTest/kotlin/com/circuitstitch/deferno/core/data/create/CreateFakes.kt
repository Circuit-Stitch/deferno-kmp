package com.circuitstitch.deferno.core.data.create

import com.circuitstitch.deferno.core.data.chore.ChoreLocalStore
import com.circuitstitch.deferno.core.data.connectivity.Connectivity
import com.circuitstitch.deferno.core.data.event.EventLocalStore
import com.circuitstitch.deferno.core.data.habit.HabitLocalStore
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
import kotlinx.coroutines.flow.map

/** A [Connectivity] whose online/offline state is set by the test. */
class FakeConnectivity(var online: Boolean = true) : Connectivity {
    override suspend fun isOnline(): Boolean = online
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

    override suspend fun convert(id: String, payload: ConvertItemPayload): ApiResult<ConvertedItem> {
        calls += "convert:$id"; return convertResult
    }
}

/** In-memory [HabitLocalStore] backed by a re-emitting [MutableStateFlow] (mirrors FakeTaskLocalStore). */
class FakeHabitLocalStore(initial: Map<HabitId, Habit> = emptyMap()) : HabitLocalStore {
    private val rows = MutableStateFlow(initial)
    val all: Map<HabitId, Habit> get() = rows.value
    override fun observeActive(): Flow<List<Habit>> = rows.map { it.values.filterNot { h -> h.isDeleted }.sortedBy { h -> h.sequence } }
    override fun observe(id: HabitId): Flow<Habit?> = rows.map { it[id] }
    override suspend fun get(id: HabitId): Habit? = rows.value[id]
    override suspend fun upsert(habit: Habit) { rows.value = rows.value.toMutableMap().also { it[habit.id] = habit } }
    override suspend fun delete(id: HabitId) { rows.value = rows.value.toMutableMap().also { it.remove(id) } }
}

/** In-memory [ChoreLocalStore]. */
class FakeChoreLocalStore(initial: Map<ChoreId, Chore> = emptyMap()) : ChoreLocalStore {
    private val rows = MutableStateFlow(initial)
    val all: Map<ChoreId, Chore> get() = rows.value
    override fun observeActive(): Flow<List<Chore>> = rows.map { it.values.filterNot { c -> c.isDeleted }.sortedBy { c -> c.sequence } }
    override fun observe(id: ChoreId): Flow<Chore?> = rows.map { it[id] }
    override suspend fun get(id: ChoreId): Chore? = rows.value[id]
    override suspend fun upsert(chore: Chore) { rows.value = rows.value.toMutableMap().also { it[chore.id] = chore } }
    override suspend fun delete(id: ChoreId) { rows.value = rows.value.toMutableMap().also { it.remove(id) } }
}

/** In-memory [EventLocalStore]. */
class FakeEventLocalStore(initial: Map<EventId, Event> = emptyMap()) : EventLocalStore {
    private val rows = MutableStateFlow(initial)
    val all: Map<EventId, Event> get() = rows.value
    override fun observeActive(): Flow<List<Event>> = rows.map { it.values.filterNot { e -> e.isDeleted }.sortedBy { e -> e.sequence } }
    override fun observe(id: EventId): Flow<Event?> = rows.map { it[id] }
    override suspend fun get(id: EventId): Event? = rows.value[id]
    override suspend fun upsert(event: Event) { rows.value = rows.value.toMutableMap().also { it[event.id] = event } }
    override suspend fun delete(id: EventId) { rows.value = rows.value.toMutableMap().also { it.remove(id) } }
}
