package com.circuitstitch.deferno.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.turbine.test
import com.circuitstitch.deferno.core.data.occurrence.SqlDelightOccurrenceCoverageLocalStore
import com.circuitstitch.deferno.core.database.sql.DefernoDatabase
import com.circuitstitch.deferno.core.database.sql.OccurrenceCoverageEntity
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.OccurrenceCoverage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Real-SQLite integration for the [Occurrence coverage] store (#390, ADR-0053 decision 4, ADR-0006
 * JVM-fast path).
 *
 * The headline case is [aGapBetweenTwoSyncedWindowsIsNeverSwallowed]. Coverage exists so that "no fact
 * for 3 March" can be told apart from "this device has never looked at 3 March"; a `record` that
 * coalesced two windows separated by a gap would assert evidence for days that were never fetched, and
 * every unsynced past day inside the gap would start reading as Missed instead of Unknown. That is
 * precisely the defect ADR-0053 was written to close, reintroduced one layer down — so it gets a named
 * regression test rather than a line in a bigger one.
 */
class OccurrenceCoverageLocalStoreTest {

    private fun db() = DefernoDatabase(
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { DefernoDatabase.Schema.create(it) },
    )

    private fun store(db: DefernoDatabase = db()) = SqlDelightOccurrenceCoverageLocalStore(db, Dispatchers.Default)

    private fun coverage(
        kind: ItemKind = ItemKind.Chore,
        definitionId: String = "chr-9",
        from: LocalDate,
        to: LocalDate,
    ) = OccurrenceCoverage(kind, definitionId, from, to)

    private fun june(day: Int) = LocalDate(2026, 6, day)

    private fun spans(ranges: List<OccurrenceCoverage>) = ranges.map { it.from to it.to }

    @Test
    fun aRecordedWindowRoundTripsThroughRealSqlite() = runTest {
        val store = store()
        store.record(coverage(from = june(1), to = june(7)))

        assertEquals(
            listOf(coverage(from = june(1), to = june(7))),
            store.get(ItemKind.Chore, "chr-9"),
        )
    }

    @Test
    fun anOverlappingWindowIsCoalescedIntoOneRange() = runTest {
        val store = store()
        store.record(coverage(from = june(1), to = june(7)))
        store.record(coverage(from = june(5), to = june(12)))

        assertEquals(listOf(june(1) to june(12)), spans(store.get(ItemKind.Chore, "chr-9")))
    }

    @Test
    fun anAdjacentWindowIsCoalescedIntoOneRange() = runTest {
        val store = store()
        store.record(coverage(from = june(1), to = june(7)))
        // Adjacent, not overlapping: `to + 1 day == from`. Joining them asserts nothing that was not
        // actually fetched, because there is no unsynced day between the two windows.
        store.record(coverage(from = june(8), to = june(12)))

        assertEquals(listOf(june(1) to june(12)), spans(store.get(ItemKind.Chore, "chr-9")))
    }

    @Test
    fun aGapBetweenTwoSyncedWindowsIsNeverSwallowed() = runTest {
        val store = store()
        store.record(coverage(from = june(1), to = june(7)))
        // One unsynced day (the 8th) between the two windows. Merging these would claim the 8th was
        // fetched, and an unresolved firing on the 8th would then read Missed rather than Unknown.
        store.record(coverage(from = june(9), to = june(12)))

        assertEquals(
            listOf(june(1) to june(7), june(9) to june(12)),
            spans(store.get(ItemKind.Chore, "chr-9")),
        )
    }

    @Test
    fun aLaterWindowBridgingTheGapJoinsAllThreeIntoOne() = runTest {
        val store = store()
        store.record(coverage(from = june(1), to = june(7)))
        store.record(coverage(from = june(9), to = june(12)))
        // The gap day itself is now fetched, so the three genuinely become one span — and the merged
        // set REPLACES the definition's rows, so neither of the absorbed rows is stranded behind.
        store.record(coverage(from = june(8), to = june(8)))

        assertEquals(listOf(june(1) to june(12)), spans(store.get(ItemKind.Chore, "chr-9")))
    }

    @Test
    fun coverageIsScopedToOneDefinitionAndOneKind() = runTest {
        val store = store()
        store.record(coverage(from = june(1), to = june(7)))
        store.record(coverage(definitionId = "chr-OTHER", from = june(1), to = june(30)))
        // Same id, different kind — a different definition entirely, so its coverage stays separate.
        store.record(coverage(kind = ItemKind.Event, definitionId = "chr-9", from = june(20), to = june(25)))

        assertEquals(listOf(june(1) to june(7)), spans(store.get(ItemKind.Chore, "chr-9")))
        assertEquals(listOf(june(1) to june(30)), spans(store.get(ItemKind.Chore, "chr-OTHER")))
        assertEquals(listOf(june(20) to june(25)), spans(store.get(ItemKind.Event, "chr-9")))
    }

    @Test
    fun observeCoveringAnswersInsideARangeAndFallsSilentInTheGap() = runTest {
        val store = store()
        store.record(coverage(from = june(1), to = june(7)))
        store.record(coverage(from = june(9), to = june(12)))

        // Inside: the absence of a fact on the 5th is evidence.
        store.observeCovering(june(5)).test {
            val covering = awaitItem()
            assertEquals(listOf(june(1) to june(7)), spans(covering))
            assertTrue(covering.single().covers(june(5)))
            cancelAndIgnoreRemainingEvents()
        }
        // In the gap: nothing covers the 8th, so the absence of a fact there is only ignorance.
        store.observeCovering(june(8)).test {
            assertEquals(emptyList(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        // Both bounds are inclusive, so the edges of a range are covered.
        assertTrue(store.get(ItemKind.Chore, "chr-9").any { it.covers(june(1)) })
        assertTrue(store.get(ItemKind.Chore, "chr-9").any { it.covers(june(12)) })
        assertFalse(store.get(ItemKind.Chore, "chr-9").any { it.covers(june(13)) })
    }

    @Test
    fun observeCoveringSpansDefinitionsAndReEmitsOnRecord() = runTest {
        val store = store()
        store.record(coverage(from = june(1), to = june(7)))

        store.observeCovering(june(5)).test {
            assertEquals(listOf("chr-9"), awaitItem().map { it.definitionId })
            store.record(coverage(kind = ItemKind.Habit, definitionId = "hab-1", from = june(4), to = june(6)))
            assertEquals(
                listOf(ItemKind.Chore to "chr-9", ItemKind.Habit to "hab-1"),
                awaitItem().map { it.kind to it.definitionId },
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun clearForgetsOneDefinitionAndLeavesTheOthersAlone() = runTest {
        val store = store()
        store.record(coverage(from = june(1), to = june(7)))
        store.record(coverage(definitionId = "chr-OTHER", from = june(1), to = june(7)))

        store.clear(ItemKind.Chore, "chr-9")

        assertEquals(emptyList(), store.get(ItemKind.Chore, "chr-9"))
        assertEquals(listOf(june(1) to june(7)), spans(store.get(ItemKind.Chore, "chr-OTHER")))
    }

    @Test
    fun anUnrecognisedStoredKindDropsTheRange() = runTest {
        val db = db()
        // A window recorded by a build that models a kind this one does not. Mis-filing it under Task
        // would claim evidence about firings that were never fetched, so it is simply not seen.
        db.occurrenceCoverageEntityQueries.insertOrReplace(
            OccurrenceCoverageEntity(
                kind = "Ritual",
                definition_id = "rit-1",
                from_date = june(1).toString(),
                to_date = june(7).toString(),
            ),
        )

        store(db).observeCovering(june(5)).test {
            assertEquals(emptyList(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
