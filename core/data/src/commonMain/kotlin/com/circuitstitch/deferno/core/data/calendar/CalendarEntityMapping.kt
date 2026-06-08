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
 * rich-type translation (the [LocalDate]/[Instant], the [WorkingState]/[CalendarSource] enums, the
 * `\n`-joined labels) lives here. The recurring [kind] is **not** a stored column — it is resolved at
 * read time from the `series_id -> kind` index and threaded into [toDomain] (`null` = unresolved, so
 * the row renders read-only). Enum tokens decode **defensively** (an unrecognised token degrades rather
 * than throwing), matching the other caches' codecs.
 */
fun CalendarItemEntity.toDomain(kind: ItemKind?): CalendarItem = CalendarItem(
    id = id,
    taskId = task_id,
    seriesId = series_id,
    title = title,
    date = LocalDate.parse(item_date),
    start = Instant.parse(start_at),
    end = Instant.parse(end_at),
    allDay = all_day != 0L,
    status = working_state.toWorkingStateOrDefault(),
    kind = kind,
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
)

/** Defensive decode: an unrecognised stored token degrades to [WorkingState.Open] (never throws). */
internal fun String.toWorkingStateOrDefault(): WorkingState =
    WorkingState.entries.firstOrNull { it.name == this } ?: WorkingState.Open

/** Defensive decode: an unrecognised stored token degrades to [CalendarSource.Unknown]. */
internal fun String.toCalendarSourceOrDefault(): CalendarSource =
    CalendarSource.entries.firstOrNull { it.name == this } ?: CalendarSource.Unknown

/** Resolve a stored series-kind token to a recurring [ItemKind], or `null` (unknown -> read-only row). */
internal fun String.toItemKindOrNull(): ItemKind? =
    ItemKind.entries.firstOrNull { it.name == this }
