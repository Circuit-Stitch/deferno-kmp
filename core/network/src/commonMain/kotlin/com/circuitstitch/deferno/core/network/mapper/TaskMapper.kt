package com.circuitstitch.deferno.core.network.mapper

import com.circuitstitch.deferno.core.model.BlockedByRef
import com.circuitstitch.deferno.core.model.ExternalRef
import com.circuitstitch.deferno.core.model.HydrationState
import com.circuitstitch.deferno.core.model.ItemSource
import com.circuitstitch.deferno.core.model.OrgId
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.network.dto.BlockedByRefDto
import com.circuitstitch.deferno.core.network.dto.ExternalProvenanceDto
import com.circuitstitch.deferno.core.network.dto.ItemView
import com.circuitstitch.deferno.core.network.dto.TaskDetailDto
import com.circuitstitch.deferno.core.network.dto.TaskSummaryDto
import kotlinx.datetime.LocalTime
import kotlin.time.Instant

/**
 * The DTO→domain `Task` mapping — the "condense at the edge" core of ADR-0011 (#18). The wire DTOs'
 * ugliness (string ids, RFC3339 timestamp strings, the overloaded wire status, the summary-vs-full
 * field split) stays quarantined in `core:network`; everything above the network boundary sees only
 * the clean `core:model` [Task].
 *
 * **Hydration (ADR-0001, #22).** A summary maps to a [HydrationState.Summary] Task with the
 * full-only enrichment (owner/description/next) left null; a detail/`ItemView.Task` maps to a
 * [HydrationState.Full] Task carrying them — so a full-snapshot reconcile from a summary never
 * clobbers an already-hydrated row.
 */

/**
 * Maps a list/summary DTO to a [HydrationState.Summary] [Task]. `owner_org_id`/`description`/
 * `next_task_id` are not on a summary, so they are left `null`; [TaskSummaryDto.ref] may be `null`
 * on a freshly created row (it is never used as identity — [Task.id] is the reconcile key).
 */
fun TaskSummaryDto.toDomain(): Task = Task(
    id = TaskId(id),
    orgSlug = orgSlug,
    title = title,
    workingState = status.toWorkingState(),
    labels = labels,
    parentId = parentId?.let(::TaskId),
    children = children.map(::TaskId),
    completeBy = completeBy.toInstantOrNull(),
    deadlineTimeOfDay = deadlineTimeOfDay.toLocalTimeOrNull(),
    productive = productive,
    desire = desire,
    pinned = pinned,
    sequence = sequence,
    ref = ref,
    dateCreated = Instant.parse(dateCreated),
    finishedAt = null,
    deletedAt = deletedAt.toInstantOrNull(),
    hydration = HydrationState.Summary,
    ownerOrgId = null,
    description = null,
    nextTaskId = null,
)

/**
 * Maps a full single-item DTO to a [HydrationState.Full] [Task], populating the enrichment fields a
 * summary lacks: [Task.ownerOrgId], [Task.description], [Task.nextTaskId], and [Task.finishedAt].
 */
fun TaskDetailDto.toDomain(): Task = Task(
    id = TaskId(id),
    orgSlug = orgSlug,
    title = title,
    workingState = status.toWorkingState(),
    labels = labels,
    parentId = parentId?.let(::TaskId),
    children = children.map(::TaskId),
    completeBy = completeBy.toInstantOrNull(),
    deadlineTimeOfDay = deadlineTimeOfDay.toLocalTimeOrNull(),
    productive = productive,
    desire = desire,
    pinned = pinned,
    sequence = sequence,
    ref = ref,
    dateCreated = Instant.parse(dateCreated),
    finishedAt = finishedAt.toInstantOrNull(),
    deletedAt = deletedAt.toInstantOrNull(),
    hydration = HydrationState.Full,
    ownerOrgId = ownerOrgId?.let(::OrgId),
    description = description,
    nextTaskId = nextTaskId?.let(::TaskId),
    blocked = blocked,
    isBlocker = isBlocker,
    blockedBy = blockedBy.toDomain(),
    external = external?.toDomain(),
    attachmentCount = attachments.size,
    attachmentTotalSize = attachments.sumOf { it.size },
)

/**
 * Maps the `task` variant of an [ItemView] to a [HydrationState.Full] domain [Task]; returns `null`
 * for the habit/chore/event variants (which have no v1 domain entity). The helper that pulls domain
 * Tasks out of a heterogeneous `/items` payload — e.g. `items.mapNotNull { it.asTaskOrNull() }`.
 */
fun ItemView.asTaskOrNull(): Task? = when (this) {
    is ItemView.Task -> Task(
        id = TaskId(id),
        orgSlug = orgSlug,
        title = title,
        workingState = status.toWorkingState(),
        labels = labels,
        parentId = parentId?.let(::TaskId),
        children = children.map(::TaskId),
        completeBy = completeBy.toInstantOrNull(),
        deadlineTimeOfDay = deadlineTimeOfDay.toLocalTimeOrNull(),
        productive = productive,
        desire = desire,
        pinned = pinned,
        sequence = sequence,
        ref = ref,
        dateCreated = Instant.parse(dateCreated),
        finishedAt = finishedAt.toInstantOrNull(),
        deletedAt = deletedAt.toInstantOrNull(),
        hydration = HydrationState.Full,
        ownerOrgId = ownerOrgId?.let(::OrgId),
        description = description,
        nextTaskId = nextTaskId?.let(::TaskId),
        descendantDone = descendantDone,
        descendantTotal = descendantTotal,
        blocked = blocked,
        isBlocker = isBlocker,
        blockedBy = blockedBy.toDomain(),
        external = external?.toDomain(),
        attachmentCount = attachments.size,
        attachmentTotalSize = attachments.sumOf { it.size },
    )
    is ItemView.Habit, is ItemView.Chore, is ItemView.Event -> null
}

/**
 * Condenses the wire `external` block to the domain [ExternalRef], mapping the short source key to an
 * [ItemSource]. An unrecognised provider (only GitHub + Google Calendar are modelled for v1) yields `null`
 * — the item then reads as native rather than wearing a mark we can't render.
 */
private fun ExternalProvenanceDto.toDomain(): ExternalRef? {
    val itemSource = when (source.lowercase()) {
        "github" -> ItemSource.GitHub
        "google_calendar" -> ItemSource.GoogleCalendar
        else -> return null
    }
    return ExternalRef(source = itemSource, id = id, url = url)
}

/** Condenses the wire `blocked_by` edge DTOs to domain [BlockedByRef]s, preserving order (#289). */
private fun List<BlockedByRefDto>.toDomain(): List<BlockedByRef> =
    map { BlockedByRef(item = it.item, occurrence = it.occurrence) }

/**
 * Parses an RFC3339 timestamp string to an [Instant], or `null` when absent. Centralised so every
 * Task timestamp field ([TaskSummaryDto.completeBy]/[TaskDetailDto.finishedAt]/`deleted_at`/…) goes
 * through the same `kotlin.time.Instant` parse.
 */
private fun String?.toInstantOrNull(): Instant? = this?.let(Instant::parse)

/**
 * Parses a wire "HH:MM" (or "HH:MM:SS") time-of-day to a [LocalTime], or `null` when absent or
 * unparseable — tolerant by design (#348), so a malformed clock degrades to "no time-of-day" rather
 * than crashing the read, matching [com.circuitstitch.deferno.core.network.DefernoJson]'s posture.
 */
internal fun String?.toLocalTimeOrNull(): LocalTime? =
    this?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
