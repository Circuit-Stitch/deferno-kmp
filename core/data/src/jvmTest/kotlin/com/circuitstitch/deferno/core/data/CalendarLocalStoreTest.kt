package com.circuitstitch.deferno.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.turbine.test
import com.circuitstitch.deferno.core.data.calendar.SqlDelightCalendarLocalStore
import com.circuitstitch.deferno.core.database.sql.DefernoDatabase
import com.circuitstitch.deferno.core.model.CalendarItem
import com.circuitstitch.deferno.core.model.CalendarSource
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.WorkingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Real-SQLite integration for the Calendar feed cache (#74, ADR-0006 JVM-fast path) — the windowed
 * sibling of `OccurrenceLocalStoreTest`. It proves the SQL path over a genuine `DefernoDatabase`: the
 * row<->domain round-trip, the half-open `[from, to)` window query, the per-day markers, the
 * `series_id -> kind` resolution (an unindexed series stays `null` = read-only), the full-window
 * replace (a vanished row is cleared), and that the agenda re-emits on a window refresh (ADR-0001).
 */
class CalendarLocalStoreTest {

    private fun db() = DefernoDatabase(
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { DefernoDatabase.Schema.create(it) },
    )

    private fun item(
        id: String = "ce-1",
        taskId: String = "task-1",
        seriesId: String? = "hab-3",
        title: String = "Morning stretch",
        date: LocalDate = LocalDate(2026, 6, 8),
        status: WorkingState = WorkingState.Open,
        source: CalendarSource = CalendarSource.Deferno,
    ) = CalendarItem(
        id = id,
        taskId = taskId,
        seriesId = seriesId,
        title = title,
        date = date,
        start = Instant.parse("${date}T09:00:00Z"),
        end = Instant.parse("${date}T09:15:00Z"),
        allDay = false,
        status = status,
        kind = null, // the store ignores kind on write; it is resolved from the index on read
        source = source,
        labels = emptyList(),
    )

    @Test
    fun windowRoundTrips_resolvesKindFromIndex_andAgendaReEmitsOnRefresh() = runTest {
        val store = SqlDelightCalendarLocalStore(db(), Dispatchers.Default)
        store.replaceSeriesKinds(mapOf("hab-3" to ItemKind.Habit))

        store.observeByDate(LocalDate(2026, 6, 8)).test {
            assertEquals(emptyList(), awaitItem())
            store.replaceWindow(LocalDate(2026, 6, 8), LocalDate(2026, 6, 9), listOf(item()))
            val rows = awaitItem()
            assertEquals(listOf("ce-1"), rows.map { it.id })
            // Kind resolves from the series index — the row is now an actionable occurrence.
            assertEquals(ItemKind.Habit, rows[0].kind)
            assertTrue(rows[0].isActionableOccurrence)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun selectInRangeIsHalfOpen_andMarkersCountPerDay() = runTest {
        val store = SqlDelightCalendarLocalStore(db(), Dispatchers.Default)
        // June: two entries on the 8th, one on the 9th, one on July 1 (the exclusive window end).
        store.replaceWindow(
            LocalDate(2026, 6, 1), LocalDate(2026, 7, 2),
            listOf(
                item(id = "a", date = LocalDate(2026, 6, 8)),
                item(id = "b", date = LocalDate(2026, 6, 8)),
                item(id = "c", date = LocalDate(2026, 6, 9)),
                item(id = "d", date = LocalDate(2026, 7, 1)),
            ),
        )

        store.observeInRange(LocalDate(2026, 6, 1), LocalDate(2026, 7, 1)).test {
            // Half-open: July 1 (the exclusive end) is excluded.
            assertEquals(listOf("a", "b", "c"), awaitItem().map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
        store.observeMarkers(LocalDate(2026, 6, 1), LocalDate(2026, 7, 1)).test {
            assertEquals(
                mapOf(LocalDate(2026, 6, 8) to 2, LocalDate(2026, 6, 9) to 1),
                awaitItem(),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun replaceWindowClearsVanishedRowsButLeavesOtherWindows() = runTest {
        val store = SqlDelightCalendarLocalStore(db(), Dispatchers.Default)
        store.replaceWindow(
            LocalDate(2026, 6, 1), LocalDate(2026, 7, 1),
            listOf(item(id = "june", date = LocalDate(2026, 6, 8))),
        )
        store.replaceWindow(
            LocalDate(2026, 7, 1), LocalDate(2026, 8, 1),
            listOf(item(id = "july", date = LocalDate(2026, 7, 8))),
        )
        // Re-refresh June as empty (the firing was removed server-side) — June clears, July untouched.
        store.replaceWindow(LocalDate(2026, 6, 1), LocalDate(2026, 7, 1), emptyList())

        assertNull(store.get("june"))
        assertEquals("july", store.get("july")?.id)
    }

    @Test
    fun unindexedSeriesResolvesToNullKind_andOneOffTaskIsReadOnly() = runTest {
        val store = SqlDelightCalendarLocalStore(db(), Dispatchers.Default)
        store.upsert(item(id = "unknown-series", seriesId = "web-only-def"))
        store.upsert(item(id = "one-off", seriesId = null))

        // A recurring row whose series isn't in the index resolves to a null kind — read-only.
        val unknown = store.get("unknown-series")
        assertNull(unknown?.kind)
        assertEquals(false, unknown?.isActionableOccurrence)

        // A one-off dated item (no series) is a dated Task, not an actionable occurrence.
        val oneOff = store.get("one-off")
        assertNull(oneOff?.seriesId)
        assertTrue(oneOff?.isDatedTask == true)
    }
}
