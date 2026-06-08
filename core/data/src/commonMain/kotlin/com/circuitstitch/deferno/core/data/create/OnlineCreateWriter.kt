package com.circuitstitch.deferno.core.data.create

import com.circuitstitch.deferno.core.data.chore.ChoreLocalStore
import com.circuitstitch.deferno.core.data.connectivity.Connectivity
import com.circuitstitch.deferno.core.data.event.EventLocalStore
import com.circuitstitch.deferno.core.data.habit.HabitLocalStore
import com.circuitstitch.deferno.core.data.task.TaskLocalStore
import com.circuitstitch.deferno.core.model.Chore
import com.circuitstitch.deferno.core.model.ChoreId
import com.circuitstitch.deferno.core.model.Event
import com.circuitstitch.deferno.core.model.EventId
import com.circuitstitch.deferno.core.model.Habit
import com.circuitstitch.deferno.core.model.HabitId
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.network.ApiError
import com.circuitstitch.deferno.core.network.ApiResult
import com.circuitstitch.deferno.core.network.dto.ConvertItemPayload
import com.circuitstitch.deferno.core.network.dto.CreateChorePayload
import com.circuitstitch.deferno.core.network.dto.CreateEventPayload
import com.circuitstitch.deferno.core.network.dto.CreateHabitPayload
import com.circuitstitch.deferno.core.network.dto.CreateTaskPayload

/**
 * The production [CreateWriter] (ADR-0016, #71): the online-only create/convert path. **Bypasses the
 * outbox entirely** (a create is never enqueued — ADR-0016) and instead:
 *
 * 1. Gates on [Connectivity.isOnline]; if offline, returns [CreateResult.Offline] **without any
 *    network call or local write** — the ADR-0016 "enqueue nothing" guarantee.
 * 2. Otherwise POSTs directly via [ItemRemoteSource]; on a server-confirmed success it **seeds the
 *    server-assigned-id row** into the matching local store, so the existing observe `Flow` shows the
 *    new item with no manual refresh — then returns [CreateResult.Created].
 * 3. On a transport/4xx failure returns [CreateResult.Offline] for a transport error (the network
 *    really is unreachable — same gentle "reconnect to save") or [CreateResult.Failed] for a server
 *    rejection (retrying offline won't help).
 *
 * Convert reconciles the local cache: the converted item changes kind, so the old-kind row is removed
 * and the new-kind row seeded.
 */
class OnlineCreateWriter(
    private val connectivity: Connectivity,
    private val remoteSource: ItemRemoteSource,
    private val taskStore: TaskLocalStore,
    private val habitStore: HabitLocalStore,
    private val choreStore: ChoreLocalStore,
    private val eventStore: EventLocalStore,
) : CreateWriter {

    override suspend fun createTask(payload: CreateTaskPayload): CreateResult =
        guarded { remoteSource.createTask(payload).seedTask() }

    override suspend fun createHabit(payload: CreateHabitPayload): CreateResult =
        guarded { remoteSource.createHabit(payload).seedHabit() }

    override suspend fun createChore(payload: CreateChorePayload): CreateResult =
        guarded { remoteSource.createChore(payload).seedChore() }

    override suspend fun createEvent(payload: CreateEventPayload): CreateResult =
        guarded { remoteSource.createEvent(payload).seedEvent() }

    override suspend fun convert(id: String, fromKind: ItemKind, payload: ConvertItemPayload): CreateResult =
        guarded {
            when (val result = remoteSource.convert(id, payload)) {
                is ApiResult.Success -> {
                    removeOldKindRow(id, fromKind)
                    seedConverted(result.data)
                }
                is ApiResult.Failure -> result.error.toResult()
            }
        }

    /** Runs [block] only when online; offline short-circuits to [CreateResult.Offline], no write/call. */
    private suspend inline fun guarded(block: () -> CreateResult): CreateResult =
        if (connectivity.isOnline()) block() else CreateResult.Offline

    private suspend fun ApiResult<Task>.seedTask(): CreateResult = when (this) {
        is ApiResult.Success -> { taskStore.upsert(data); CreateResult.Created(ItemKind.Task, data.id.value) }
        is ApiResult.Failure -> error.toResult()
    }

    private suspend fun ApiResult<Habit>.seedHabit(): CreateResult = when (this) {
        is ApiResult.Success -> { habitStore.upsert(data); CreateResult.Created(ItemKind.Habit, data.id.value) }
        is ApiResult.Failure -> error.toResult()
    }

    private suspend fun ApiResult<Chore>.seedChore(): CreateResult = when (this) {
        is ApiResult.Success -> { choreStore.upsert(data); CreateResult.Created(ItemKind.Chore, data.id.value) }
        is ApiResult.Failure -> error.toResult()
    }

    private suspend fun ApiResult<Event>.seedEvent(): CreateResult = when (this) {
        is ApiResult.Success -> { eventStore.upsert(data); CreateResult.Created(ItemKind.Event, data.id.value) }
        is ApiResult.Failure -> error.toResult()
    }

    /** Seeds the converted item's new-kind row and reports the kind + id. */
    private suspend fun seedConverted(item: ConvertedItem): CreateResult = when (item) {
        is ConvertedItem.AsTask -> { taskStore.upsert(item.task); CreateResult.Created(ItemKind.Task, item.task.id.value) }
        is ConvertedItem.AsHabit -> { habitStore.upsert(item.habit); CreateResult.Created(ItemKind.Habit, item.habit.id.value) }
        is ConvertedItem.AsChore -> { choreStore.upsert(item.chore); CreateResult.Created(ItemKind.Chore, item.chore.id.value) }
        is ConvertedItem.AsEvent -> { eventStore.upsert(item.event); CreateResult.Created(ItemKind.Event, item.event.id.value) }
    }

    /** Removes the pre-convert row from whichever store held its old kind. */
    private suspend fun removeOldKindRow(id: String, fromKind: ItemKind) = when (fromKind) {
        ItemKind.Task -> taskStore.delete(TaskId(id))
        ItemKind.Habit -> habitStore.delete(HabitId(id))
        ItemKind.Chore -> choreStore.delete(ChoreId(id))
        ItemKind.Event -> eventStore.delete(EventId(id))
    }

    /**
     * A transport error means the network was unreachable — the same gentle "reconnect to save" as an
     * offline gate. Any other error (a 4xx server rejection, an unsupported version) is a [Failed] the
     * user can't fix by reconnecting.
     */
    private fun ApiError.toResult(): CreateResult = when (this) {
        is ApiError.Transport -> CreateResult.Offline
        is ApiError.Endpoint -> CreateResult.Failed(message)
        is ApiError.Status -> CreateResult.Failed(message)
        is ApiError.UnsupportedVersion -> CreateResult.Failed("Unsupported API version: $version")
    }
}
