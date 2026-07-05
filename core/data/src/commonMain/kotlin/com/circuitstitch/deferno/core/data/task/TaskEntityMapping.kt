package com.circuitstitch.deferno.core.data.task

import com.circuitstitch.deferno.core.database.sql.TaskEntity
import com.circuitstitch.deferno.core.model.BlockedByRef
import com.circuitstitch.deferno.core.model.ExternalRef
import com.circuitstitch.deferno.core.model.HydrationState
import com.circuitstitch.deferno.core.model.ItemSource
import com.circuitstitch.deferno.core.model.OrgId
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
import kotlinx.datetime.LocalTime
import kotlin.time.Instant

/**
 * The row<->domain conversion for the Task cache (ADR-0001, #22). core:database deliberately keeps
 * `taskEntity` adapter-free — every column is a SQL primitive (TEXT/INTEGER/REAL) — and pushes the
 * conversion to the domain `Task`'s rich types up here (ADR-0011: the schema is modelled from the
 * domain, but the translation lives in the data layer). This is the load-bearing seam the whole
 * reconcile/hydration path rides on: it must be a faithful, total round-trip, because a lossy field
 * would silently corrupt the local source of truth.
 *
 * Encoding rules (kept symmetric with the `.sq` column types):
 * - `labels`/`children` <-> a `\n`-joined TEXT. Labels never contain newlines, so the join is
 *   lossless; an empty column decodes to `emptyList()` (rather than a one-element `[""]`).
 * - `pinned: Boolean` <-> `INTEGER` (true -> 1, false -> 0; `!= 0L` to decode).
 * - the `WorkingState`/`HydrationState` enums <-> their `.name`, decoded **defensively**: an
 *   unrecognised stored token degrades to the safe default (`Open` / `Summary`) instead of throwing,
 *   so a forward-additive enum value written by a newer build can never crash an older reader.
 * - the `Instant?` timestamps <-> RFC3339 strings via `Instant.toString()` / `Instant.parse(...)`;
 *   `dateCreated` is the one non-null timestamp.
 * - the id value classes (`TaskId`/`OrgId`) <-> their backing `String`.
 */

/** Decodes a stored `taskEntity` row into the domain [Task]. Defensive on the enum columns. */
fun TaskEntity.toDomain(): Task = Task(
    id = TaskId(id),
    orgSlug = org_slug,
    title = title,
    workingState = working_state.toWorkingStateOrDefault(),
    labels = labels.decodeNewlineList(),
    parentId = parent_id?.let(::TaskId),
    children = child_ids.decodeNewlineList().map(::TaskId),
    completeBy = complete_by.toInstantOrNull(),
    deadlineTimeOfDay = deadline_time_of_day.toLocalTimeOrNull(),
    productive = productive,
    desire = desire,
    pinned = pinned != 0L,
    sequence = sequence,
    ref = ref,
    dateCreated = Instant.parse(date_created),
    finishedAt = finished_at.toInstantOrNull(),
    deletedAt = deleted_at.toInstantOrNull(),
    hydration = hydration_state.toHydrationStateOrDefault(),
    ownerOrgId = owner_org_id?.let(::OrgId),
    description = description,
    nextTaskId = next_task_id?.let(::TaskId),
    descendantDone = descendant_done,
    descendantTotal = descendant_total,
    // Server-derived dependency flags (#290): NULL (pre-migration / omitted) decodes to false.
    blocked = blocked == 1L,
    isBlocker = is_blocker == 1L,
    // The ordered blockedBy edge list (#291): NULL (pre-migration) decodes to empty.
    blockedBy = blocked_by.decodeBlockedBy(),
    external = decodeExternalRef(external_source, external_id, external_url),
    // Attachment rollup (#311): NULL (pre-migration / omitted) decodes to 0.
    attachmentCount = (attachment_count ?: 0L).toInt(),
    attachmentTotalSize = attachment_total_size ?: 0L,
)

/** Encodes a domain [Task] into a `taskEntity` row, ready for `insertOrReplace`. */
fun Task.toEntity(): TaskEntity = TaskEntity(
    id = id.value,
    org_slug = orgSlug,
    owner_org_id = ownerOrgId?.value,
    ref = ref,
    sequence = sequence,
    title = title,
    working_state = workingState.name,
    labels = labels.encodeNewlineList(),
    parent_id = parentId?.value,
    child_ids = children.map { it.value }.encodeNewlineList(),
    complete_by = completeBy?.toString(),
    deadline_time_of_day = deadlineTimeOfDay?.toString(),
    productive = productive,
    desire = desire,
    pinned = if (pinned) 1L else 0L,
    date_created = dateCreated.toString(),
    finished_at = finishedAt?.toString(),
    deleted_at = deletedAt?.toString(),
    hydration_state = hydration.name,
    description = description,
    next_task_id = nextTaskId?.value,
    descendant_done = descendantDone,
    descendant_total = descendantTotal,
    blocked = if (blocked) 1L else 0L,
    is_blocker = if (isBlocker) 1L else 0L,
    external_source = external?.source?.name,
    external_id = external?.id,
    external_url = external?.url,
    attachment_count = attachmentCount.toLong(),
    attachment_total_size = attachmentTotalSize,
    blocked_by = blockedBy.encodeBlockedBy(),
)

/**
 * Reassembles the domain [ExternalRef] from its three stored columns. `external_source` is the
 * [ItemSource] enum name; a null (native item) or an unrecognised token degrades to `null` — defensive,
 * matching the enum columns above, so a forward-additive source written by a newer build never crashes an
 * older reader. `external_id` is required when a source is present (a row with one but not the other is
 * malformed and read as native).
 */
private fun decodeExternalRef(source: String?, id: String?, url: String?): ExternalRef? {
    val itemSource = source?.let { s -> ItemSource.entries.firstOrNull { it.name == s } } ?: return null
    val refId = id ?: return null
    return ExternalRef(source = itemSource, id = refId, url = url)
}

/**
 * The blockedBy edge list (#291) <-> a `\n`-joined TEXT of `item` or `item|occurrence` entries.
 * UUIDs and occurrence dates contain neither separator, so the join is lossless; `[]` <-> `""`/NULL.
 */
private fun List<BlockedByRef>.encodeBlockedBy(): String =
    joinToString("\n") { ref -> if (ref.occurrence == null) ref.item else "${ref.item}|${ref.occurrence}" }

private fun String?.decodeBlockedBy(): List<BlockedByRef> =
    if (isNullOrEmpty()) {
        emptyList()
    } else {
        split("\n").map { entry ->
            val bar = entry.indexOf('|')
            if (bar < 0) BlockedByRef(entry) else BlockedByRef(entry.take(bar), entry.substring(bar + 1))
        }
    }

/** `[]` -> `""`, else the elements joined with `\n` (the list columns never contain newlines). */
private fun List<String>.encodeNewlineList(): String = joinToString("\n")

/** `""` -> `[]` (not `[""]`), else the `\n`-split elements. */
private fun String.decodeNewlineList(): List<String> =
    if (isEmpty()) emptyList() else split("\n")

/** Parses a stored RFC3339 timestamp, or `null` when the column is null. */
private fun String?.toInstantOrNull(): Instant? = this?.let(Instant::parse)

/** Parses a stored "HH:MM[:SS]" time-of-day, or `null` when absent/unparseable (#348, defensive). */
private fun String?.toLocalTimeOrNull(): LocalTime? =
    this?.let { runCatching { LocalTime.parse(it) }.getOrNull() }

/** Defensive decode: an unrecognised stored token degrades to [WorkingState.Open] (never throws). */
private fun String.toWorkingStateOrDefault(): WorkingState =
    WorkingState.entries.firstOrNull { it.name == this } ?: WorkingState.Open

/** Defensive decode: an unrecognised stored token degrades to [HydrationState.Summary]. */
private fun String.toHydrationStateOrDefault(): HydrationState =
    HydrationState.entries.firstOrNull { it.name == this } ?: HydrationState.Summary
