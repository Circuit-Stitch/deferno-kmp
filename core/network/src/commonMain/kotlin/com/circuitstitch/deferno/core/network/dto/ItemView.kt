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
        // Server-computed subtree progress for a collapsed tree node's badge (ADR-0034, #226). Absent
        // on a freshly-created row → null; `ignoreUnknownKeys` lets older payloads omit them.
        @SerialName("descendant_done") val descendantDone: Long? = null,
        @SerialName("descendant_total") val descendantTotal: Long? = null,
        // Server-derived dependency state (ADR-0034, #289); both booleans default false so a payload
        // omitting them decodes cleanly. `blocked_by` is the ordered edge list on the full record.
        val blocked: Boolean = false,
        @SerialName("is_blocker") val isBlocker: Boolean = false,
        @SerialName("blocked_by") val blockedBy: List<BlockedByRefDto> = emptyList(),
        // External provenance for a synced/imported item (e.g. a GitHub issue → Task).
        // Absent on a native item; the tolerant reader ignores its unmodelled fields (write_policy/…).
        val external: ExternalProvenanceDto? = null,
        // Backend-hosted attachment metadata, size-only (#311). The cold-sync snapshot carries the full
        // `attachments` array on every item; the client used to drop it. Modelled here as size-only so the
        // DTO→domain mapper can roll it up to `attachment_count` + `attachment_total_size` for offline
        // attachment search/sort (ADR-0042). Absent/empty → no attachments.
        val attachments: List<AttachmentSizeDto> = emptyList(),
        // On-device (brain-dump recording) attachment metadata carried by the offline Backup file (#315,
        // ADR-0041) — distinct from the size-only backend-hosted `attachments` rollup above. The bytes ride
        // the zip at `attachments/<id>`; a real API response never carries this key (defaults empty, the
        // reader ignores it), so it is inert outside a Backup file. Task-only: on-device attachments link to
        // a Task (`local_attachment.task_id`, brain-dump → Task); the other kinds carry no local attachments.
        @SerialName("local_attachments") val localAttachments: List<LocalAttachmentDto> = emptyList(),
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
        // Server-derived dependency flags (ADR-0034, #289) — default false when omitted.
        val blocked: Boolean = false,
        @SerialName("is_blocker") val isBlocker: Boolean = false,
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
        // Server-derived dependency flags (ADR-0034, #289) — default false when omitted.
        val blocked: Boolean = false,
        @SerialName("is_blocker") val isBlocker: Boolean = false,
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
        // Server-derived dependency flags (ADR-0034, #289) — default false when omitted.
        val blocked: Boolean = false,
        @SerialName("is_blocker") val isBlocker: Boolean = false,
    ) : ItemView
}

/**
 * One edge of a Task's wire `blocked_by` array (ADR-0034, #289) → domain
 * [com.circuitstitch.deferno.core.model.BlockedByRef]. [item] is the blocker's id; [occurrence] is an
 * optional dated-firing ref — a later follow-up, so it is decoded tolerantly and defaults `null`.
 */
@Serializable
data class BlockedByRefDto(
    val item: String,
    val occurrence: String? = null,
)

/**
 * The wire `external` provenance block carried on a synced/imported item (the `ExternalProvenance` schema):
 * the opaque provider [id] (`owner/repo#N` for a GitHub issue), the short [source] key (`github`,
 * `google_calendar`), and the optional provider-side [url]. The wire also carries `write_policy`,
 * `updated_at`, and `master_id`; the client doesn't display them, so the tolerant reader drops them. Maps
 * to the domain [com.circuitstitch.deferno.core.model.ExternalRef] in `TaskMapper.kt`.
 */
@Serializable
data class ExternalProvenanceDto(
    val id: String,
    val source: String,
    val url: String? = null,
)

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
