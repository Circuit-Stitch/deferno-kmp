package com.circuitstitch.deferno.core.data.calendar

import com.circuitstitch.deferno.core.data.recurring.decodeNewlineList
import com.circuitstitch.deferno.core.data.recurring.encodeNewlineList
import com.circuitstitch.deferno.core.database.sql.CalendarItemEntity
import com.circuitstitch.deferno.core.model.CalendarItem
import com.circuitstitch.deferno.core.model.CalendarSource
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.WorkingState
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

/**
 * The row<->domain conversion for the Calendar feed cache (ADR-0001, #74) — the windowed-feed sibling
 * of `OccurrenceEntityMapping.kt`. core:database keeps `calendarItemEntity` adapter-free, so the
 * rich-type translation (the [LocalDate]/[Instant], the [WorkingState]/[CalendarSource]/[ItemKind]
 * enums, the `\n`-joined labels) lives here. Every enum token decodes **defensively** (an unrecognised
 * stored token degrades rather than throwing), matching the other caches' codecs — for `kind` that
 * degradation is `null`, which renders the row read-only rather than routing a wrong write.
 */
fun CalendarItemEntity.toDomain(): CalendarItem = CalendarItem(
    id = id,
    taskId = task_id,
    seriesId = series_id,
    title = title,
    date = LocalDate.parse(item_date),
    start = Instant.parse(start_at),
    end = Instant.parse(end_at),
    allDay = all_day != 0L,
    status = working_state.toWorkingStateOrDefault(),
    kind = kind?.toItemKindOrNull(),
    source = source.toCalendarSourceOrDefault(),
    labels = labels.decodeNewlineList(),
)

fun CalendarItem.toEntity(): CalendarItemEntity = CalendarItemEntity(
    id = id,
    task_id = taskId,
    series_id = seriesId,
    title = title,
    item_date = date.toString(),
    start_at = start.toString(),
    end_at = end.toString(),
    all_day = if (allDay) 1L else 0L,
    working_state = status.name,
    source = source.name,
    labels = labels.encodeNewlineList(),
    kind = kind?.name,
)

/** Defensive decode: an unrecognised stored token degrades to [WorkingState.Open] (never throws). */
internal fun String.toWorkingStateOrDefault(): WorkingState =
    WorkingState.entries.firstOrNull { it.name == this } ?: WorkingState.Open

/** Defensive decode: an unrecognised stored token degrades to [CalendarSource.Unknown]. */
internal fun String.toCalendarSourceOrDefault(): CalendarSource =
    CalendarSource.entries.firstOrNull { it.name == this } ?: CalendarSource.Unknown

/** Defensive decode of the stored `kind` token; an unrecognised one is `null` -> the row is read-only. */
internal fun String.toItemKindOrNull(): ItemKind? =
    ItemKind.entries.firstOrNull { it.name == this }
