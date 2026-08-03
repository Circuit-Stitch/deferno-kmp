package com.circuitstitch.deferno.core.data.backup

import com.circuitstitch.deferno.core.data.attachment.LocalAttachment
import com.circuitstitch.deferno.core.model.Chore
import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.Event
import com.circuitstitch.deferno.core.model.Habit
import com.circuitstitch.deferno.core.model.MonthlyAnchor
import com.circuitstitch.deferno.core.model.Recurrence
import com.circuitstitch.deferno.core.model.RecurrenceBound
import com.circuitstitch.deferno.core.model.RecurrenceFrequency
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.WorkingState
import com.circuitstitch.deferno.core.network.dto.DefStatusWire
import com.circuitstitch.deferno.core.network.dto.ItemView
import com.circuitstitch.deferno.core.network.dto.LocalAttachmentDto
import com.circuitstitch.deferno.core.network.dto.MonthlyAnchorDto
import com.circuitstitch.deferno.core.network.dto.RecurrenceDto
import com.circuitstitch.deferno.core.network.dto.RecurrenceEndDto
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

/**
 * [Recurrence] → the flat wire `recurrence` object — the true inverse of `RecurringItemMapper`'s read
 * side, emitting every cadence parameter and the `end` bound rather than just `type` + `days` (#382).
 *
 * **This also fixes an unreported second-order bug.** The old mapper emitted `type = null` for an
 * [RecurrenceFrequency.Unknown] rule; with `explicitNulls = false` that serialized to a body with **no
 * `type` key at all**, which `BackupImportMapper` fed straight into `CreateHabitPayload`, and the
 * backend's internally-tagged `Cadence` rejects a body with no tag. So a backup taken of an
 * `every_n_days` or `custom` item (both of which collapsed to `Unknown` on read) was not merely lossy —
 * it was **unrestorable**. Now: all six cadences are modelled and name themselves, an Unknown rule
 * re-emits the raw token it preserved, and the residual "cannot even name it" case returns `null` so
 * the rule is **skipped** rather than exported as an invalid tagless body — the import side then
 * substitutes a named placeholder. Skip on export, placeholder on import; both are covered by tests.
 */
private fun Recurrence.toDto(): RecurrenceDto? {
    val token = when (frequency) {
        RecurrenceFrequency.Daily -> "daily"
        RecurrenceFrequency.EveryNDays -> "every_n_days"
        RecurrenceFrequency.Weekly -> "weekly"
        RecurrenceFrequency.Monthly -> "monthly"
        RecurrenceFrequency.Yearly -> "yearly"
        RecurrenceFrequency.Custom -> "custom"
        // The token this client could not model but did preserve on read (Recurrence.rawType).
        RecurrenceFrequency.Unknown -> rawType
    } ?: return null
    return RecurrenceDto(
        type = token,
        days = days,
        // The domain condenses the wire's two numeric keys into one cycle multiplier; the frequency
        // decides which key it goes back out under. They can never co-occur on the wire.
        n = interval.takeIf { frequency == RecurrenceFrequency.EveryNDays },
        interval = interval.takeIf {
            frequency == RecurrenceFrequency.Monthly || frequency == RecurrenceFrequency.Yearly
        },
        on = monthlyAnchor?.toDto(),
        month = month,
        day = day,
        rrule = rrule,
        end = bound.toDto(),
    )
}

/** [MonthlyAnchor] → the nested wire `recurrence.on` object. */
private fun MonthlyAnchor.toDto(): MonthlyAnchorDto = when (this) {
    is MonthlyAnchor.DayOfMonth -> MonthlyAnchorDto(type = "day_of_month", day = day)
    is MonthlyAnchor.NthWeekday -> MonthlyAnchorDto(type = "nth_weekday", nth = nth, weekday = weekday)
}

/**
 * [RecurrenceBound] → the nested wire `recurrence.end` object. [RecurrenceBound.Never] emits **no `end`
 * key**, mirroring the server's own `Serialize` (which skips the key when the bound is never) — absent
 * is the canonical encoding, and an explicit `{"type":"never"}` is a shape the server never produces.
 */
private fun RecurrenceBound.toDto(): RecurrenceEndDto? = when (this) {
    RecurrenceBound.Never -> null
    is RecurrenceBound.OnDate -> RecurrenceEndDto(type = "on_date", date = date.toString())
    is RecurrenceBound.AfterCount -> RecurrenceEndDto(type = "after_count", n = n)
}
