package com.circuitstitch.deferno.core.data.calendar

import com.circuitstitch.deferno.core.model.CalendarItem
import kotlinx.datetime.LocalDate

/**
 * The network port the calendar repository refreshes through (ADR-0001, #74). `GET /tasks/calendar`
 * is the one windowed feed that unifies recurring firings + one-off dated items + synced external
 * events over a half-open `[from, to)` local-date window — so the month grid loads the whole month in
 * a single call rather than fanning out per definition (there is no list-definitions endpoint).
 *
 * Offline-first (ADR-0001): a failed call returns `null` so a failed refresh leaves the cached window
 * untouched rather than blanking the grid. The kind condensation (the feed carries no kind) is resolved
 * separately at the store boundary, so this returns the kind-less [CalendarItem]s as mapped from the wire.
 */
interface CalendarRemoteSource {

    /** The feed rows for the `[from, to)` window in [tz], or `null` on failure (cache untouched). */
    suspend fun fetchWindow(from: LocalDate, to: LocalDate, tz: String): List<CalendarItem>?
}
