package com.circuitstitch.deferno.core.model

import kotlinx.datetime.LocalDate
import kotlin.time.Instant

/**
 * One entry in the windowed **Calendar feed** (`GET /tasks/calendar`, #74) — the unified row the month
 * grid + day agenda render over. The backend projects three things into this one flat dated shape: a
 * **recurring firing** (an [[Occurrence]] of a Habit/Chore/Event — `seriesId` non-null), a **one-off
 * dated item** (a Task with a `complete_by` — `seriesId` null), and a **synced external event** (Google
 * — `source = External`). The Calendar is a *read projection*; the client acts on a firing by routing a
 * coarse [OccurrenceAction] to the kind-scoped occurrence endpoints (the act path keys on
 * [seriesId] + [date]).
 *
 * **Why [status] is a [WorkingState], not an [OccurrenceState].** The feed reports every row's progress
 * as the wire `TaskStatus`, condensed here to [WorkingState] — even for recurring firings. This is a
 * deliberate gentleness win (design-principle #4): `WorkingState` has **no `Missed`/`late` concept**, so
 * the calendar surface literally cannot shame a past, unfinished firing — it just reads as `Open`
 * (rendered "Scheduled"). The richer [OccurrenceState] punctuality split stays server-side and unread.
 *
 * **Why [kind] is nullable.** The feed carries no kind, so the DTO→domain mapper leaves it `null`; the
 * local store resolves it at read time from a `series_id → kind` index seeded from the Habit/Chore/Event
 * definitions this client knows. A row whose kind can't be resolved (e.g. a definition created on the
 * web that this device hasn't cached) renders **read-only** — gentle degradation, never a wrong write.
 */
data class CalendarItem(
    /** The feed row id (`CalendarEvent.id`) — the local cache primary key. */
    val id: String,
    /** The underlying Deferno item id the row projects from. */
    val taskId: String,
    /** The recurring series/definition id the occurrence endpoints key on; `null` for a one-off dated item. */
    val seriesId: String?,
    val title: String,
    /** The local calendar day this row falls on — [start] projected into the user's time zone. */
    val date: LocalDate,
    /** The firing's start instant (projected to UTC on the wire). */
    val start: Instant,
    /** The firing's end instant. */
    val end: Instant,
    /** Whether the row renders as an all-day chip rather than a timed block. */
    val allDay: Boolean,
    /** Progress, condensed from the feed's `TaskStatus` — never an [OccurrenceState] (see class note). */
    val status: WorkingState,
    /** The recurring kind, resolved from the series→kind index; `null` for a one-off Task or an unknown series. */
    val kind: ItemKind?,
    /** Where the row came from — a Deferno item or a synced external calendar. */
    val source: CalendarSource,
    val labels: List<String> = emptyList(),
) {
    /**
     * A recurring firing this client can **act on** via the occurrence endpoints: it belongs to a series
     * and its [kind] resolved to one of the recurring kinds. A one-off dated Task ([seriesId] `null`) and
     * an unresolved-kind row are both excluded — the agenda offers occurrence actions only here.
     */
    val isActionableOccurrence: Boolean
        get() = seriesId != null && kind != null && kind != ItemKind.Task

    /** A one-off dated item (a Task with a deadline) — rendered in the agenda, acted on via the Task path. */
    val isDatedTask: Boolean get() = seriesId == null && source == CalendarSource.Deferno
}

/**
 * Where a [CalendarItem] originates (the feed's `source`). [External] (e.g. Google) rows are
 * **read-only** in v1 — there is no Deferno write endpoint for them — and [Unknown] is the tolerant
 * fallback so an additive future source degrades gracefully rather than crashing the reader (ADR-0005).
 */
enum class CalendarSource { Deferno, External, Unknown }
