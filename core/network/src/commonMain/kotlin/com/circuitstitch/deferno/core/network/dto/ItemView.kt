package com.circuitstitch.deferno.core.network.dto

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * The `/items` polymorphic union (ADR-0011, CONTRACT-NOTES → "Items"). `/items` returns the closed
 * `oneOf{task,habit,chore,event}`, each item flattened and **discriminated by an injected `type`**
 * field (`task`/`habit`/`chore`/`event`); a redundant `kind` duplicates it on `/items` only — we
 * **ignore `kind`** and key off `type`.
 *
 * The mechanism is kotlinx.serialization's [JsonClassDiscriminator] over a sealed type: the
 * discriminator key is `type`, each variant declares its token via [SerialName], and the tolerant
 * reader ([com.circuitstitch.deferno.core.network.DefernoJson], `ignoreUnknownKeys`) lets the
 * redundant `kind` and the unmodelled per-item fields pass through. Sealed-type decoding registers
 * the subtypes automatically — no manual `SerializersModule` needed.
 *
 * v1 only needs a *domain* entity for Task: [Task] maps to `core:model`'s `Task` via
 * `mapper/TaskMapper.kt`; [Habit]/[Chore]/[Event] must parse faithfully but earn their domain
 * entities in their own issues.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed interface ItemView {

    /** The full `task` variant — the same load-bearing fields as [TaskDetailDto]. */
    @Serializable
    @SerialName("task")
    data class Task(
        val id: String,
        @SerialName("org_slug") val orgSlug: String,
        @SerialName("owner_org_id") val ownerOrgId: String? = null,
        val ref: String? = null,
        val sequence: Long? = null,
        val title: String,
        val status: TaskStatusWire = TaskStatusWire.Unknown,
        val labels: List<String> = emptyList(),
        @SerialName("parent_id") val parentId: String? = null,
        val children: List<String> = emptyList(),
        @SerialName("complete_by") val completeBy: String? = null,
        @SerialName("deadline_time_of_day") val deadlineTimeOfDay: String? = null,
        val productive: Double? = null,
        val desire: Double? = null,
        val pinned: Boolean = false,
        @SerialName("date_created") val dateCreated: String,
        @SerialName("finished_at") val finishedAt: String? = null,
        @SerialName("deleted_at") val deletedAt: String? = null,
        val description: String? = null,
        @SerialName("next_task_id") val nextTaskId: String? = null,
    ) : ItemView

    /** The `habit` variant — a recurring definition with no extra kind-specific fields. */
    @Serializable
    @SerialName("habit")
    data class Habit(
        val id: String,
        @SerialName("org_slug") val orgSlug: String,
        @SerialName("owner_org_id") val ownerOrgId: String? = null,
        val ref: String? = null,
        val sequence: Long? = null,
        val title: String,
        val status: DefStatusWire = DefStatusWire.Unknown,
        val labels: List<String> = emptyList(),
        @SerialName("parent_id") val parentId: String? = null,
        @SerialName("complete_by") val completeBy: String? = null,
        @SerialName("deadline_time_of_day") val deadlineTimeOfDay: String? = null,
        val pinned: Boolean = false,
        @SerialName("date_created") val dateCreated: String,
        @SerialName("deleted_at") val deletedAt: String? = null,
        val description: String? = null,
        val recurrence: RecurrenceDto? = null,
        @SerialName("series_id") val seriesId: String? = null,
        @SerialName("subtask_template") val subtaskTemplate: List<String> = emptyList(),
    ) : ItemView

    /** The `chore` variant — adds `cadence_mode` over the shared recurring base. */
    @Serializable
    @SerialName("chore")
    data class Chore(
        val id: String,
        @SerialName("org_slug") val orgSlug: String,
        @SerialName("owner_org_id") val ownerOrgId: String? = null,
        val ref: String? = null,
        val sequence: Long? = null,
        val title: String,
        val status: DefStatusWire = DefStatusWire.Unknown,
        val labels: List<String> = emptyList(),
        @SerialName("parent_id") val parentId: String? = null,
        @SerialName("complete_by") val completeBy: String? = null,
        @SerialName("deadline_time_of_day") val deadlineTimeOfDay: String? = null,
        val pinned: Boolean = false,
        @SerialName("date_created") val dateCreated: String,
        @SerialName("deleted_at") val deletedAt: String? = null,
        val description: String? = null,
        val recurrence: RecurrenceDto? = null,
        @SerialName("series_id") val seriesId: String? = null,
        @SerialName("subtask_template") val subtaskTemplate: List<String> = emptyList(),
        @SerialName("cadence_mode") val cadenceMode: String? = null,
    ) : ItemView

    /** The `event` variant — adds `all_day` + `end_time` + start/end time-of-day over the recurring base. */
    @Serializable
    @SerialName("event")
    data class Event(
        val id: String,
        @SerialName("org_slug") val orgSlug: String,
        @SerialName("owner_org_id") val ownerOrgId: String? = null,
        val ref: String? = null,
        val sequence: Long? = null,
        val title: String,
        val status: DefStatusWire = DefStatusWire.Unknown,
        val labels: List<String> = emptyList(),
        @SerialName("parent_id") val parentId: String? = null,
        @SerialName("complete_by") val completeBy: String? = null,
        val pinned: Boolean = false,
        @SerialName("date_created") val dateCreated: String,
        @SerialName("deleted_at") val deletedAt: String? = null,
        val description: String? = null,
        val recurrence: RecurrenceDto? = null,
        @SerialName("series_id") val seriesId: String? = null,
        @SerialName("subtask_template") val subtaskTemplate: List<String> = emptyList(),
        @SerialName("all_day") val allDay: Boolean = false,
        @SerialName("end_time") val endTime: String? = null,
        @SerialName("start_time_of_day") val startTimeOfDay: String? = null,
        @SerialName("end_time_of_day") val endTimeOfDay: String? = null,
    ) : ItemView
}

/**
 * The recurrence rule carried by Habit/Chore/Event (CONTRACT-NOTES → "Items"). Modelled tolerantly:
 * a [type] (`daily`/`weekly`/…) plus the optional shape fields seen on the wire (e.g. `days` for
 * weekly). `ignoreUnknownKeys` lets additive recurrence fields pass; the recurring kinds do not yet
 * have domain entities, so this stays a faithful pass-through.
 */
@Serializable
data class RecurrenceDto(
    val type: String? = null,
    val days: List<String> = emptyList(),
)
