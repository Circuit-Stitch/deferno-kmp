package com.circuitstitch.deferno.core.network.dto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The **item-history** wire DTOs (`GET /items/{id}/history` → `Envelope<List<TaskAction>>`): the
 * server-authored, read-only audit log the Task detail's ACTIVITY feed renders (ADR-0043). One entry
 * is a [kind] + the RFC3339 [recordedAt] instant it was applied.
 *
 * [TaskActionKind] is Serde's **externally-tagged** enum, which kotlinx's discriminator-based
 * polymorphism (as used by [ItemView]) cannot decode: unit variants are **bare JSON strings**
 * (`"Created"`, `"MergedIntoParent"`) and data variants are **single-key objects**
 * (`{"StatusChanged": {…}}`), with no `type` discriminator. So it carries a hand-written
 * [TaskActionKindSerializer] that routes on the JSON shape and degrades any unrecognised token to
 * [TaskActionKind.Unknown] (the tolerant-reader contract, ADR-0011).
 */
@Serializable
data class TaskActionDto(
    val kind: TaskActionKind,
    @SerialName("recorded_at") val recordedAt: String,
)

/**
 * The externally-tagged history-action union (see [TaskActionDto]); decoded by
 * [TaskActionKindSerializer]. Faithful to the wire (raw String peer ids, [TaskStatusWire] statuses);
 * the DTO→domain condense lives in `mapper/TaskActionMapper.kt` (ADR-0011).
 */
@Serializable(with = TaskActionKindSerializer::class)
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
 * Hand-written codec for the externally-tagged [TaskActionKind]. **Decode** (server → domain): a bare
 * `JsonPrimitive` string selects a unit variant; a `JsonObject`'s single key selects a data variant
 * whose value decodes through the same tolerant reader; anything unrecognised (or of an unexpected JSON
 * shape) degrades to [TaskActionKind.Unknown]. **Encode** (kind → cache payload): the symmetric inverse
 * — a unit variant writes its bare string, a data variant its single-key object — so the item-history
 * cache can round-trip a kind through the `itemHistoryEntry.payload` TEXT column (ADR-0043; this is a
 * *local* round-trip, not a wire write — the server authors history, this client never POSTs it).
 * `Unknown` encodes to the bare `"Unknown"`, which decodes straight back to [TaskActionKind.Unknown].
 */
object TaskActionKindSerializer : KSerializer<TaskActionKind> {

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("TaskActionKind")

    override fun deserialize(decoder: Decoder): TaskActionKind {
        val input = decoder as? JsonDecoder
            ?: error("TaskActionKind can only be read from JSON")
        return when (val element = input.decodeJsonElement()) {
            is JsonPrimitive -> when (element.content) {
                "Created" -> TaskActionKind.Created
                "MergedIntoParent" -> TaskActionKind.MergedIntoParent
                else -> TaskActionKind.Unknown
            }
            is JsonObject -> {
                val entry = element.entries.singleOrNull() ?: return TaskActionKind.Unknown
                val json = input.json
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

    override fun serialize(encoder: Encoder, value: TaskActionKind) {
        val output = encoder as? JsonEncoder
            ?: error("TaskActionKind can only be written to JSON")
        val json = output.json
        val element: JsonElement = when (value) {
            TaskActionKind.Created -> JsonPrimitive("Created")
            TaskActionKind.MergedIntoParent -> JsonPrimitive("MergedIntoParent")
            TaskActionKind.Unknown -> JsonPrimitive("Unknown")
            is TaskActionKind.Updated ->
                tagged("Updated", json.encodeToJsonElement(UpdatedPayload.serializer(), UpdatedPayload(value.fields)))
            is TaskActionKind.Moved ->
                tagged("Moved", json.encodeToJsonElement(MovedPayload.serializer(), MovedPayload(value.fromParentId, value.toParentId, value.position)))
            is TaskActionKind.ParentAssigned ->
                tagged("ParentAssigned", json.encodeToJsonElement(ParentAssignedPayload.serializer(), ParentAssignedPayload(value.parentId)))
            is TaskActionKind.Split ->
                tagged("Split", json.encodeToJsonElement(ChildIdPayload.serializer(), ChildIdPayload(value.childId)))
            is TaskActionKind.FoldedInto ->
                tagged("FoldedInto", json.encodeToJsonElement(FoldedIntoPayload.serializer(), FoldedIntoPayload(value.nextTaskId)))
            is TaskActionKind.MergedChild ->
                tagged("MergedChild", json.encodeToJsonElement(ChildIdPayload.serializer(), ChildIdPayload(value.childId)))
            is TaskActionKind.StatusChanged ->
                tagged("StatusChanged", json.encodeToJsonElement(StatusChangedPayload.serializer(), StatusChangedPayload(value.from, value.to)))
        }
        output.encodeJsonElement(element)
    }

    /** The externally-tagged single-key object `{"<tag>": <payload>}` a data variant encodes to. */
    private fun tagged(tag: String, payload: JsonElement): JsonObject = JsonObject(mapOf(tag to payload))
}
