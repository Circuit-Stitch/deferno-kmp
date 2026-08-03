package com.circuitstitch.deferno.core.data.event

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
    recurrence = decodeRecurrence(
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
    ),
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
    // Server-derived dependency flags (#290): NULL (pre-migration / omitted) decodes to false.
    blocked = blocked == 1L,
    isBlocker = is_blocker == 1L,
    // The soft target date + urgency bucket (#375): NULL (pre-migration) decodes to no-target / Normal.
    targetDate = target_date.toInstantOrNull(),
    priority = priority.toPriorityOrDefault(),
)

fun Event.toEntity(): EventEntity {
    val rule = recurrence.encodeColumns()
    return EventEntity(
        id = id.value,
        org_slug = orgSlug,
        owner_org_id = ownerOrgId?.value,
        ref = ref,
        sequence = sequence,
        title = title,
        definition_state = definitionState.name,
        recurrence_type = rule.type,
        recurrence_days = rule.days,
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
        blocked = if (blocked) 1L else 0L,
        is_blocker = if (isBlocker) 1L else 0L,
        target_date = targetDate?.toString(),
        priority = priority.name,
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
