package com.circuitstitch.deferno.core.data.chore

import com.circuitstitch.deferno.core.data.recurring.decodeNewlineList
import com.circuitstitch.deferno.core.data.recurring.encodeNewlineList
import com.circuitstitch.deferno.core.data.recurring.toDefinitionStateOrDefault
import com.circuitstitch.deferno.core.data.recurring.toHydrationStateOrDefault
import com.circuitstitch.deferno.core.data.recurring.toInstantOrNull
import com.circuitstitch.deferno.core.data.recurring.toLocalTimeOrNull
import com.circuitstitch.deferno.core.data.recurring.toRecurrenceFrequencyOrDefault
import com.circuitstitch.deferno.core.database.sql.ChoreEntity
import com.circuitstitch.deferno.core.model.Chore
import com.circuitstitch.deferno.core.model.ChoreId
import com.circuitstitch.deferno.core.model.OrgId
import com.circuitstitch.deferno.core.model.Recurrence
import com.circuitstitch.deferno.core.model.TaskId
import kotlin.time.Instant

/** Row<->domain conversion for the Chore cache (ADR-0001, #71) — sibling of `HabitEntityMapping.kt`. */
fun ChoreEntity.toDomain(): Chore = Chore(
    id = ChoreId(id),
    orgSlug = org_slug,
    title = title,
    definitionState = definition_state.toDefinitionStateOrDefault(),
    recurrence = recurrence_type?.let {
        Recurrence(frequency = it.toRecurrenceFrequencyOrDefault(), days = recurrence_days.decodeNewlineList())
    },
    cadenceMode = cadence_mode,
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
)

fun Chore.toEntity(): ChoreEntity = ChoreEntity(
    id = id.value,
    org_slug = orgSlug,
    owner_org_id = ownerOrgId?.value,
    ref = ref,
    sequence = sequence,
    title = title,
    definition_state = definitionState.name,
    recurrence_type = recurrence?.frequency?.name,
    recurrence_days = (recurrence?.days ?: emptyList()).encodeNewlineList(),
    cadence_mode = cadenceMode,
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
)
