package com.circuitstitch.deferno.core.data.create

import com.circuitstitch.deferno.core.model.Chore
import com.circuitstitch.deferno.core.model.Event
import com.circuitstitch.deferno.core.model.Habit
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.network.ApiResult
import com.circuitstitch.deferno.core.network.dto.ConvertItemPayload
import com.circuitstitch.deferno.core.network.dto.CreateChorePayload
import com.circuitstitch.deferno.core.network.dto.CreateEventPayload
import com.circuitstitch.deferno.core.network.dto.CreateHabitPayload
import com.circuitstitch.deferno.core.network.dto.CreateTaskPayload

/**
 * The network port for the **online-only create** flow (ADR-0016, #71) and its post-creation
 * counterpart, **convert**. Unlike the offline-first [com.circuitstitch.deferno.core.data.task.TaskRemoteSource]
 * (whose reads degrade to `emptyList()`/`null` so a failed refresh leaves the cache intact), create is
 * a direct write that the caller must be able to distinguish success from failure on — so every method
 * returns an [ApiResult] of the **domain** entity (the wire DTO is condensed at the boundary, ADR-0011).
 *
 * The create response is the full single-item shape (`Envelope<item>`), so a success carries a
 * fully-hydrated domain row whose server-assigned id seeds the local cache (the create writer's job).
 */
interface ItemRemoteSource {
    /** `POST /tasks` — returns the created [Task] (server id + full hydration) or a typed failure. */
    suspend fun createTask(payload: CreateTaskPayload): ApiResult<Task>

    /** `POST /habits` — returns the created [Habit] or a typed failure. */
    suspend fun createHabit(payload: CreateHabitPayload): ApiResult<Habit>

    /** `POST /chores` — returns the created [Chore] or a typed failure. */
    suspend fun createChore(payload: CreateChorePayload): ApiResult<Chore>

    /** `POST /events` — returns the created [Event] or a typed failure. */
    suspend fun createEvent(payload: CreateEventPayload): ApiResult<Event>

    /**
     * `POST /items/{id}/convert` — change an existing item's [ConvertItemPayload.type]. Returns the
     * converted item's new kind ([ConvertedItem]) so the caller can reconcile the local cache (remove
     * the old-kind row, seed the new-kind row).
     */
    suspend fun convert(id: String, payload: ConvertItemPayload): ApiResult<ConvertedItem>
}

/**
 * The outcome of a successful convert: exactly one of the four domain kinds the item became. A sealed
 * result (not a nullable quartet) so the caller's reconcile is an exhaustive `when`.
 */
sealed interface ConvertedItem {
    val kind: ItemKind

    data class AsTask(val task: Task) : ConvertedItem {
        override val kind: ItemKind get() = ItemKind.Task
    }

    data class AsHabit(val habit: Habit) : ConvertedItem {
        override val kind: ItemKind get() = ItemKind.Habit
    }

    data class AsChore(val chore: Chore) : ConvertedItem {
        override val kind: ItemKind get() = ItemKind.Chore
    }

    data class AsEvent(val event: Event) : ConvertedItem {
        override val kind: ItemKind get() = ItemKind.Event
    }
}
