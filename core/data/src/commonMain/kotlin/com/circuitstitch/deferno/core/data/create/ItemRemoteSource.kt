package com.circuitstitch.deferno.core.data.create

import com.circuitstitch.deferno.core.model.Chore
import com.circuitstitch.deferno.core.model.Event
import com.circuitstitch.deferno.core.model.Habit
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.network.ApiResult
import com.circuitstitch.deferno.core.network.dto.CreateChorePayload
import com.circuitstitch.deferno.core.network.dto.CreateEventPayload
import com.circuitstitch.deferno.core.network.dto.CreateHabitPayload
import com.circuitstitch.deferno.core.network.dto.CreateTaskPayload

/**
 * The network port for the **online-only create** flow (ADR-0016, #71). Its post-creation counterpart
 * **convert** deliberately lives on [ItemConverter]/[StampedItemConverter] instead: convert is the only
 * call in this package that carries a client-minted Activity stamp (#364), and folding it back in here
 * would force that stamp — or a nullable stand-in for it — onto four creates that have no use for one.
 * Unlike the offline-first [com.circuitstitch.deferno.core.data.task.TaskRemoteSource]
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
}
