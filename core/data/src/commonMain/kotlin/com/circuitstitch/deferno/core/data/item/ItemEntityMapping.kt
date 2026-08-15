package com.circuitstitch.deferno.core.data.item

import com.circuitstitch.deferno.core.data.recurring.RecurrenceColumns
import com.circuitstitch.deferno.core.data.recurring.decodeNewlineList
import com.circuitstitch.deferno.core.data.recurring.decodeRecurrence
import com.circuitstitch.deferno.core.data.recurring.encodeColumns
import com.circuitstitch.deferno.core.data.recurring.encodeNewlineList
import com.circuitstitch.deferno.core.data.recurring.toDefinitionStateOrDefault
import com.circuitstitch.deferno.core.data.recurring.toHydrationStateOrDefault
import com.circuitstitch.deferno.core.data.recurring.toInstantOrNull
import com.circuitstitch.deferno.core.data.recurring.toLocalTimeOrNull
import com.circuitstitch.deferno.core.data.recurring.toPriorityOrDefault
import com.circuitstitch.deferno.core.database.sql.ItemEntity
import com.circuitstitch.deferno.core.model.BlockedByRef
import com.circuitstitch.deferno.core.model.Chore
import com.circuitstitch.deferno.core.model.ChoreId
import com.circuitstitch.deferno.core.model.Event
import com.circuitstitch.deferno.core.model.EventId
import com.circuitstitch.deferno.core.model.ExternalRef
import com.circuitstitch.deferno.core.model.Habit
import com.circuitstitch.deferno.core.model.HabitId
import com.circuitstitch.deferno.core.model.HydrationState
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.ItemSource
import com.circuitstitch.deferno.core.model.OrgId
import com.circuitstitch.deferno.core.model.Priority
import com.circuitstitch.deferno.core.model.Recurrence
import com.circuitstitch.deferno.core.model.SeriesInputs
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
import com.circuitstitch.deferno.core.model.cadenceModeFromWire
import com.circuitstitch.deferno.core.model.recipe.KindRow
import com.circuitstitch.deferno.core.model.wireToken
import kotlin.time.Instant

/**
 * The row conversion for the one item cache (ADR-0001, ADR-0055, #422). It replaces the four
 * `{Task,Habit,Chore,Event}EntityMapping.kt` files, which held the same encoding rules four times.
 *
 * It converts between a stored `itemEntity` row and a [KindRow] — one of the four rows the wire still
 * speaks. The plugin-shaped record the store hands out is one more step, through `KindRecipe`, and
 * [com.circuitstitch.deferno.core.data.item.SqlDelightItemLocalStore] takes it. Splitting the two keeps
 * each translation gated by its own round trip: the recipe's by `KindRecipeRoundTripTest`, this one by
 * `ItemEntityMappingTest`.
 *
 * `core:database` keeps `itemEntity` adapter-free — every column is a SQL primitive — and pushes the
 * conversion to the domain's rich types up here (ADR-0011). This is the load-bearing seam the whole
 * reconcile and hydration path rides on. It has to be a faithful, total round trip, because a lossy
 * field would silently corrupt the local source of truth.
 *
 * Encoding rules, kept symmetric with the `.sq` column types:
 *
 * - `labels`, `child_ids` and a series' `exdates` are `\n`-joined TEXT. None of those values contains a
 *   newline, so the join is lossless, and an empty column decodes to `emptyList()` rather than `[""]`.
 * - `blocked_by` is a `\n`-joined list of `item` or `item|occurrence` entries. UUIDs and occurrence
 *   dates contain neither separator.
 * - Booleans are INTEGER: `true` writes 1, and anything non-zero decodes to `true`.
 * - Enums store their `.name` and decode **defensively**. An unrecognised stored token degrades to the
 *   safe default rather than throwing, so a forward-additive value written by a newer build can never
 *   crash an older reader. `cadence_mode` is the exception: it stores the WIRE token, because every row
 *   an earlier build cached holds the literal `rolling`/`fixed` it read off the wire.
 * - Timestamps are RFC3339 via `Instant.toString()` and `Instant.parse`. `date_created` is the one
 *   non-null timestamp.
 * - The id value classes unwrap to their backing `String`.
 *
 * **A column a kind does not have is NULL on that kind's rows.** Each encode starts from [sharedRow],
 * which puts every kind-specific column at NULL or its default, and then copies in only the columns its
 * own kind owns. That is what keeps a Chore's `cadence_mode` off a Habit's row and makes the decode
 * unambiguous, the same rule the fourteen recurrence columns already follow.
 */

// ── Read: a stored row becomes the wire row its `kind` column names ────────────────────────────────

/**
 * Decodes a stored row into whichever of the four wire rows its `kind` column names, or `null` when it
 * names none of them.
 *
 * `null` rather than a degraded default, which is the one place this file does not follow its own
 * defensive-decode rule. There is no safe kind to fall back to: reading a Habit as a Task would give it
 * a working state it has never had and drop the rule that makes it a series. So an unreadable row is
 * treated the way an absent `seriesInputsEntity` row is treated — this build cannot read it — and the
 * store drops it from the list rather than rendering a fiction.
 *
 * [series] is the expansion inputs from `seriesInputsEntity`, which the caller reads separately because
 * they are unbounded lists. `null` means this device cannot reproduce that grid, never a grid with no
 * exclusions. It is ignored for a Task, which has no series.
 */
internal fun ItemEntity.toKindRow(series: SeriesInputs? = null): KindRow? = when (kind) {
    ItemKind.Task.name -> KindRow.OfTask(toTask())
    ItemKind.Habit.name -> KindRow.OfHabit(toHabit(series))
    ItemKind.Chore.name -> KindRow.OfChore(toChore(series))
    ItemKind.Event.name -> KindRow.OfEvent(toEvent(series))
    else -> null
}

private fun ItemEntity.toTask(): Task = Task(
    id = TaskId(id),
    orgSlug = org_slug,
    title = title,
    workingState = working_state.toWorkingStateOrDefault(),
    labels = labels.decodeNewlineList(),
    parentId = parent_id?.let(::TaskId),
    children = child_ids.decodeNewlineList().map(::TaskId),
    completeBy = complete_by.toInstantOrNull(),
    deadlineTimeOfDay = deadline_time_of_day.toLocalTimeOrNull(),
    targetDate = target_date.toInstantOrNull(),
    priority = priority.toPriorityOrDefault(),
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
    blocked = blocked == 1L,
    isBlocker = is_blocker == 1L,
    blockedBy = blocked_by.decodeBlockedBy(),
    external = decodeExternalRef(external_source, external_id, external_url),
    attachmentCount = (attachment_count ?: 0L).toInt(),
    attachmentTotalSize = attachment_total_size ?: 0L,
)

private fun ItemEntity.toHabit(series: SeriesInputs?): Habit = Habit(
    id = HabitId(id),
    orgSlug = org_slug,
    title = title,
    definitionState = definition_state.toDefinitionStateOrDefault(),
    recurrence = decodeRule(),
    labels = labels.decodeNewlineList(),
    parentId = parent_id?.let(::TaskId),
    completeBy = complete_by.toInstantOrNull(),
    deadlineTimeOfDay = deadline_time_of_day.toLocalTimeOrNull(),
    targetDate = target_date.toInstantOrNull(),
    priority = priority.toPriorityOrDefault(),
    pinned = pinned != 0L,
    sequence = sequence,
    ref = ref,
    dateCreated = Instant.parse(date_created),
    deletedAt = deleted_at.toInstantOrNull(),
    hydration = hydration_state.toHydrationStateOrDefault(),
    ownerOrgId = owner_org_id?.let(::OrgId),
    description = description,
    seriesId = series_id,
    series = series,
    blocked = blocked == 1L,
    isBlocker = is_blocker == 1L,
)

private fun ItemEntity.toChore(series: SeriesInputs?): Chore = Chore(
    id = ChoreId(id),
    orgSlug = org_slug,
    title = title,
    definitionState = definition_state.toDefinitionStateOrDefault(),
    recurrence = decodeRule(),
    // A NULL column is not "unknown": it is the pre-#401 default every client-created chore still
    // holds, and it decodes to Rolling exactly as an absent wire token does.
    cadenceMode = cadenceModeFromWire(cadence_mode),
    labels = labels.decodeNewlineList(),
    parentId = parent_id?.let(::TaskId),
    completeBy = complete_by.toInstantOrNull(),
    deadlineTimeOfDay = deadline_time_of_day.toLocalTimeOrNull(),
    targetDate = target_date.toInstantOrNull(),
    priority = priority.toPriorityOrDefault(),
    pinned = pinned != 0L,
    sequence = sequence,
    ref = ref,
    dateCreated = Instant.parse(date_created),
    deletedAt = deleted_at.toInstantOrNull(),
    hydration = hydration_state.toHydrationStateOrDefault(),
    ownerOrgId = owner_org_id?.let(::OrgId),
    description = description,
    seriesId = series_id,
    series = series,
    blocked = blocked == 1L,
    isBlocker = is_blocker == 1L,
)

private fun ItemEntity.toEvent(series: SeriesInputs?): Event = Event(
    id = EventId(id),
    orgSlug = org_slug,
    title = title,
    definitionState = definition_state.toDefinitionStateOrDefault(),
    recurrence = decodeRule(),
    allDay = all_day != 0L,
    completeBy = complete_by.toInstantOrNull(),
    endTime = end_time.toInstantOrNull(),
    startTimeOfDay = start_time_of_day.toLocalTimeOrNull(),
    endTimeOfDay = end_time_of_day.toLocalTimeOrNull(),
    targetDate = target_date.toInstantOrNull(),
    priority = priority.toPriorityOrDefault(),
    labels = labels.decodeNewlineList(),
    parentId = parent_id?.let(::TaskId),
    pinned = pinned != 0L,
    sequence = sequence,
    ref = ref,
    dateCreated = Instant.parse(date_created),
    deletedAt = deleted_at.toInstantOrNull(),
    hydration = hydration_state.toHydrationStateOrDefault(),
    ownerOrgId = owner_org_id?.let(::OrgId),
    description = description,
    seriesId = series_id,
    series = series,
    blocked = blocked == 1L,
    isBlocker = is_blocker == 1L,
)

/** The fourteen flat recurrence columns as a domain rule. A NULL `recurrence_type` is no rule at all. */
private fun ItemEntity.decodeRule(): Recurrence? = decodeRecurrence(
    RecurrenceColumns(
        type = recurrence_type,
        days = recurrence_days,
        interval = recurrence_interval,
        anchorType = recurrence_anchor_type,
        anchorDay = recurrence_anchor_day,
        anchorNth = recurrence_anchor_nth,
        anchorWeekday = recurrence_anchor_weekday,
        month = recurrence_month,
        day = recurrence_day,
        rrule = recurrence_rrule,
        endType = recurrence_end_type,
        endDate = recurrence_end_date,
        endCount = recurrence_end_count,
        rawType = recurrence_raw_type,
    ),
)

// ── Write: a wire row becomes a stored row ─────────────────────────────────────────────────────────

/** Encodes whichever of the four wire rows this is, ready for `insertOrReplace`. */
internal fun KindRow.toEntity(): ItemEntity = when (this) {
    is KindRow.OfTask -> task.toEntity()
    is KindRow.OfHabit -> habit.toEntity()
    is KindRow.OfChore -> chore.toEntity()
    is KindRow.OfEvent -> event.toEntity()
}

private fun Task.toEntity(): ItemEntity = sharedRow(
    id = id.value,
    kind = ItemKind.Task,
    orgSlug = orgSlug,
    ownerOrgId = ownerOrgId,
    ref = ref,
    sequence = sequence,
    title = title,
    parentId = parentId?.value,
    dateCreated = dateCreated,
    deletedAt = deletedAt,
    hydration = hydration,
    description = description,
    labels = labels,
    completeBy = completeBy,
    targetDate = targetDate,
    priority = priority,
    pinned = pinned,
    blocked = blocked,
    isBlocker = isBlocker,
).copy(
    deadline_time_of_day = deadlineTimeOfDay?.toString(),
    working_state = workingState.name,
    finished_at = finishedAt?.toString(),
    child_ids = children.map { it.value }.encodeNewlineList(),
    descendant_done = descendantDone,
    descendant_total = descendantTotal,
    blocked_by = blockedBy.encodeBlockedBy(),
    next_task_id = nextTaskId?.value,
    productive = productive,
    desire = desire,
    external_source = external?.source?.name,
    external_id = external?.id,
    external_url = external?.url,
    attachment_count = attachmentCount.toLong(),
    attachment_total_size = attachmentTotalSize,
)

private fun Habit.toEntity(): ItemEntity = sharedRow(
    id = id.value,
    kind = ItemKind.Habit,
    orgSlug = orgSlug,
    ownerOrgId = ownerOrgId,
    ref = ref,
    sequence = sequence,
    title = title,
    parentId = parentId?.value,
    dateCreated = dateCreated,
    deletedAt = deletedAt,
    hydration = hydration,
    description = description,
    labels = labels,
    completeBy = completeBy,
    targetDate = targetDate,
    priority = priority,
    pinned = pinned,
    blocked = blocked,
    isBlocker = isBlocker,
).copy(
    deadline_time_of_day = deadlineTimeOfDay?.toString(),
).withRule(recurrence, definitionState.name, seriesId)

private fun Chore.toEntity(): ItemEntity = sharedRow(
    id = id.value,
    kind = ItemKind.Chore,
    orgSlug = orgSlug,
    ownerOrgId = ownerOrgId,
    ref = ref,
    sequence = sequence,
    title = title,
    parentId = parentId?.value,
    dateCreated = dateCreated,
    deletedAt = deletedAt,
    hydration = hydration,
    description = description,
    labels = labels,
    completeBy = completeBy,
    targetDate = targetDate,
    priority = priority,
    pinned = pinned,
    blocked = blocked,
    isBlocker = isBlocker,
).copy(
    deadline_time_of_day = deadlineTimeOfDay?.toString(),
    // A PERSISTED FORMAT, not a display string, and the same warning the recurrence tokens carry: every
    // row an earlier build cached holds the literal `rolling`/`fixed`/NULL it read off the wire, so this
    // emits the wire token and never the Kotlin variant name.
    cadence_mode = cadenceMode.wireToken,
).withRule(recurrence, definitionState.name, seriesId)

private fun Event.toEntity(): ItemEntity = sharedRow(
    id = id.value,
    kind = ItemKind.Event,
    orgSlug = orgSlug,
    ownerOrgId = ownerOrgId,
    ref = ref,
    sequence = sequence,
    title = title,
    parentId = parentId?.value,
    dateCreated = dateCreated,
    deletedAt = deletedAt,
    hydration = hydration,
    description = description,
    labels = labels,
    completeBy = completeBy,
    targetDate = targetDate,
    priority = priority,
    pinned = pinned,
    blocked = blocked,
    isBlocker = isBlocker,
).copy(
    all_day = if (allDay) 1L else 0L,
    end_time = endTime?.toString(),
    start_time_of_day = startTimeOfDay?.toString(),
    end_time_of_day = endTimeOfDay?.toString(),
).withRule(recurrence, definitionState.name, seriesId)

/**
 * A row carrying only what all four kinds declare identically, with every kind-specific column at NULL
 * or its column default. Each kind's encode copies its own columns in on top.
 *
 * The eighteen fields here are the duplication the re-cut exists to remove: each was declared on four
 * tables and mapped by four near-identical files.
 */
@Suppress("LongParameterList")
private fun sharedRow(
    id: String,
    kind: ItemKind,
    orgSlug: String,
    ownerOrgId: OrgId?,
    ref: String?,
    sequence: Long?,
    title: String,
    parentId: String?,
    dateCreated: Instant,
    deletedAt: Instant?,
    hydration: HydrationState,
    description: String?,
    labels: List<String>,
    completeBy: Instant?,
    targetDate: Instant?,
    priority: Priority,
    pinned: Boolean,
    blocked: Boolean,
    isBlocker: Boolean,
): ItemEntity = ItemEntity(
    id = id,
    kind = kind.name,
    org_slug = orgSlug,
    owner_org_id = ownerOrgId?.value,
    ref = ref,
    sequence = sequence,
    title = title,
    parent_id = parentId,
    date_created = dateCreated.toString(),
    deleted_at = deletedAt?.toString(),
    hydration_state = hydration.name,
    description = description,
    labels = labels.encodeNewlineList(),
    complete_by = completeBy?.toString(),
    target_date = targetDate?.toString(),
    priority = priority.name,
    pinned = if (pinned) 1L else 0L,
    blocked = if (blocked) 1L else 0L,
    is_blocker = if (isBlocker) 1L else 0L,
    deadline_time_of_day = null,
    working_state = null,
    finished_at = null,
    child_ids = "",
    descendant_done = null,
    descendant_total = null,
    blocked_by = null,
    next_task_id = null,
    productive = null,
    desire = null,
    external_source = null,
    external_id = null,
    external_url = null,
    attachment_count = null,
    attachment_total_size = null,
    definition_state = null,
    series_id = null,
    recurrence_type = null,
    recurrence_days = "",
    recurrence_interval = null,
    recurrence_anchor_type = null,
    recurrence_anchor_day = null,
    recurrence_anchor_nth = null,
    recurrence_anchor_weekday = null,
    recurrence_month = null,
    recurrence_day = null,
    recurrence_rrule = null,
    recurrence_end_type = null,
    recurrence_end_date = null,
    recurrence_end_count = null,
    recurrence_raw_type = null,
    cadence_mode = null,
    all_day = 0L,
    end_time = null,
    start_time_of_day = null,
    end_time_of_day = null,
)

/** The recurring columns the three recurring kinds share: the light switch, the series name, the rule. */
private fun ItemEntity.withRule(
    recurrence: Recurrence?,
    definitionState: String,
    seriesId: String?,
): ItemEntity {
    val rule = recurrence.encodeColumns()
    return copy(
        definition_state = definitionState,
        series_id = seriesId,
        recurrence_type = rule.type,
        recurrence_days = rule.days,
        recurrence_interval = rule.interval,
        recurrence_anchor_type = rule.anchorType,
        recurrence_anchor_day = rule.anchorDay,
        recurrence_anchor_nth = rule.anchorNth,
        recurrence_anchor_weekday = rule.anchorWeekday,
        recurrence_month = rule.month,
        recurrence_day = rule.day,
        recurrence_rrule = rule.rrule,
        recurrence_end_type = rule.endType,
        recurrence_end_date = rule.endDate,
        recurrence_end_count = rule.endCount,
        recurrence_raw_type = rule.rawType,
    )
}

// ── The Task-only encodings ────────────────────────────────────────────────────────────────────────

/**
 * Reassembles the domain [ExternalRef] from its three stored columns. `external_source` is the
 * [ItemSource] enum name; a null (a native item) or an unrecognised token degrades to `null`, matching
 * the enum columns above. `external_id` is required when a source is present, so a row holding one but
 * not the other is malformed and reads as native.
 */
private fun decodeExternalRef(source: String?, id: String?, url: String?): ExternalRef? {
    val itemSource = source?.let { s -> ItemSource.entries.firstOrNull { it.name == s } } ?: return null
    val refId = id ?: return null
    return ExternalRef(source = itemSource, id = refId, url = url)
}

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

/**
 * Defensive decode of the stored working state. A NULL column is what every recurring row holds, and it
 * reaches this function only on a Task, where an unrecognised token degrades to [WorkingState.Open] —
 * what a Task row with no status has always decoded to.
 */
private fun String?.toWorkingStateOrDefault(): WorkingState =
    WorkingState.entries.firstOrNull { it.name == this } ?: WorkingState.Open
