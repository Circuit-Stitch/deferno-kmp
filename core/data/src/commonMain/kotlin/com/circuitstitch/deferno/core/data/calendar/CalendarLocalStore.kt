package com.circuitstitch.deferno.core.data.calendar

import com.circuitstitch.deferno.core.model.CalendarItem
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDate

/**
 * The local source-of-truth port for the Calendar feed (ADR-0001, #74) — the windowed, day-indexed
 * sibling of
 * [com.circuitstitch.deferno.core.data.occurrence.OccurrenceFactLocalStore], which is keyed
 * `(kind, definitionId, date)` instead. (It used to read "sibling of `OccurrenceLocalStore`"; that
 * type was replaced in #390 / ADR-0053 decision 4, because its server-UUID primary key could not be
 * joined against anything the client writes.) The repository talks to *this*, never the network: the
 * UI-facing reads are the [observeInRange] / [observeByDate] / [observeMarkers] DB `Flow`s (the month
 * grid + day agenda + per-cell markers), and a window refresh seeds rows through [replaceWindow] so
 * they surface with no manual refresh.
 *
 * A row's recurring `kind` is stored with it (#380), so what goes in through [replaceWindow] / [upsert]
 * is exactly what comes back out — a firing stays actionable for as long as its row survives.
 *
 * **A row says which firings exist, never how one went.** `CalendarItem.status` is the Task axis
 * ([com.circuitstitch.deferno.core.model.WorkingState]); a firing's reading is derived from the fact
 * table plus coverage plus today, and since #390 an offline mark or clear no longer touches a row
 * here at all.
 */
interface CalendarLocalStore {
    /** The feed rows in the half-open `[from, to)` day window, observed as a `Flow`. */
    fun observeInRange(from: LocalDate, to: LocalDate): Flow<List<CalendarItem>>

    /** One calendar day's rows (the day agenda), observed as a `Flow`. */
    fun observeByDate(date: LocalDate): Flow<List<CalendarItem>>

    /** The per-day entry counts in `[from, to)` — the grid's cell markers. */
    fun observeMarkers(from: LocalDate, to: LocalDate): Flow<Map<LocalDate, Int>>

    /** The current row for [id], or `null` — the seam the occurrence writer reads. */
    suspend fun get(id: String): CalendarItem?

    /**
     * Inserts or replaces [item] by its id. The occurrence writer reaches for this on **reschedule
     * only** — the one act that genuinely moves an agenda row to another day. Marking and clearing
     * write their optimism to the fact table instead (#390), so this is no longer "the
     * optimistic-apply seam" it was documented as before ADR-0053.
     */
    suspend fun upsert(item: CalendarItem)

    /** Full-replace the `[from, to)` window: clear the span, then insert [items] (one atomic transaction). */
    suspend fun replaceWindow(from: LocalDate, to: LocalDate, items: List<CalendarItem>)
}
