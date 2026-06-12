package com.circuitstitch.deferno.core.data.calendar

import com.circuitstitch.deferno.core.data.RemoteSnapshot
import com.circuitstitch.deferno.core.model.CalendarItem
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDate

/**
 * The Calendar read repository the feature layer depends on (ADR-0001, #74) — the windowed sibling of
 * `PlanRepository`. **Reads are local DB `Flow`s only** (the month markers + the day agenda), so a
 * refreshed window surfaces with no manual refresh (ADR-0001 observe-via-Flow). [refreshWindow] is the
 * one network seam; [reconcile] re-pulls the last-loaded window after an offline write flushes.
 */
interface CalendarRepository {

    /** The per-day entry counts for the half-open `[from, to)` window — the month grid's cell markers. */
    fun observeMarkers(from: LocalDate, to: LocalDate): Flow<Map<LocalDate, Int>>

    /** One calendar day's agenda — its Occurrences + dated items, kind-resolved. */
    fun observeDay(date: LocalDate): Flow<List<CalendarItem>>

    /** Pull the `[from, to)` window from the feed in [tz] and full-replace the cached span (ADR-0001). */
    suspend fun refreshWindow(from: LocalDate, to: LocalDate, tz: String)

    /** Re-pull the most recently refreshed window — the outbox reconcile hook after a successful flush. */
    suspend fun reconcile()
}

/**
 * The offline-first [CalendarRepository] (ADR-0001, #74): the local store is the single source of truth,
 * the feed only refreshes it. A refresh fetches the feed FIRST and writes nothing on failure (the cached
 * window stays intact, never blanked); on success it reseeds the `series_id -> kind` index from the
 * known definitions and full-replaces the window so the grid + agenda re-emit. [reconcile] replays the
 * last window so a freshly-flushed occurrence write reconciles against server truth (LWW).
 */
class OfflineCalendarRepository(
    private val localStore: CalendarLocalStore,
    private val remoteSource: CalendarRemoteSource,
    private val seriesKindSource: SeriesKindSource,
) : CalendarRepository {

    private var lastWindow: Window? = null

    override fun observeMarkers(from: LocalDate, to: LocalDate): Flow<Map<LocalDate, Int>> =
        localStore.observeMarkers(from, to)

    override fun observeDay(date: LocalDate): Flow<List<CalendarItem>> = localStore.observeByDate(date)

    override suspend fun refreshWindow(from: LocalDate, to: LocalDate, tz: String) {
        lastWindow = Window(from, to, tz)
        // Fetch first: an Unavailable pull writes nothing, so the cached window is never blanked
        // (ADR-0001); an Available (possibly empty) window is written through — an empty span blanks it.
        val items = when (val result = remoteSource.fetchWindow(from, to, tz)) {
            is RemoteSnapshot.Available -> result.value
            RemoteSnapshot.Unavailable -> return
        }
        localStore.replaceSeriesKinds(seriesKindSource.currentSeriesKinds())
        localStore.replaceWindow(from, to, items)
    }

    override suspend fun reconcile() {
        val window = lastWindow ?: return
        refreshWindow(window.from, window.to, window.tz)
    }

    private data class Window(val from: LocalDate, val to: LocalDate, val tz: String)
}
