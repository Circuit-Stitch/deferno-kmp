package com.circuitstitch.deferno.core.network.mapper

import com.circuitstitch.deferno.core.model.Chore
import com.circuitstitch.deferno.core.model.ChoreId
import com.circuitstitch.deferno.core.model.Event
import com.circuitstitch.deferno.core.model.EventId
import com.circuitstitch.deferno.core.model.Habit
import com.circuitstitch.deferno.core.model.HabitId
import com.circuitstitch.deferno.core.model.HydrationState
import com.circuitstitch.deferno.core.model.OrgId
import com.circuitstitch.deferno.core.model.Recurrence
import com.circuitstitch.deferno.core.model.RecurrenceFrequency
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.network.dto.ChoreDetailDto
import com.circuitstitch.deferno.core.network.dto.EventDetailDto
import com.circuitstitch.deferno.core.network.dto.HabitDetailDto
import com.circuitstitch.deferno.core.network.dto.ItemView
import com.circuitstitch.deferno.core.network.dto.RecurrenceDto
import kotlin.time.Instant

/**
 * The DTO→domain mapping for the recurring kinds — Habit / Chore / Event (ADR-0011 "condense at the
 * edge", #71), the sibling of `TaskMapper.kt`. The wire ugliness (string ids/timestamps, the
 * overloaded `DefStatus`, the loosely-typed `recurrence`) stays in `core:network`; everything above
 * sees only the clean `core:model` definitions, each governed by [com.circuitstitch.deferno.core.model.DefinitionState]
 * (the "light switch") — never confused with a [com.circuitstitch.deferno.core.model.WorkingState].
 *
 * The full single-item DTOs ([HabitDetailDto]/…) and the `/items` [ItemView] variants share the same
 * fields, so each maps to a [HydrationState.Full] domain row; the heterogeneous-`/items` extractors
 * ([asHabitOrNull]/[asChoreOrNull]/[asEventOrNull]) return `null` for the non-matching kinds.
 */

/**
 * The wire `recurrence` object → domain [Recurrence]. The loosely-typed wire `type` condenses to a
 * [RecurrenceFrequency]; an unmodelled/absent token degrades to [RecurrenceFrequency.Unknown] (the
 * row stays usable). `null` DTO → `null` domain (a non-recurring item carries no rule).
 */
fun RecurrenceDto?.toDomain(): Recurrence? = this?.let {
    Recurrence(frequency = type.toRecurrenceFrequency(), days = days)
}

/** `recurrence.type` token → [RecurrenceFrequency]; unknown/absent degrades to [RecurrenceFrequency.Unknown]. */
private fun String?.toRecurrenceFrequency(): RecurrenceFrequency = when (this) {
    "daily" -> RecurrenceFrequency.Daily
    "weekly" -> RecurrenceFrequency.Weekly
    "monthly" -> RecurrenceFrequency.Monthly
    "yearly" -> RecurrenceFrequency.Yearly
    else -> RecurrenceFrequency.Unknown
}

// --- Habit ---

fun HabitDetailDto.toDomain(): Habit = Habit(
    id = HabitId(id),
    orgSlug = orgSlug,
    title = title,
    definitionState = status.toDefinitionState(),
    recurrence = recurrence.toDomain(),
    labels = labels,
    parentId = parentId?.let(::TaskId),
    completeBy = completeBy.toInstantOrNull(),
    deadlineTimeOfDay = deadlineTimeOfDay.toLocalTimeOrNull(),
    pinned = pinned,
    sequence = sequence,
    ref = ref,
    dateCreated = Instant.parse(dateCreated),
    deletedAt = deletedAt.toInstantOrNull(),
    hydration = HydrationState.Full,
    ownerOrgId = ownerOrgId?.let(::OrgId),
    description = description,
    seriesId = seriesId,
    blocked = blocked,
    isBlocker = isBlocker,
)

/** Extracts a domain [Habit] from the `habit` variant of an [ItemView]; `null` for the other kinds. */
fun ItemView.asHabitOrNull(): Habit? = (this as? ItemView.Habit)?.let { v ->
    Habit(
        id = HabitId(v.id),
        orgSlug = v.orgSlug,
        title = v.title,
        definitionState = v.status.toDefinitionState(),
        recurrence = v.recurrence.toDomain(),
        labels = v.labels,
        parentId = v.parentId?.let(::TaskId),
        completeBy = v.completeBy.toInstantOrNull(),
        deadlineTimeOfDay = v.deadlineTimeOfDay.toLocalTimeOrNull(),
        pinned = v.pinned,
        sequence = v.sequence,
        ref = v.ref,
        dateCreated = Instant.parse(v.dateCreated),
        deletedAt = v.deletedAt.toInstantOrNull(),
        hydration = HydrationState.Full,
        ownerOrgId = v.ownerOrgId?.let(::OrgId),
        description = v.description,
        seriesId = v.seriesId,
        blocked = v.blocked,
        isBlocker = v.isBlocker,
    )
}

// --- Chore ---

fun ChoreDetailDto.toDomain(): Chore = Chore(
    id = ChoreId(id),
    orgSlug = orgSlug,
    title = title,
    definitionState = status.toDefinitionState(),
    recurrence = recurrence.toDomain(),
    cadenceMode = cadenceMode,
    labels = labels,
    parentId = parentId?.let(::TaskId),
    completeBy = completeBy.toInstantOrNull(),
    deadlineTimeOfDay = deadlineTimeOfDay.toLocalTimeOrNull(),
    pinned = pinned,
    sequence = sequence,
    ref = ref,
    dateCreated = Instant.parse(dateCreated),
    deletedAt = deletedAt.toInstantOrNull(),
    hydration = HydrationState.Full,
    ownerOrgId = ownerOrgId?.let(::OrgId),
    description = description,
    seriesId = seriesId,
    blocked = blocked,
    isBlocker = isBlocker,
)

/** Extracts a domain [Chore] from the `chore` variant of an [ItemView]; `null` for the other kinds. */
fun ItemView.asChoreOrNull(): Chore? = (this as? ItemView.Chore)?.let { v ->
    Chore(
        id = ChoreId(v.id),
        orgSlug = v.orgSlug,
        title = v.title,
        definitionState = v.status.toDefinitionState(),
        recurrence = v.recurrence.toDomain(),
        cadenceMode = v.cadenceMode,
        labels = v.labels,
        parentId = v.parentId?.let(::TaskId),
        completeBy = v.completeBy.toInstantOrNull(),
        deadlineTimeOfDay = v.deadlineTimeOfDay.toLocalTimeOrNull(),
        pinned = v.pinned,
        sequence = v.sequence,
        ref = v.ref,
        dateCreated = Instant.parse(v.dateCreated),
        deletedAt = v.deletedAt.toInstantOrNull(),
        hydration = HydrationState.Full,
        ownerOrgId = v.ownerOrgId?.let(::OrgId),
        description = v.description,
        seriesId = v.seriesId,
        blocked = v.blocked,
        isBlocker = v.isBlocker,
    )
}

// --- Event ---

fun EventDetailDto.toDomain(): Event = Event(
    id = EventId(id),
    orgSlug = orgSlug,
    title = title,
    definitionState = status.toDefinitionState(),
    recurrence = recurrence.toDomain(),
    allDay = allDay,
    completeBy = completeBy.toInstantOrNull(),
    endTime = endTime.toInstantOrNull(),
    startTimeOfDay = startTimeOfDay.toLocalTimeOrNull(),
    endTimeOfDay = endTimeOfDay.toLocalTimeOrNull(),
    labels = labels,
    parentId = parentId?.let(::TaskId),
    pinned = pinned,
    sequence = sequence,
    ref = ref,
    dateCreated = Instant.parse(dateCreated),
    deletedAt = deletedAt.toInstantOrNull(),
    hydration = HydrationState.Full,
    ownerOrgId = ownerOrgId?.let(::OrgId),
    description = description,
    seriesId = seriesId,
    blocked = blocked,
    isBlocker = isBlocker,
)

/** Extracts a domain [Event] from the `event` variant of an [ItemView]; `null` for the other kinds. */
fun ItemView.asEventOrNull(): Event? = (this as? ItemView.Event)?.let { v ->
    Event(
        id = EventId(v.id),
        orgSlug = v.orgSlug,
        title = v.title,
        definitionState = v.status.toDefinitionState(),
        recurrence = v.recurrence.toDomain(),
        allDay = v.allDay,
        completeBy = v.completeBy.toInstantOrNull(),
        endTime = v.endTime.toInstantOrNull(),
        startTimeOfDay = v.startTimeOfDay.toLocalTimeOrNull(),
        endTimeOfDay = v.endTimeOfDay.toLocalTimeOrNull(),
        labels = v.labels,
        parentId = v.parentId?.let(::TaskId),
        pinned = v.pinned,
        sequence = v.sequence,
        ref = v.ref,
        dateCreated = Instant.parse(v.dateCreated),
        deletedAt = v.deletedAt.toInstantOrNull(),
        hydration = HydrationState.Full,
        ownerOrgId = v.ownerOrgId?.let(::OrgId),
        description = v.description,
        seriesId = v.seriesId,
        blocked = v.blocked,
        isBlocker = v.isBlocker,
    )
}

/** Parses an RFC3339 timestamp string to an [Instant], or `null` when absent (mirrors TaskMapper). */
private fun String?.toInstantOrNull(): Instant? = this?.let(Instant::parse)
