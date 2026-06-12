package com.circuitstitch.deferno.core.data.event

import com.circuitstitch.deferno.core.data.recurring.decodeNewlineList
import com.circuitstitch.deferno.core.data.recurring.encodeNewlineList
import com.circuitstitch.deferno.core.data.recurring.toDefinitionStateOrDefault
import com.circuitstitch.deferno.core.data.recurring.toHydrationStateOrDefault
import com.circuitstitch.deferno.core.data.recurring.toInstantOrNull
import com.circuitstitch.deferno.core.data.recurring.toLocalTimeOrNull
import com.circuitstitch.deferno.core.data.recurring.toRecurrenceFrequencyOrDefault
import com.circuitstitch.deferno.core.database.sql.EventEntity
import com.circuitstitch.deferno.core.model.Event
import com.circuitstitch.deferno.core.model.EventId
import com.circuitstitch.deferno.core.model.OrgId
import com.circuitstitch.deferno.core.model.Recurrence
import com.circuitstitch.deferno.core.model.TaskId
import kotlin.time.Instant

/** Row<->domain conversion for the Event cache (ADR-0001, #71) — sibling of `HabitEntityMapping.kt`. */
fun EventEntity.toDomain(): Event = Event(
    id = EventId(id),
    orgSlug = org_slug,
    title = title,
    definitionState = definition_state.toDefinitionStateOrDefault(),
    recurrence = recurrence_type?.let {
        Recurrence(frequency = it.toRecurrenceFrequencyOrDefault(), days = recurrence_days.decodeNewlineList())
    },
    allDay = all_day != 0L,
    completeBy = complete_by.toInstantOrNull(),
    endTime = end_time.toInstantOrNull(),
    startTimeOfDay = start_time_of_day.toLocalTimeOrNull(),
    endTimeOfDay = end_time_of_day.toLocalTimeOrNull(),
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
)

fun Event.toEntity(): EventEntity = EventEntity(
    id = id.value,
    org_slug = orgSlug,
    owner_org_id = ownerOrgId?.value,
    ref = ref,
    sequence = sequence,
    title = title,
    definition_state = definitionState.name,
    recurrence_type = recurrence?.frequency?.name,
    recurrence_days = (recurrence?.days ?: emptyList()).encodeNewlineList(),
    all_day = if (allDay) 1L else 0L,
    complete_by = completeBy?.toString(),
    end_time = endTime?.toString(),
    start_time_of_day = startTimeOfDay?.toString(),
    end_time_of_day = endTimeOfDay?.toString(),
    labels = labels.encodeNewlineList(),
    parent_id = parentId?.value,
    pinned = if (pinned) 1L else 0L,
    date_created = dateCreated.toString(),
    deleted_at = deletedAt?.toString(),
    hydration_state = hydration.name,
    description = description,
    series_id = seriesId,
)
