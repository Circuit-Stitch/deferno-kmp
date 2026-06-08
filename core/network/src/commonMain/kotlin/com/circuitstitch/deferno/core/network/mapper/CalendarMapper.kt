package com.circuitstitch.deferno.core.network.mapper

import com.circuitstitch.deferno.core.model.CalendarItem
import com.circuitstitch.deferno.core.model.CalendarSource
import com.circuitstitch.deferno.core.network.dto.CalendarEventDto
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * The DTO→domain mapping for a calendar feed row (ADR-0011 "condense at the edge", #74) — the
 * windowed-feed sibling of [com.circuitstitch.deferno.core.network.dto.OccurrenceDto]'s `toDomain`.
 * The wire ugliness (string instants, the overloaded `TaskStatus`, the free-string `source`) stays in
 * `core:network`; the domain [CalendarItem] is clean.
 *
 * Two projections happen here:
 * - **Status condenses** through the existing [TaskStatusWire.toWorkingState] — the feed reports
 *   progress on the Task axis even for recurring firings, and condensing to [com.circuitstitch.deferno.core.model.WorkingState]
 *   (which has no `missed`/`late`) is what keeps the calendar structurally non-shaming (design-principle #4).
 * - **The local day** is [CalendarEventDto.start] projected into [tz] — the day the row buckets onto in
 *   the month grid + agenda (the wire ships UTC instants; the grid is a local-day view).
 *
 * [CalendarItem.kind] is left `null`: the feed carries no kind, so the recurring kind is resolved later
 * at the store boundary from the `series_id → kind` index (#74). [source] condenses `"deferno"` →
 * [CalendarSource.Deferno], `"google_calendar"` → [CalendarSource.External], anything else →
 * [CalendarSource.Unknown] (tolerant — an additive future source degrades, ADR-0005).
 */
fun CalendarEventDto.toDomain(tz: TimeZone): CalendarItem {
    val startInstant = Instant.parse(start)
    return CalendarItem(
        id = id,
        taskId = taskId,
        seriesId = seriesId,
        title = title,
        date = startInstant.toLocalDateTime(tz).date,
        start = startInstant,
        end = Instant.parse(end),
        allDay = allDay,
        status = status.toWorkingState(),
        kind = null,
        source = source.toCalendarSource(),
        labels = labels,
    )
}

/** Condense the feed's free-string `source` to the domain [CalendarSource] (tolerant fallback). */
fun String.toCalendarSource(): CalendarSource = when (this) {
    "deferno" -> CalendarSource.Deferno
    "google_calendar" -> CalendarSource.External
    else -> CalendarSource.Unknown
}
