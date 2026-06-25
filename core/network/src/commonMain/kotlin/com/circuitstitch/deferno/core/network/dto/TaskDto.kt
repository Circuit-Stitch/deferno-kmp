package com.circuitstitch.deferno.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The faithful flat wire DTOs for the Task endpoints (ADR-0011, CONTRACT-NOTES → "Items"). The
 * backend `#[serde(flatten)]`-es an `ItemEnvelope<T>`'s fields beside the payload, but
 * **kotlinx.serialization has no `@flatten`** — so each endpoint gets its own flat data class
 * combining the envelope fields (`ref`/`org_slug`/`sequence`/`type`) with the (flattened) item
 * fields, rather than a generic `ItemEnvelope<T>`.
 *
 * Lossless by design: snake_case wire keys are carried on [SerialName]; every enum field defaults to
 * `...Wire.Unknown` so the tolerant reader ([com.circuitstitch.deferno.core.network.DefernoJson]
 * with `coerceInputValues`) degrades additive enum tokens instead of crashing. The DTO→domain
 * mapping lives in `mapper/TaskMapper.kt`.
 */

/**
 * The list/summary element returned by `/tasks`, `/tasks/plan`, and (nested) `/tasks/today`. Must
 * parse every element of `contracts/fixtures/tasks-sample.json` AND `plan.json`.
 *
 * Some `plan.json` entries are brand-new rows the server has only just created — they OMIT `ref`,
 * `sequence`, and `type`, so those are nullable/defaulted ([ref], [sequence] nullable; [type]
 * nullable). [deletedAt] is the soft-delete tombstone (CONTRACT-NOTES → Sync, #21): not in the
 * fixtures but present on the wire, so it is modelled here for the reconcile path.
 *
 * [orgSlug] defaults to `""` because the `/tasks/today` payload nests this summary *without* its
 * `org_slug` (the org slug lives on the [TodayTaskDto] wrapper instead — CONTRACT-NOTES → Items).
 * In the flat list shapes (`/tasks`, `/tasks/plan`) `org_slug` is always present, so the default only
 * fires for the nested-today case; the mapper for today must supply the wrapper slug.
 */
@Serializable
data class TaskSummaryDto(
    val id: String,
    val title: String,
    val status: TaskStatusWire = TaskStatusWire.Unknown,
    val labels: List<String> = emptyList(),
    @SerialName("parent_id") val parentId: String? = null,
    val children: List<String> = emptyList(),
    @SerialName("complete_by") val completeBy: String? = null,
    @SerialName("deadline_time_of_day") val deadlineTimeOfDay: String? = null,
    val productive: Double? = null,
    val desire: Double? = null,
    @SerialName("date_created") val dateCreated: String,
    val pinned: Boolean = false,
    @SerialName("descendant_done") val descendantDone: Int? = null,
    @SerialName("descendant_total") val descendantTotal: Int? = null,
    val ref: String? = null,
    @SerialName("org_slug") val orgSlug: String = "",
    val sequence: Long? = null,
    val type: String? = null,
    @SerialName("deleted_at") val deletedAt: String? = null,
)

/**
 * The full single-item `task` payload returned by `/items` and `/tasks/{id}` — must parse the
 * `task`-typed element of `contracts/fixtures/items-sample.json`. Models the load-bearing fields for
 * the domain mapper; the tolerant reader silently ignores the rest (`actions`, `mood_*`,
 * `attachments`, `comment`, `assignee`, `created_by`, `group_id`, `kind`), so the DTO still parses
 * the full fixture element without modelling them.
 *
 * Carries the full-only enrichment the summary lacks ([ownerOrgId], [description], [nextTaskId],
 * [finishedAt]) so the mapper can produce a [com.circuitstitch.deferno.core.model.HydrationState.Full]
 * Task.
 */
@Serializable
data class TaskDetailDto(
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
    val type: String? = null,
    // Server-derived dependency state (ADR-0034, #289). The full single-item record carries the ordered
    // [blockedBy] edge list the detail/edit surfaces read; both flags default false when omitted.
    val blocked: Boolean = false,
    @SerialName("is_blocker") val isBlocker: Boolean = false,
    @SerialName("blocked_by") val blockedBy: List<BlockedByRefDto> = emptyList(),
)

/**
 * The `/tasks/today` element (CONTRACT-NOTES → Items): a *different*, nested shape —
 * `{ task: <summary>, priority_score, urgency_reason, ref, org_slug, sequence, type }`. The
 * envelope wrapper carries [ref]/[orgSlug]/[sequence]/[type]; the [task] is a nested
 * [TaskSummaryDto] (which itself omits ref/sequence there). Must parse `today-sample.json`.
 */
@Serializable
data class TodayTaskDto(
    val task: TaskSummaryDto,
    @SerialName("priority_score") val priorityScore: Double = 0.0,
    @SerialName("urgency_reason") val urgencyReason: String? = null,
    val ref: String? = null,
    @SerialName("org_slug") val orgSlug: String? = null,
    val sequence: Long? = null,
    val type: String? = null,
)
