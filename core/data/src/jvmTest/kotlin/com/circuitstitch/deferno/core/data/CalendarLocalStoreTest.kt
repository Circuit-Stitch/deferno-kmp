package com.circuitstitch.deferno.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.turbine.test
import com.circuitstitch.deferno.core.data.calendar.SqlDelightCalendarLocalStore
import com.circuitstitch.deferno.core.database.sql.CalendarItemEntity
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
 * sibling of `OccurrenceFactLocalStoreTest`. (It said `OccurrenceLocalStoreTest` until #390, when that
 * store was replaced by the fact + coverage pair per ADR-0053 decision 4.)
 *
 * It proves the SQL path over a genuine `DefernoDatabase`: the row<->domain round-trip, the half-open
 * `[from, to)` window query, the per-day markers, the `kind` column (it survives the write, and an
 * unrecognised stored token degrades to `null` = read-only), the full-window replace (a vanished row is
 * cleared), and that the agenda re-emits on a window refresh (ADR-0001).
 *
 * What it deliberately does **not** prove is how a firing went: that is a fact keyed
 * `(kind, definitionId, date)` in the sibling store, never a column here.
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
        kind: ItemKind? = null,
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
        kind = kind,
        source = source,
        labels = emptyList(),
    )

    @Test
    fun windowRoundTrips_keepsTheRowsKind_andAgendaReEmitsOnRefresh() = runTest {
        val store = SqlDelightCalendarLocalStore(db(), Dispatchers.Default)

        store.observeByDate(LocalDate(2026, 6, 8)).test {
            assertEquals(emptyList(), awaitItem())
            store.replaceWindow(
                LocalDate(2026, 6, 8), LocalDate(2026, 6, 9),
                listOf(item(kind = ItemKind.Habit)),
            )
            val rows = awaitItem()
            assertEquals(listOf("ce-1"), rows.map { it.id })
            // The kind written with the row comes back with it — the firing stays actionable.
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
    fun aKindlessRowIsReadOnly_andAOneOffTaskIsADatedTask() = runTest {
        val store = SqlDelightCalendarLocalStore(db(), Dispatchers.Default)
        store.upsert(item(id = "no-kind", seriesId = "web-only-def", kind = null))
        store.upsert(item(id = "one-off", seriesId = null))

        // A recurring row the feed gave no usable kind for stays read-only.
        val noKind = store.get("no-kind")
        assertNull(noKind?.kind)
        assertEquals(false, noKind?.isActionableOccurrence)

        // A one-off dated item (no series) is a dated Task, not an actionable occurrence.
        val oneOff = store.get("one-off")
        assertNull(oneOff?.seriesId)
        assertTrue(oneOff?.isDatedTask == true)
    }

    @Test
    fun anUnrecognisedStoredKindTokenDecodesToNullRatherThanThrowing() = runTest {
        // The `working_state` / `source` contract, applied to `kind`: a row written by a newer build (or
        // a hand-edited database) must degrade to a read-only row, never blow up the whole agenda read.
        val db = db()
        val store = SqlDelightCalendarLocalStore(db, Dispatchers.Default)
        db.calendarItemEntityQueries.insertOrReplace(
            CalendarItemEntity(
                id = "from-the-future",
                task_id = "task-1",
                series_id = "hab-3",
                title = "Morning stretch",
                item_date = "2026-06-08",
                start_at = "2026-06-08T09:00:00Z",
                end_at = "2026-06-08T09:15:00Z",
                all_day = 0L,
                working_state = "Open",
                source = "Deferno",
                labels = "",
                kind = "Sasquatch",
            ),
        )

        val row = store.get("from-the-future")
        assertNull(row?.kind)
        assertEquals(false, row?.isActionableOccurrence)
    }
}
