package com.circuitstitch.deferno.core.data.habit

import com.circuitstitch.deferno.core.data.recurring.decodeNewlineList
import com.circuitstitch.deferno.core.data.recurring.encodeNewlineList
import com.circuitstitch.deferno.core.data.recurring.toDefinitionStateOrDefault
import com.circuitstitch.deferno.core.data.recurring.toHydrationStateOrDefault
import com.circuitstitch.deferno.core.data.recurring.toInstantOrNull
import com.circuitstitch.deferno.core.data.recurring.toLocalTimeOrNull
import com.circuitstitch.deferno.core.data.recurring.toRecurrenceFrequencyOrDefault
import com.circuitstitch.deferno.core.database.sql.HabitEntity
import com.circuitstitch.deferno.core.model.Habit
import com.circuitstitch.deferno.core.model.HabitId
import com.circuitstitch.deferno.core.model.OrgId
import com.circuitstitch.deferno.core.model.Recurrence
import com.circuitstitch.deferno.core.model.TaskId
import kotlin.time.Instant

/**
 * The row<->domain conversion for the Habit cache (ADR-0001, #71) — sibling of `TaskEntityMapping.kt`.
 * core:database keeps `habitEntity` adapter-free, so the rich-type translation (the [DefinitionState]
 * light switch, the [Recurrence] object, instants, the id value classes) lives here. The [Recurrence]
 * flattens into `recurrence_type` + a `\n`-joined `recurrence_days`; a null `recurrence_type` decodes
 * to a `null` Recurrence (a definition with no rule).
 */
fun HabitEntity.toDomain(): Habit = Habit(
    id = HabitId(id),
    orgSlug = org_slug,
    title = title,
    definitionState = definition_state.toDefinitionStateOrDefault(),
    recurrence = recurrence_type?.let {
        Recurrence(frequency = it.toRecurrenceFrequencyOrDefault(), days = recurrence_days.decodeNewlineList())
    },
    labels = labels.decodeNewlineList(),
    parentId = parent_id?.let(::TaskId),
    completeBy = complete_by.toInstantOrNull(),
    deadlineTimeOfDay = deadline_time_of_day.toLocalTimeOrNull(),
    pinned = pinned != 0L,
    sequence = sequence,
    ref = ref,
    dateCreated = Instant.parse(date_created),
    deletedAt = deleted_at.toInstantOrNull(),
    hydration = hydration_state.toHydrationStateOrDefault(),
    ownerOrgId = owner_org_id?.let(::OrgId),
    description = description,
    seriesId = series_id,
    // Server-derived dependency flags (#290): NULL (pre-migration / omitted) decodes to false.
    blocked = blocked == 1L,
    isBlocker = is_blocker == 1L,
)

fun Habit.toEntity(): HabitEntity = HabitEntity(
    id = id.value,
    org_slug = orgSlug,
    owner_org_id = ownerOrgId?.value,
    ref = ref,
    sequence = sequence,
    title = title,
    definition_state = definitionState.name,
    recurrence_type = recurrence?.frequency?.name,
    recurrence_days = (recurrence?.days ?: emptyList()).encodeNewlineList(),
    labels = labels.encodeNewlineList(),
    parent_id = parentId?.value,
    complete_by = completeBy?.toString(),
    deadline_time_of_day = deadlineTimeOfDay?.toString(),
    pinned = if (pinned) 1L else 0L,
    date_created = dateCreated.toString(),
    deleted_at = deletedAt?.toString(),
    hydration_state = hydration.name,
    description = description,
    series_id = seriesId,
    blocked = if (blocked) 1L else 0L,
    is_blocker = if (isBlocker) 1L else 0L,
)
