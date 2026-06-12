package com.circuitstitch.deferno.core.data.calendar

import app.cash.turbine.test
import com.circuitstitch.deferno.core.data.RemoteSnapshot
import com.circuitstitch.deferno.core.model.CalendarItem
import com.circuitstitch.deferno.core.model.CalendarSource
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.WorkingState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * The offline-first orchestration of the calendar repository (ADR-0001, #74). It proves the refresh
 * contract: a successful pull reseeds the `series_id -> kind` index THEN full-replaces the window (so
 * the agenda re-emits with kinds resolved); a failed pull (`null`) writes nothing (the cached window is
 * never blanked); and [OfflineCalendarRepository.reconcile] replays the last window (the outbox flush
 * hook), a no-op before any window has loaded. The windowing/marker SQL itself is proved against real
 * SQLite in `CalendarLocalStoreTest`.
 */
class OfflineCalendarRepositoryTest {

    private val from = LocalDate(2026, 6, 1)
    private val to = LocalDate(2026, 7, 1)
    private val day = LocalDate(2026, 6, 8)

    private fun item(id: String = "ce-1", seriesId: String? = "hab-3") = CalendarItem(
        id = id,
        taskId = "task-1",
        seriesId = seriesId,
        title = "Morning stretch",
        date = day,
        start = Instant.parse("2026-06-08T09:00:00Z"),
        end = Instant.parse("2026-06-08T09:15:00Z"),
        allDay = false,
        status = WorkingState.Open,
        kind = null,
        source = CalendarSource.Deferno,
    )

    @Test
    fun refreshSeedsTheKindIndexThenReplacesTheWindow_andAgendaResolvesKind() = runTest {
        val store = RecordingCalendarLocalStore()
        val remote = FakeCalendarRemoteSource(result = listOf(item()))
        val repo = OfflineCalendarRepository(store, remote, { mapOf("hab-3" to ItemKind.Habit) })

        repo.observeDay(day).test {
            assertEquals(emptyList(), awaitItem())
            repo.refreshWindow(from, to, "UTC")
            // The agenda re-emits with the firing's kind resolved from the freshly-seeded index.
            assertEquals(ItemKind.Habit, awaitItem().single().kind)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf(mapOf("hab-3" to ItemKind.Habit)), store.replacedIndexes)
        assertEquals(1, store.replacedWindows.size)
        assertEquals(Triple(from, to, listOf("ce-1")), store.replacedWindows.single().let { (f, t, items) -> Triple(f, t, items.map { it.id }) })
    }

    @Test
    fun aFailedPullLeavesTheCacheUntouched() = runTest {
        val store = RecordingCalendarLocalStore()
        val remote = FakeCalendarRemoteSource(result = null) // failure
        val repo = OfflineCalendarRepository(store, remote, { mapOf("hab-3" to ItemKind.Habit) })

        repo.refreshWindow(from, to, "UTC")

        // Nothing written: neither the window nor the index — the cached month is never blanked.
        assertEquals(0, store.replacedWindows.size)
        assertEquals(0, store.replacedIndexes.size)
    }

    @Test
    fun anEmptyServerWindowBlanksTheCachedWindow() = runTest {
        // The bug the explicit RemoteSnapshot fixes: a reachable server with no items in the window
        // (Available, empty) must replace-with-empty — distinct from a failed pull, which writes nothing.
        val store = RecordingCalendarLocalStore()
        val remote = FakeCalendarRemoteSource(result = emptyList()) // reachable, empty window
        val repo = OfflineCalendarRepository(store, remote, { emptyMap() })

        repo.refreshWindow(from, to, "UTC")

        // The window IS replaced (with an empty span), unlike the Unavailable case above.
        assertEquals(1, store.replacedWindows.size)
        assertEquals(0, store.replacedWindows.single().third.size)
    }

    @Test
    fun reconcileReplaysTheLastWindow_andIsANoOpBeforeAnyRefresh() = runTest {
        val store = RecordingCalendarLocalStore()
        val remote = FakeCalendarRemoteSource(result = listOf(item()))
        val repo = OfflineCalendarRepository(store, remote, { emptyMap() })

        // No-op before any window has been refreshed.
        repo.reconcile()
        assertEquals(0, remote.calls.size)

        repo.refreshWindow(from, to, "UTC")
        repo.reconcile()
        // The reconcile re-pulls the exact same window the UI last loaded.
        assertEquals(listOf(Triple(from, to, "UTC"), Triple(from, to, "UTC")), remote.calls)
    }
}

/** A [CalendarRemoteSource] returning a scripted [result] (null = failure) and recording its calls. */
private class FakeCalendarRemoteSource(private val result: List<CalendarItem>?) : CalendarRemoteSource {
    val calls = mutableListOf<Triple<LocalDate, LocalDate, String>>()
    override suspend fun fetchWindow(from: LocalDate, to: LocalDate, tz: String): RemoteSnapshot<List<CalendarItem>> {
        calls += Triple(from, to, tz)
        return result?.let { RemoteSnapshot.Available(it) } ?: RemoteSnapshot.Unavailable
    }
}

/** Records the orchestration writes and serves the day agenda live, resolving kind from the last index. */
private class RecordingCalendarLocalStore : CalendarLocalStore {
    val replacedWindows = mutableListOf<Triple<LocalDate, LocalDate, List<CalendarItem>>>()
    val replacedIndexes = mutableListOf<Map<String, ItemKind>>()
    private var index: Map<String, ItemKind> = emptyMap()
    private val day = MutableStateFlow<List<CalendarItem>>(emptyList())

    override fun observeInRange(from: LocalDate, to: LocalDate): Flow<List<CalendarItem>> = flowOf(emptyList())
    override fun observeByDate(date: LocalDate): Flow<List<CalendarItem>> = day
    override fun observeMarkers(from: LocalDate, to: LocalDate): Flow<Map<LocalDate, Int>> = flowOf(emptyMap())
    override suspend fun get(id: String): CalendarItem? = null
    override suspend fun upsert(item: CalendarItem) {}

    override suspend fun replaceWindow(from: LocalDate, to: LocalDate, items: List<CalendarItem>) {
        replacedWindows += Triple(from, to, items)
        day.value = items.map { it.copy(kind = index[it.seriesId]) }
    }

    override suspend fun replaceSeriesKinds(index: Map<String, ItemKind>) {
        replacedIndexes += index
        this.index = index
    }
}
