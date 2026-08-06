package com.circuitstitch.deferno.core.data.backup

import com.circuitstitch.deferno.core.data.attachment.LocalAttachment
import com.circuitstitch.deferno.core.data.recurring.toWireString
import com.circuitstitch.deferno.core.model.Cadence
import com.circuitstitch.deferno.core.model.Chore
import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.Event
import com.circuitstitch.deferno.core.model.Habit
import com.circuitstitch.deferno.core.model.MonthlyAnchor
import com.circuitstitch.deferno.core.model.Recurrence
import com.circuitstitch.deferno.core.model.RecurrenceBound
import com.circuitstitch.deferno.core.model.SeriesInputs
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.WorkingState
import com.circuitstitch.deferno.core.model.wireToken
import com.circuitstitch.deferno.core.network.dto.DefStatusWire
import com.circuitstitch.deferno.core.network.dto.ItemView
import com.circuitstitch.deferno.core.network.dto.LocalAttachmentDto
import com.circuitstitch.deferno.core.network.dto.MonthlyAnchorDto
import com.circuitstitch.deferno.core.network.dto.RecurrenceDto
import com.circuitstitch.deferno.core.network.dto.RecurrenceEndDto
import com.circuitstitch.deferno.core.network.dto.SeriesInputsDto
import com.circuitstitch.deferno.core.network.dto.SeriesOverrideDto
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
    series = series?.toDto(),
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
    series = series?.toDto(),
    // The exact wire token, never the Kotlin variant name: `items.json` IS the API's own snake-case JSON
    // (ADR-0041), so re-casing it — or defaulting an unmodelled mode down to `rolling` — silently
    // rewrites the user's chore on restore. Rolling emits its token explicitly because the server does
    // too (no `skip_serializing_if`); see [CadenceMode.wireToken].
    cadenceMode = cadenceMode.wireToken,
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
    series = series?.toDto(),
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
 * **This also fixes an unreported second-order bug.** The old mapper emitted `type = null` for a rule
 * whose cadence it could not name; with `explicitNulls = false` that serialized to a body with **no
 * `type` key at all**, which `BackupImportMapper` fed straight into `CreateHabitPayload`, and the
 * backend's internally-tagged `Cadence` rejects a body with no tag. So a backup taken of an
 * `every_n_days` or `custom` item (both of which collapsed to "unknown" on read) was not merely lossy —
 * it was **unrestorable**. Now: all six cadences are modelled and each names itself here, a
 * [Cadence.Unmodelled] rule re-emits the raw token it preserved, and the residual "cannot even name it"
 * case (a blank token) returns `null` so the rule is **skipped** rather than exported as an invalid
 * tagless body — the import side then substitutes a named placeholder. Skip on export, placeholder on
 * import; both are covered by tests.
 *
 * Each arm emits only the keys its cadence owns, which is the whole of the domain→wire rule now that
 * the two numeric keys (`every_n_days.n` vs `monthly`/`yearly`'s `interval`) belong to separate
 * variants rather than to one shared multiplier this mapper had to re-route by re-asking which cadence
 * it was looking at.
 */
private fun Recurrence.toDto(): RecurrenceDto? {
    val cadenceDto = when (val cadence = this.cadence) {
        Cadence.Daily -> RecurrenceDto(type = "daily")
        is Cadence.EveryNDays -> RecurrenceDto(type = "every_n_days", n = cadence.n)
        is Cadence.Weekly -> RecurrenceDto(type = "weekly", days = cadence.days)
        is Cadence.Monthly -> RecurrenceDto(
            type = "monthly",
            interval = cadence.interval,
            on = cadence.on?.toDto(),
        )
        is Cadence.Yearly -> RecurrenceDto(
            type = "yearly",
            interval = cadence.interval,
            month = cadence.month,
            day = cadence.day,
        )
        is Cadence.Custom -> RecurrenceDto(type = "custom", rrule = cadence.rrule)
        // The token this client could not model but did preserve on read. Blank means it never had one
        // to preserve — skip the whole rule rather than emit a body the restore would be rejected for.
        is Cadence.Unmodelled ->
            if (cadence.rawType.isBlank()) return null else RecurrenceDto(type = cadence.rawType)
    }
    return cadenceDto.copy(end = bound.toDto())
}

/**
 * [SeriesInputs] → the wire `series` block (#410) — carried faithfully into `items.json` rather than
 * quietly dropped.
 *
 * ADR-0041 makes this mandatory rather than nice-to-have: `items.json` **is** the API's own JSON, so a
 * field the export omits is a field the file claims the item never had. The inputs are the only record
 * of *which wall times* a series fires on — the anchor it was frozen at, the zone it was frozen in, the
 * exceptions taken against it — and none of it is recoverable from the rule plus the cursor. An export
 * that drops them produces a Backup whose recurring items cannot have their grid reproduced.
 *
 * **The restore side cannot replay it, and that asymmetry is the server's, not ours.** No create payload
 * accepts a `series` block; the backend derives the series from `complete_by` + `recurrence`, so a
 * restored item is re-anchored on the day it was restored. Carrying the block anyway keeps the *file*
 * lossless — a reader (or a later importer, once the API grows a way to accept it) can still see the
 * grid the item really had. Exporting nothing would foreclose that permanently.
 */
private fun SeriesInputs.toDto(): SeriesInputsDto = SeriesInputsDto(
    // `toWireString`, never `toString`: kotlinx omits zero seconds and chrono's NaiveDateTime parser
    // requires them, so a minute-precision wall time here is a body the backend rejects outright.
    dtstartLocal = anchorLocal.toWireString(),
    tzid = tzid,
    untilUtc = untilUtc?.toString(),
    exdates = exdates.map { it.toWireString() },
    // `movedToLocal` goes back out under the wire's own overloaded `dtstart_local` key — the rename is
    // a domain-side clarification, not a wire change, so the inverse restores the wire's spelling.
    overrides = overrides.map {
        SeriesOverrideDto(
            recurrenceId = it.recurrenceId.toWireString(),
            isCancelled = it.isCancelled,
            dtstartLocal = it.movedToLocal?.toWireString(),
        )
    },
)

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
