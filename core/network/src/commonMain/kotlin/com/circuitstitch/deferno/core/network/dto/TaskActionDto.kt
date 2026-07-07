package com.circuitstitch.deferno.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The **item-history** wire DTO (`GET /items/{id}/history` → `Envelope<List<TaskAction>>`): the
 * server-authored, read-only audit log the Task detail's ACTIVITY feed renders (ADR-0043). One entry
 * is a [kind] + the RFC3339 [recordedAt] instant it was applied.
 *
 * [kind] is kept as the **raw wire [JsonElement]**, not an eagerly-typed value: the kind is Serde's
 * externally-tagged union (bare strings like `"Created"`, single-key objects like `{"StatusChanged":{…}}`,
 * with no `type` discriminator — which kotlinx's discriminator polymorphism, as used by [ItemView], can't
 * decode). The item-history cache stores the server's own bytes verbatim (ADR-0043) and decodes lazily on
 * read via [toTaskActionKind], so an additive/unknown kind stays recoverable once the client learns to
 * decode it — and no encoder is needed, since the client never writes history (it is server-authored).
 */
@Serializable
data class TaskActionDto(
    val kind: JsonElement,
    @SerialName("recorded_at") val recordedAt: String,
)

/**
 * The externally-tagged history-action union, decoded from a raw wire [JsonElement] by [toTaskActionKind]
 * and condensed to the domain `ItemHistoryEvent` by `mapper/TaskActionMapper.kt` (ADR-0011). Faithful to
 * the wire (raw String peer ids, [TaskStatusWire] statuses).
 */
sealed interface TaskActionKind {

    /** `"Created"` (bare string). */
    data object Created : TaskActionKind

    /** `{"Updated": {"fields": [...]}}` — the changed field names. */
    data class Updated(val fields: List<String>) : TaskActionKind

    /** `{"Moved": {from_parent_id?, to_parent_id?, position?}}` — all peers nullable. */
    data class Moved(val fromParentId: String?, val toParentId: String?, val position: Int?) : TaskActionKind

    /** `{"ParentAssigned": {parent_id}}`. */
    data class ParentAssigned(val parentId: String) : TaskActionKind

    /** `{"Split": {child_id}}`. */
    data class Split(val childId: String) : TaskActionKind

    /** `{"FoldedInto": {next_task_id}}`. */
    data class FoldedInto(val nextTaskId: String) : TaskActionKind

    /** `{"MergedChild": {child_id}}`. */
    data class MergedChild(val childId: String) : TaskActionKind

    /** `"MergedIntoParent"` (bare string). */
    data object MergedIntoParent : TaskActionKind

    /** `{"StatusChanged": {from, to}}` — [TaskStatusWire] (v1 Task-only; recurring DefStatus → Unknown). */
    data class StatusChanged(val from: TaskStatusWire, val to: TaskStatusWire) : TaskActionKind

    /** An additive/unrecognised server action kind — degraded rather than crashing the reader. */
    data object Unknown : TaskActionKind
}

// --- inner payload DTOs for the single-key data variants (decoded through the tolerant reader) ---

@Serializable
private class UpdatedPayload(val fields: List<String> = emptyList())

@Serializable
private class MovedPayload(
    @SerialName("from_parent_id") val fromParentId: String? = null,
    @SerialName("to_parent_id") val toParentId: String? = null,
    val position: Int? = null,
)

@Serializable
private class ParentAssignedPayload(@SerialName("parent_id") val parentId: String)

@Serializable
private class ChildIdPayload(@SerialName("child_id") val childId: String)

@Serializable
private class FoldedIntoPayload(@SerialName("next_task_id") val nextTaskId: String)

/** from/to default to [TaskStatusWire.Unknown] so an out-of-vocabulary token (a recurring kind's
 *  DefStatus) coerces to Unknown rather than throwing on the required, default-less wire field. */
@Serializable
private class StatusChangedPayload(
    val from: TaskStatusWire = TaskStatusWire.Unknown,
    val to: TaskStatusWire = TaskStatusWire.Unknown,
)

/**
 * Decode a raw wire [JsonElement] into a [TaskActionKind] (server → domain), lazily off the cached
 * payload: a bare `JsonPrimitive` string selects a unit variant; a `JsonObject`'s single key selects a
 * data variant whose value decodes through [json] (the tolerant reader); anything unrecognised (or of an
 * unexpected JSON shape) degrades to [TaskActionKind.Unknown] (the tolerant-reader contract, ADR-0011).
 *
 * There is no inverse encoder: the item-history cache stores the server's raw bytes verbatim (ADR-0043),
 * so a kind is only ever decoded, never re-serialized — the client never POSTs history.
 */
fun JsonElement.toTaskActionKind(json: Json): TaskActionKind {
    return when (this) {
        is JsonPrimitive -> when (content) {
            "Created" -> TaskActionKind.Created
            "MergedIntoParent" -> TaskActionKind.MergedIntoParent
            else -> TaskActionKind.Unknown
        }
        is JsonObject -> {
            val entry = entries.singleOrNull() ?: return TaskActionKind.Unknown
            when (entry.key) {
                "Updated" ->
                    TaskActionKind.Updated(json.decodeFromJsonElement(UpdatedPayload.serializer(), entry.value).fields)
                "Moved" ->
                    json.decodeFromJsonElement(MovedPayload.serializer(), entry.value)
                        .let { TaskActionKind.Moved(it.fromParentId, it.toParentId, it.position) }
                "ParentAssigned" ->
                    TaskActionKind.ParentAssigned(json.decodeFromJsonElement(ParentAssignedPayload.serializer(), entry.value).parentId)
                "Split" ->
                    TaskActionKind.Split(json.decodeFromJsonElement(ChildIdPayload.serializer(), entry.value).childId)
                "FoldedInto" ->
                    TaskActionKind.FoldedInto(json.decodeFromJsonElement(FoldedIntoPayload.serializer(), entry.value).nextTaskId)
                "MergedChild" ->
                    TaskActionKind.MergedChild(json.decodeFromJsonElement(ChildIdPayload.serializer(), entry.value).childId)
                "StatusChanged" ->
                    json.decodeFromJsonElement(StatusChangedPayload.serializer(), entry.value)
                        .let { TaskActionKind.StatusChanged(it.from, it.to) }
                else -> TaskActionKind.Unknown
            }
        }
        else -> TaskActionKind.Unknown
    }
}
