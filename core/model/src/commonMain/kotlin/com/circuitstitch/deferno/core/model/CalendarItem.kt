package com.circuitstitch.deferno.core.model

import kotlinx.datetime.LocalDate
import kotlin.time.Instant

/**
 * One entry in the windowed **Calendar feed** (`GET /tasks/calendar`, #74) — the unified row the month
 * grid + day agenda render over. The backend projects three things into this one flat dated shape: a
 * **recurring firing** (an [[Occurrence]] of a Habit/Chore/Event — `seriesId` non-null), a **one-off
 * dated item** (a Task with a `complete_by` — `seriesId` null), and a **synced external event** (Google
 * — `source = External`). The Calendar is a *read projection*; the client acts on a firing by routing a
 * coarse [OccurrenceAction] to the kind-scoped occurrence endpoints, which key on **[taskId] + [date]**
 * — [seriesId] identifies *which* series the firing belongs to, but is never a path key (#380).
 *
 * **Why [status] is a [WorkingState], not an [OccurrenceState].** The feed reports every row's progress
 * as the wire `TaskStatus`, condensed here to [WorkingState] — even for recurring firings. This is a
 * deliberate gentleness win (design-principle #4): `WorkingState` has **no `Missed`/`late` concept**, so
 * the calendar surface literally cannot shame a past, unfinished firing — it just reads as `Open`
 * (rendered "Scheduled"). The richer [OccurrenceState] punctuality split stays server-side and unread.
 *
 * **Why [kind] is nullable.** It arrives on the feed row (required since #311) and condenses through the
 * DTO mapper, but tolerantly: an additive kind token we do not recognise degrades to `null` rather than
 * being guessed. It is also not a *persisted* column, so a row read back out of the cache has its kind
 * threaded in from the `series_id → kind` index instead — and a row cached before the feed carried a
 * kind, from a series this device has never seen, resolves to `null`. Either way an unresolved-kind row
 * renders **read-only** — gentle degradation, never a wrong write.
 */
data class CalendarItem(
    /** The feed row id (`CalendarEvent.id`) — the local cache primary key. */
    val id: String,
    /**
     * The underlying Deferno item id the row projects from — the chain Head, and **the id the
     * occurrence endpoints address** (`POST /habits/{taskId}/occurrences`, #380).
     */
    val taskId: String,
    /**
     * The recurring series this firing belongs to; `null` for a one-off dated item or an external row.
     * Its job is *identity*, not addressing: it says "this row is a firing" and keys the local
     * `series_id → kind` index. It is never a path segment — see [taskId].
     */
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
     * A recurring firing this client can **act on** via the occurrence endpoints: it is a Deferno-owned
     * row, it belongs to a series, and its [kind] resolved to one of the recurring kinds. A one-off
     * dated Task ([seriesId] `null`), an unresolved-kind row, and anything synced from outside Deferno
     * are all excluded — the agenda offers occurrence actions only here.
     *
     * The [source] clause is the backend's own instruction ("clients gate actionability on `source`, not
     * `kind`"): an external event is stored as an Event-*kind* item, so once [kind] arrives on the wire
     * (#311) `kind` alone stops being a safe gate. Today external rows also carry no series id, so this
     * is belt-and-braces — which is the point: the day the provider's recurrence is expanded, a Google
     * row must not sprout a Done chip that posts to `/events/{id}/occurrences`.
     */
    val isActionableOccurrence: Boolean
        get() = source == CalendarSource.Deferno && seriesId != null && kind != null && kind != ItemKind.Task

    /** A one-off dated item (a Task with a deadline) — rendered in the agenda, acted on via the Task path. */
    val isDatedTask: Boolean get() = seriesId == null && source == CalendarSource.Deferno
}

/**
 * Where a [CalendarItem] originates (the feed's `source`). [External] (e.g. Google) rows are
 * **read-only** in v1 — there is no Deferno write endpoint for them — and [Unknown] is the tolerant
 * fallback so an additive future source degrades gracefully rather than crashing the reader (ADR-0005).
 */
enum class CalendarSource { Deferno, External, Unknown }
