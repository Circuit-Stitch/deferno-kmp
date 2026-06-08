package com.circuitstitch.deferno.core.data.calendar

import com.circuitstitch.deferno.core.model.CalendarItem
import com.circuitstitch.deferno.core.model.ItemKind
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDate

/**
 * The local source-of-truth port for the Calendar feed (ADR-0001, #74) — the windowed sibling of
 * `OccurrenceLocalStore`. The repository talks to *this*, never the network: the UI-facing reads are
 * the [observeInRange] / [observeByDate] / [observeMarkers] DB `Flow`s (the month grid + day agenda +
 * per-cell markers), and a window refresh seeds rows through [replaceWindow] so they surface with no
 * manual refresh.
 *
 * It also owns the **`series_id -> kind` index** (the bridge that makes a feed firing actionable):
 * [replaceSeriesKinds] reseeds it from the known definitions, and the observe reads resolve each row's
 * recurring kind from it (a row with no resolvable kind comes back with `kind = null`, read-only).
 */
interface CalendarLocalStore {
    /** The feed rows in the half-open `[from, to)` day window, kind-resolved, observed as a `Flow`. */
    fun observeInRange(from: LocalDate, to: LocalDate): Flow<List<CalendarItem>>

    /** One calendar day's rows (the day agenda), kind-resolved, observed as a `Flow`. */
    fun observeByDate(date: LocalDate): Flow<List<CalendarItem>>

    /** The per-day entry counts in `[from, to)` — the grid's cell markers. */
    fun observeMarkers(from: LocalDate, to: LocalDate): Flow<Map<LocalDate, Int>>

    /** The current row for [id] (kind-resolved), or `null` — the seam the occurrence writer reads. */
    suspend fun get(id: String): CalendarItem?

    /** Inserts or replaces [item] by its id — the optimistic-apply seam the occurrence writer upserts through. */
    suspend fun upsert(item: CalendarItem)

    /** Full-replace the `[from, to)` window: clear the span, then insert [items] (one atomic transaction). */
    suspend fun replaceWindow(from: LocalDate, to: LocalDate, items: List<CalendarItem>)

    /** Reseed the `series_id -> kind` index (clear + insert) from the definitions this client knows. */
    suspend fun replaceSeriesKinds(index: Map<String, ItemKind>)
}
