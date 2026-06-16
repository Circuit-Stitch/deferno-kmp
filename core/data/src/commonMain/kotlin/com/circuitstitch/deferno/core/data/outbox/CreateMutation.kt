package com.circuitstitch.deferno.core.data.outbox

import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.network.DefernoJson
import com.circuitstitch.deferno.core.network.dto.CreateChorePayload
import com.circuitstitch.deferno.core.network.dto.CreateEventPayload
import com.circuitstitch.deferno.core.network.dto.CreateHabitPayload
import com.circuitstitch.deferno.core.network.dto.CreateTaskPayload
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * The offline-first **create** intent (#185, ADR-0001 forward path from ADR-0016): unlike a
 * [TaskMutation] — which transforms an *existing* cached row — a create *inserts* a brand-new Item, so
 * it carries no `applyTo` (the [com.circuitstitch.deferno.core.data.create.OfflineCreateWriter] does the
 * optimistic insert directly) and exists only to render the replayable [OutboxRequest].
 *
 * **Client-supplied id is the idempotency key.** The rendered body is the create payload PLUS an
 * explicit `"id"` — the client-generated Item UUID. The backend creates the Item under that id and
 * dedupes a replay on it (Kyle-Falconer/Deferno#402), so replaying the same create after an interrupted
 * request never duplicates. The body is built once from the payload's own serializer (so its
 * `@SerialName`s + `explicitNulls=false` omit-vs-null rules apply, ADR-0011) with `id` merged in.
 *
 * The [target] is `create:<kind>:<id>` — the prefix the [OutboxProcessor] routes on to replay a create
 * through the response-bearing [OutboxRequestSender.sendCreate] (it needs the server's returned id to
 * confirm the pending-create row and heal a divergent canonical id), distinct from the fire-and-forget
 * replay of every edit.
 */
sealed interface CreateMutation : Mutation {
    /** The client-generated Item id this create inserts under (the local row id + the wire `id`). */
    val itemId: String

    /** The converged Item kind being created — selects the endpoint + the confirm/heal store. */
    val itemKind: ItemKind

    override val target: String get() = "create:${itemKind.name}:$itemId"
}

/** `POST /tasks` with `{"id": …, …CreateTaskPayload}`. */
data class CreateTaskItem(override val itemId: String, val payload: CreateTaskPayload) : CreateMutation {
    override val itemKind: ItemKind get() = ItemKind.Task
    override fun toRequest(): OutboxRequest = createRequest("tasks", itemId, DefernoJson.encodeToJsonElement(payload).jsonObject)
}

/** `POST /habits` with `{"id": …, …CreateHabitPayload}`. */
data class CreateHabitItem(override val itemId: String, val payload: CreateHabitPayload) : CreateMutation {
    override val itemKind: ItemKind get() = ItemKind.Habit
    override fun toRequest(): OutboxRequest = createRequest("habits", itemId, DefernoJson.encodeToJsonElement(payload).jsonObject)
}

/** `POST /chores` with `{"id": …, …CreateChorePayload}`. */
data class CreateChoreItem(override val itemId: String, val payload: CreateChorePayload) : CreateMutation {
    override val itemKind: ItemKind get() = ItemKind.Chore
    override fun toRequest(): OutboxRequest = createRequest("chores", itemId, DefernoJson.encodeToJsonElement(payload).jsonObject)
}

/** `POST /events` with `{"id": …, …CreateEventPayload}`. */
data class CreateEventItem(override val itemId: String, val payload: CreateEventPayload) : CreateMutation {
    override val itemKind: ItemKind get() = ItemKind.Event
    override fun toRequest(): OutboxRequest = createRequest("events", itemId, DefernoJson.encodeToJsonElement(payload).jsonObject)
}

/** A `POST /{kindPath}` whose body is the payload's fields plus the explicit client-supplied `id`. */
private fun createRequest(kindPath: String, id: String, payload: JsonObject): OutboxRequest {
    val body = buildJsonObject {
        put("id", id)
        payload.forEach { (key, value) -> put(key, value) }
    }
    return OutboxRequest(OutboxMethod.Post, listOf(kindPath), body.toString())
}
