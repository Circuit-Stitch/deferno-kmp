package com.circuitstitch.deferno.core.data.backup

import com.circuitstitch.deferno.core.data.attachment.LocalAttachment
import com.circuitstitch.deferno.core.model.Chore
import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.Event
import com.circuitstitch.deferno.core.model.Habit
import com.circuitstitch.deferno.core.model.Recurrence
import com.circuitstitch.deferno.core.model.RecurrenceFrequency
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.WorkingState
import com.circuitstitch.deferno.core.network.dto.DefStatusWire
import com.circuitstitch.deferno.core.network.dto.ItemView
import com.circuitstitch.deferno.core.network.dto.LocalAttachmentDto
import com.circuitstitch.deferno.core.network.dto.RecurrenceDto
import com.circuitstitch.deferno.core.network.dto.TaskStatusWire

/**
 * The **outbound** domain→DTO mapping for on-device export (#313, ADR-0041) — the inverse of the
 * read-side `core:network/mapper/{TaskMapper,RecurringItemMapper}.kt`. It re-emits a clean `core:model`
 * item as the `/items` wire shape ([ItemView]), so the exported `items.json` carries the API's own
 * snake-case DTOs (compatible-by-construction). Only the fields the **local DB actually holds** are
 * mapped: server-derived state never persisted offline (`descendant_*`, `blocked`/`is_blocker`,
 * `blocked_by`) is left at its DTO default and omitted, and `external` provenance is excluded entirely
 * (those rows are filtered out before reaching here). Timestamps/clock-times round-trip via
 * `Instant.toString()` / `LocalTime.toString()`, which the read mapper parses back.
 */
internal fun Task.toItemView(): ItemView.Task = ItemView.Task(
    id = id.value,
    orgSlug = orgSlug,
    ownerOrgId = ownerOrgId?.value,
    ref = ref,
    sequence = sequence,
    title = title,
    status = workingState.toWire(),
    labels = labels,
    parentId = parentId?.value,
    children = children.map { it.value },
    completeBy = completeBy?.toString(),
    deadlineTimeOfDay = deadlineTimeOfDay?.toString(),
    productive = productive,
    desire = desire,
    pinned = pinned,
    dateCreated = dateCreated.toString(),
    finishedAt = finishedAt?.toString(),
    deletedAt = deletedAt?.toString(),
    description = description,
    nextTaskId = nextTaskId?.value,
)

internal fun Habit.toItemView(): ItemView.Habit = ItemView.Habit(
    id = id.value,
    orgSlug = orgSlug,
    ownerOrgId = ownerOrgId?.value,
    ref = ref,
    sequence = sequence,
    title = title,
    status = definitionState.toWire(),
    labels = labels,
    parentId = parentId?.value,
    completeBy = completeBy?.toString(),
    deadlineTimeOfDay = deadlineTimeOfDay?.toString(),
    pinned = pinned,
    dateCreated = dateCreated.toString(),
    deletedAt = deletedAt?.toString(),
    description = description,
    recurrence = recurrence?.toDto(),
    seriesId = seriesId,
)

internal fun Chore.toItemView(): ItemView.Chore = ItemView.Chore(
    id = id.value,
    orgSlug = orgSlug,
    ownerOrgId = ownerOrgId?.value,
    ref = ref,
    sequence = sequence,
    title = title,
    status = definitionState.toWire(),
    labels = labels,
    parentId = parentId?.value,
    completeBy = completeBy?.toString(),
    deadlineTimeOfDay = deadlineTimeOfDay?.toString(),
    pinned = pinned,
    dateCreated = dateCreated.toString(),
    deletedAt = deletedAt?.toString(),
    description = description,
    recurrence = recurrence?.toDto(),
    seriesId = seriesId,
    cadenceMode = cadenceMode,
)

internal fun Event.toItemView(): ItemView.Event = ItemView.Event(
    id = id.value,
    orgSlug = orgSlug,
    ownerOrgId = ownerOrgId?.value,
    ref = ref,
    sequence = sequence,
    title = title,
    status = definitionState.toWire(),
    labels = labels,
    parentId = parentId?.value,
    completeBy = completeBy?.toString(),
    pinned = pinned,
    dateCreated = dateCreated.toString(),
    deletedAt = deletedAt?.toString(),
    description = description,
    recurrence = recurrence?.toDto(),
    seriesId = seriesId,
    allDay = allDay,
    endTime = endTime?.toString(),
    startTimeOfDay = startTimeOfDay?.toString(),
    endTimeOfDay = endTimeOfDay?.toString(),
)

/**
 * An on-device [LocalAttachment] → the [LocalAttachmentDto] nested under its owning Task in a Backup file
 * (#315, ADR-0041). Only the fields that round-trip through export→import are carried: `provider`/`locator`/
 * `taskId` are re-derived on restore (provider = on-device, locator = id, taskId = the owning item), and a
 * device-local attachment has no `url`/`created_by`. The raw bytes go into the zip at `attachments/<id>`.
 */
internal fun LocalAttachment.toDto(): LocalAttachmentDto = LocalAttachmentDto(
    id = id,
    filename = filename,
    mime = mime,
    size = size,
    caption = caption,
    createdAt = createdAt.toString(),
)

/** [WorkingState] → wire `TaskStatus` enum — the write-side inverse of `TaskStatusWire.toWorkingState()`. */
private fun WorkingState.toWire(): TaskStatusWire = when (this) {
    WorkingState.Open -> TaskStatusWire.Open
    WorkingState.InProgress -> TaskStatusWire.InProgress
    WorkingState.InReview -> TaskStatusWire.InReview
    WorkingState.Done -> TaskStatusWire.Done
    WorkingState.Dropped -> TaskStatusWire.Dropped
}

/** [DefinitionState] → wire `DefStatus` enum — the inverse of `DefStatusWire.toDefinitionState()`. */
private fun DefinitionState.toWire(): DefStatusWire = when (this) {
    DefinitionState.Active -> DefStatusWire.Active
    DefinitionState.InReview -> DefStatusWire.InReview
    DefinitionState.Archived -> DefStatusWire.Archived
}

/** [Recurrence] → wire `recurrence` object; an [RecurrenceFrequency.Unknown] rule carries no `type`. */
private fun Recurrence.toDto(): RecurrenceDto = RecurrenceDto(
    type = when (frequency) {
        RecurrenceFrequency.Daily -> "daily"
        RecurrenceFrequency.Weekly -> "weekly"
        RecurrenceFrequency.Monthly -> "monthly"
        RecurrenceFrequency.Yearly -> "yearly"
        RecurrenceFrequency.Unknown -> null
    },
    days = days,
)
