package com.circuitstitch.deferno.core.data.create

import com.circuitstitch.deferno.core.data.chore.ChoreLocalStore
import com.circuitstitch.deferno.core.data.connectivity.Connectivity
import com.circuitstitch.deferno.core.data.event.EventLocalStore
import com.circuitstitch.deferno.core.data.habit.HabitLocalStore
import com.circuitstitch.deferno.core.data.outbox.CreateChoreItem
import com.circuitstitch.deferno.core.data.outbox.CreateEventItem
import com.circuitstitch.deferno.core.data.outbox.CreateHabitItem
import com.circuitstitch.deferno.core.data.outbox.CreateMutation
import com.circuitstitch.deferno.core.data.outbox.CreateTaskItem
import com.circuitstitch.deferno.core.data.outbox.OutboxStore
import com.circuitstitch.deferno.core.data.task.TaskLocalStore
import com.circuitstitch.deferno.core.model.Chore
import com.circuitstitch.deferno.core.model.ChoreId
import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.Event
import com.circuitstitch.deferno.core.model.EventId
import com.circuitstitch.deferno.core.model.Habit
import com.circuitstitch.deferno.core.model.HabitId
import com.circuitstitch.deferno.core.model.HydrationState
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
import com.circuitstitch.deferno.core.network.ApiError
import com.circuitstitch.deferno.core.network.ApiResult
import com.circuitstitch.deferno.core.network.dto.ConvertItemPayload
import com.circuitstitch.deferno.core.network.dto.CreateChorePayload
import com.circuitstitch.deferno.core.network.dto.CreateEventPayload
import com.circuitstitch.deferno.core.network.dto.CreateHabitPayload
import com.circuitstitch.deferno.core.network.dto.CreateTaskPayload
import kotlinx.datetime.LocalTime
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The production [CreateWriter] (#185, ADR-0001 forward path from ADR-0016): **offline-first create**.
 * It no longer gates on connectivity — every create:
 *
 * 1. mints a client-side Item UUID ([newId]),
 * 2. inserts the optimistic local row into the matching store immediately (so the observe `Flow` shows
 *    it with no network round trip — offline or online),
 * 3. records the create in the [PendingCreateStore] (state = pending), and
 * 4. enqueues a `POST /{kind}` carrying that id ([CreateMutation]) on the existing outbox.
 *
 * The backend dedupes the create on the client id (Kyle-Falconer/Deferno#402), so a replay after an
 * interrupted request never duplicates. On successful replay the outbox processor's
 * [com.circuitstitch.deferno.core.data.outbox.CreateReplayListener] confirms the pending-create row (and
 * heals if the server diverged); a terminal rejection undoes the optimism. So a create always returns
 * [CreateResult.Created] — there is no offline refusal any more.
 *
 * **Convert stays online-only.** Converting an *existing* item's kind has no client-id idempotency story
 * (it is a server-side mutation of a row that already exists), so [convert] keeps the ADR-0016 gate:
 * online → POST + reconcile the cache; offline → [CreateResult.Offline]; 4xx → [CreateResult.Failed].
 *
 * The optimistic rows carry only what the New form supplied (`hydration = Full` so the user's typed
 * description survives a summary refresh until the server's real row replaces it on confirm). `orgSlug`
 * uses [orgSlug] (the Active Account's slug, or empty — it is not a list filter; `selectAllActive`
 * filters on `deleted_at` only — and reconcile fills the real slug on confirm).
 */
class OfflineCreateWriter(
    private val connectivity: Connectivity,
    private val remoteSource: ItemRemoteSource,
    private val taskStore: TaskLocalStore,
    private val habitStore: HabitLocalStore,
    private val choreStore: ChoreLocalStore,
    private val eventStore: EventLocalStore,
    private val outbox: OutboxStore,
    private val pendingCreateStore: PendingCreateStore,
    private val newId: () -> String = ::newItemId,
    private val now: () -> Instant = { Clock.System.now() },
    private val orgSlug: () -> String = { "" },
) : CreateWriter {

    override suspend fun createTask(payload: CreateTaskPayload): CreateResult {
        val id = newId()
        taskStore.upsert(
            Task(
                id = TaskId(id),
                orgSlug = orgSlug(),
                title = payload.title,
                workingState = WorkingState.Open,
                labels = payload.labels.orEmpty(),
                parentId = payload.parentId?.let(::TaskId),
                completeBy = payload.completeBy.toInstantOrNull(),
                deadlineTimeOfDay = payload.deadlineTimeOfDay.toLocalTimeOrNull(),
                productive = payload.productive,
                desire = payload.desire,
                dateCreated = now(),
                hydration = HydrationState.Full,
                description = payload.description,
            ),
        )
        return enqueue(id, ItemKind.Task, CreateTaskItem(id, payload))
    }

    override suspend fun createHabit(payload: CreateHabitPayload): CreateResult {
        val id = newId()
        habitStore.upsert(
            Habit(
                id = HabitId(id),
                orgSlug = orgSlug(),
                title = payload.title,
                definitionState = DefinitionState.Active,
                labels = payload.labels.orEmpty(),
                completeBy = payload.completeBy.toInstantOrNull(),
                deadlineTimeOfDay = payload.deadlineTimeOfDay.toLocalTimeOrNull(),
                dateCreated = now(),
                hydration = HydrationState.Full,
                description = payload.description,
            ),
        )
        return enqueue(id, ItemKind.Habit, CreateHabitItem(id, payload))
    }

    override suspend fun createChore(payload: CreateChorePayload): CreateResult {
        val id = newId()
        choreStore.upsert(
            Chore(
                id = ChoreId(id),
                orgSlug = orgSlug(),
                title = payload.title,
                definitionState = DefinitionState.Active,
                cadenceMode = payload.cadenceMode,
                labels = payload.labels.orEmpty(),
                completeBy = payload.completeBy.toInstantOrNull(),
                deadlineTimeOfDay = payload.deadlineTimeOfDay.toLocalTimeOrNull(),
                dateCreated = now(),
                hydration = HydrationState.Full,
                description = payload.description,
            ),
        )
        return enqueue(id, ItemKind.Chore, CreateChoreItem(id, payload))
    }

    override suspend fun createEvent(payload: CreateEventPayload): CreateResult {
        val id = newId()
        eventStore.upsert(
            Event(
                id = EventId(id),
                orgSlug = orgSlug(),
                title = payload.title,
                definitionState = DefinitionState.Active,
                completeBy = payload.completeBy.toInstantOrNull(),
                endTime = payload.endTime.toInstantOrNull(),
                startTimeOfDay = payload.startTimeOfDay.toLocalTimeOrNull(),
                endTimeOfDay = payload.endTimeOfDay.toLocalTimeOrNull(),
                labels = payload.labels.orEmpty(),
                dateCreated = now(),
                hydration = HydrationState.Full,
                description = payload.description,
            ),
        )
        return enqueue(id, ItemKind.Event, CreateEventItem(id, payload))
    }

    /** Records the pending create + enqueues its replayable outbox entry, then reports the new id. */
    private suspend fun enqueue(id: String, kind: ItemKind, mutation: CreateMutation): CreateResult {
        pendingCreateStore.add(id, kind)
        outbox.enqueue(mutation.target, mutation.toRequest(), now())
        return CreateResult.Created(kind, id)
    }

    override suspend fun convert(id: String, fromKind: ItemKind, payload: ConvertItemPayload): CreateResult {
        if (!connectivity.isOnline()) return CreateResult.Offline
        return when (val result = remoteSource.convert(id, payload)) {
            is ApiResult.Success -> {
                removeOldKindRow(id, fromKind)
                seedConverted(result.data)
            }
            is ApiResult.Failure -> result.error.toResult()
        }
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
     * A transport error on convert means the network was unreachable → the gentle "reconnect to save";
     * any other error (a 4xx rejection, an unsupported version) is a [Failed] reconnecting won't fix.
     */
    private fun ApiError.toResult(): CreateResult = when (this) {
        is ApiError.Transport -> CreateResult.Offline
        is ApiError.Endpoint -> CreateResult.Failed(message)
        is ApiError.Status -> CreateResult.Failed(message)
        is ApiError.UnsupportedVersion -> CreateResult.Failed("Unsupported API version: $version")
    }
}

/** Parses an RFC3339 wire instant, tolerating a malformed/absent value (→ null) rather than throwing. */
private fun String?.toInstantOrNull(): Instant? = this?.let { runCatching { Instant.parse(it) }.getOrNull() }

/** Parses an "HH:MM" wire time-of-day, tolerating a malformed/absent value (→ null). */
private fun String?.toLocalTimeOrNull(): LocalTime? = this?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
