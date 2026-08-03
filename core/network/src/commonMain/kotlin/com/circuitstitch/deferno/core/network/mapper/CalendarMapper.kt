package com.circuitstitch.deferno.core.network.mapper

import com.circuitstitch.deferno.core.model.CalendarItem
import com.circuitstitch.deferno.core.model.CalendarSource
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.network.dto.CalendarEventDto
import com.circuitstitch.deferno.core.network.dto.ItemKindWire
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
 * - **Kind arrives on the wire** (#311/#380): [CalendarEventDto.kind] condenses through
 *   [toItemKindOrNull], so a firing is routable straight from the feed row. An unrecognised token
 *   degrades to `null` — the row renders read-only rather than routing a write to a guessed endpoint.
 *   The store still threads a kind in from the `series_id → kind` index for rows cached before the
 *   feed carried one; `kind` is not a persisted column, so the repository seeds that index *from these
 *   rows* on refresh (`OfflineCalendarRepository.refreshWindow`).
 *
 * [source] condenses `"deferno"` → [CalendarSource.Deferno], `"google_calendar"` →
 * [CalendarSource.External], anything else → [CalendarSource.Unknown] (tolerant — an additive future
 * source degrades, ADR-0005).
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
        kind = kind.toItemKindOrNull(),
        source = source.toCalendarSource(),
        labels = labels,
    )
}

/**
 * Condense the feed's wire `ItemKind` to the domain [ItemKind], or `null` (#380). Unlike the status
 * condensations in `StatusMapper.kt`, this degrades to **absent** rather than to a safe member: the
 * value routes a kind-scoped *write* (`/habits/…` vs `/chores/…`), so guessing a kind we do not
 * recognise would post to the wrong endpoint. `null` makes the row read-only — gentle degradation,
 * never a wrong write (ADR-0005). It lives beside [toCalendarSource] because [ItemKindWire] is a
 * feed-row concern; the item-read DTOs key off their own `type` discriminator instead.
 */
fun ItemKindWire.toItemKindOrNull(): ItemKind? = when (this) {
    ItemKindWire.Task -> ItemKind.Task
    ItemKindWire.Habit -> ItemKind.Habit
    ItemKindWire.Chore -> ItemKind.Chore
    ItemKindWire.Event -> ItemKind.Event
    ItemKindWire.Unknown -> null
}

/** Condense the feed's free-string `source` to the domain [CalendarSource] (tolerant fallback). */
fun String.toCalendarSource(): CalendarSource = when (this) {
    "deferno" -> CalendarSource.Deferno
    "google_calendar" -> CalendarSource.External
    else -> CalendarSource.Unknown
}
