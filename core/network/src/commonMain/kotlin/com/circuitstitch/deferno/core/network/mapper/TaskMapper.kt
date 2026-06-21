package com.circuitstitch.deferno.core.network.mapper

import com.circuitstitch.deferno.core.model.HydrationState
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.OrgId
import com.circuitstitch.deferno.core.model.SearchHit
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
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
)

/**
 * Maps a search summary to a kind-agnostic [SearchHit] (#231). Unlike [toDomain] (which force-fits every
 * summary into a [Task] and drops the wire `type`), this **keeps the kind**: the search endpoint returns
 * items of every [ItemKind] with the `type` discriminant required on each row, so a hit wears its real
 * kind. [isTerminal] folds the wire status through [TaskStatusWire.toWorkingState] (the search shape
 * reports status on the Task axis for all kinds), giving the done/active de-emphasis signal.
 */
fun TaskSummaryDto.toSearchHit(): SearchHit = SearchHit(
    id = id,
    kind = type.toItemKind(),
    title = title,
    isTerminal = status.toWorkingState().isTerminal,
    completeBy = completeBy.toInstantOrNull(),
    deadlineTimeOfDay = deadlineTimeOfDay.toLocalTimeOrNull(),
    ref = ref,
)

/** The wire `type` discriminant (`task`/`habit`/`chore`/`event`) → domain [ItemKind]; unknown ⇒ Task. */
private fun String?.toItemKind(): ItemKind = when (this?.lowercase()) {
    "habit" -> ItemKind.Habit
    "chore" -> ItemKind.Chore
    "event" -> ItemKind.Event
    else -> ItemKind.Task
}

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
    )
    is ItemView.Habit, is ItemView.Chore, is ItemView.Event -> null
}

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
