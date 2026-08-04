package com.circuitstitch.deferno.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.turbine.test
import com.circuitstitch.deferno.core.data.occurrence.SqlDelightOccurrenceFactLocalStore
import com.circuitstitch.deferno.core.database.sql.DefernoDatabase
import com.circuitstitch.deferno.core.database.sql.OccurrenceFactEntity
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.OccurrenceFact
import com.circuitstitch.deferno.core.model.OccurrenceResolution
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * Real-SQLite integration for the occurrence **fact** store (#390, ADR-0053 decision 4, ADR-0006
 * JVM-fast path) — the replacement for `OccurrenceLocalStoreTest`. It proves the SQL path: the
 * row<->domain mapping (the [OccurrenceResolution], the [LocalDate], the two nullable instants, the
 * [ItemKind]) round-trips through a genuine `DefernoDatabase` over an in-memory `JdbcSqliteDriver`,
 * that the composite key `(kind, definition_id, occurrence_date)` genuinely replaces rather than
 * duplicating, and that both defensive decodes degrade instead of throwing.
 *
 * It is also the only real-SQLite guard on the whole-row `insertOrReplace(OccurrenceFactEntity)` bind:
 * the `.sq` `VALUES ?` form takes the entity positionally, so a column declared out of order shows up
 * here as a round-trip that no longer returns what it stored.
 *
 * Nothing in here asks what today is. That is the point of the table: the Scheduled-vs-Missed split is
 * a reading over these facts, derived at render time by `resolveOccurrenceState`, and this store cannot
 * express it.
 */
class OccurrenceFactLocalStoreTest {

    private val day = LocalDate(2026, 6, 8)

    private fun db() = DefernoDatabase(
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { DefernoDatabase.Schema.create(it) },
    )

    private fun store(db: DefernoDatabase = db()) = SqlDelightOccurrenceFactLocalStore(db, Dispatchers.Default)

    private fun fact(
        kind: ItemKind = ItemKind.Chore,
        definitionId: String = "chr-9",
        date: LocalDate = day,
        resolution: OccurrenceResolution = OccurrenceResolution.DoneLate,
        doneAt: Instant? = Instant.parse("2026-06-08T19:30:00Z"),
        completeBy: Instant? = Instant.parse("2026-06-08T17:00:00Z"),
    ) = OccurrenceFact(kind, definitionId, date, resolution, doneAt, completeBy)

    @Test
    fun everyStoredResolutionRoundTripsThroughRealSqlite() = runTest {
        val store = store()

        // All five members of the STORED partition, timestamps present. Five, not four: an event stores
        // a genuine `scheduled` row and a chore's `dropped` is the wire spelling of Skipped.
        OccurrenceResolution.entries.forEachIndexed { i, resolution ->
            val f = fact(date = LocalDate(2026, 6, 1 + i), resolution = resolution)
            store.upsert(f)
            assertEquals(f, store.get(f.kind, f.definitionId, f.date))
        }
    }

    @Test
    fun aFactWithNoTimestampsRoundTripsWithBothColumnsNull() = runTest {
        val store = store()
        // A habit stores no status and no deadline — done-ness is `done_at != null` and nothing else —
        // so the nullable columns must survive as genuinely absent, not as an empty string.
        val f = fact(kind = ItemKind.Habit, definitionId = "hab-1", resolution = OccurrenceResolution.Scheduled, doneAt = null, completeBy = null)
        store.upsert(f)

        val read = store.get(ItemKind.Habit, "hab-1", day)
        assertEquals(f, read)
        assertNull(read?.doneAt)
        assertNull(read?.completeBy)
    }

    @Test
    fun upsertOnTheSameFiringReplacesRatherThanDuplicating() = runTest {
        val store = store()
        store.upsert(fact(resolution = OccurrenceResolution.InProgress, doneAt = null))
        store.upsert(fact(resolution = OccurrenceResolution.DoneOnTime))

        // `(kind, definition_id, occurrence_date)` is the PRIMARY KEY, so the second write is the same
        // firing — one row, the later resolution — not a second opinion about the same day.
        val all = store.observeInRange(ItemKind.Chore, "chr-9", day, day)
        all.test {
            assertEquals(listOf(OccurrenceResolution.DoneOnTime), awaitItem().map { it.resolution })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun theSameDateUnderADifferentKindIsADifferentFiring() = runTest {
        val store = store()
        store.upsert(fact(kind = ItemKind.Chore, definitionId = "same-id"))
        store.upsert(fact(kind = ItemKind.Event, definitionId = "same-id", resolution = OccurrenceResolution.Skipped))

        assertEquals(OccurrenceResolution.DoneLate, store.get(ItemKind.Chore, "same-id", day)?.resolution)
        assertEquals(OccurrenceResolution.Skipped, store.get(ItemKind.Event, "same-id", day)?.resolution)
    }

    @Test
    fun observeOnReturnsEveryDefinitionsFactsForOneDayAndReEmitsOnUpsert() = runTest {
        val store = store()
        store.upsert(fact(kind = ItemKind.Habit, definitionId = "hab-1", resolution = OccurrenceResolution.Scheduled))
        store.upsert(fact(kind = ItemKind.Chore, definitionId = "chr-9"))
        // A neighbouring day must not leak into the agenda's read.
        store.upsert(fact(kind = ItemKind.Chore, definitionId = "chr-9", date = LocalDate(2026, 6, 9)))

        store.observeOn(day).test {
            assertEquals(
                listOf(ItemKind.Chore to "chr-9", ItemKind.Habit to "hab-1"),
                awaitItem().map { it.kind to it.definitionId },
            )
            store.upsert(fact(kind = ItemKind.Event, definitionId = "evt-3"))
            assertEquals(3, awaitItem().size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun observeInRangeRespectsBothInclusiveBounds() = runTest {
        val store = store()
        (5..9).forEach { d -> store.upsert(fact(date = LocalDate(2026, 6, d))) }

        // Both bounds inclusive — this window is exactly the `?from=&to=` an Occurrence coverage row
        // records, so the facts and the coverage describe the same span.
        store.observeInRange(ItemKind.Chore, "chr-9", LocalDate(2026, 6, 6), LocalDate(2026, 6, 8)).test {
            assertEquals(
                listOf(LocalDate(2026, 6, 6), LocalDate(2026, 6, 7), LocalDate(2026, 6, 8)),
                awaitItem().map { it.date },
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun observeOneEmitsNullUntilTheFiringIsRecorded() = runTest {
        val store = store()
        store.observe(ItemKind.Chore, "chr-9", day).test {
            assertNull(awaitItem())
            store.upsert(fact())
            assertEquals(OccurrenceResolution.DoneLate, awaitItem()?.resolution)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun deleteRemovesTheFactSoTheFiringIsUnresolvedAgain() = runTest {
        val store = store()
        store.upsert(fact())
        store.delete(ItemKind.Chore, "chr-9", day)

        // A Clear is the ABSENCE of a fact, never a fact reading "Scheduled" — the two are different
        // claims and only one of them is true after a clear.
        assertNull(store.get(ItemKind.Chore, "chr-9", day))
    }

    @Test
    fun replaceRangeBlanksTheWindowThenWritesTheFreshSet() = runTest {
        val store = store()
        (5..9).forEach { d -> store.upsert(fact(date = LocalDate(2026, 6, d))) }
        // Outside the window on both axes: a different definition, and a date past `to`.
        store.upsert(fact(definitionId = "chr-OTHER", date = LocalDate(2026, 6, 7)))
        store.upsert(fact(date = LocalDate(2026, 6, 20)))

        store.replaceRange(
            ItemKind.Chore, "chr-9", LocalDate(2026, 6, 6), LocalDate(2026, 6, 8),
            listOf(fact(date = LocalDate(2026, 6, 7), resolution = OccurrenceResolution.Skipped)),
        )

        // A resolution the server no longer reports inside `[from, to]` is gone, not merely absent from
        // the response — which is what makes a server-side Clear converge locally.
        assertNull(store.get(ItemKind.Chore, "chr-9", LocalDate(2026, 6, 6)))
        assertEquals(OccurrenceResolution.Skipped, store.get(ItemKind.Chore, "chr-9", LocalDate(2026, 6, 7))?.resolution)
        assertNull(store.get(ItemKind.Chore, "chr-9", LocalDate(2026, 6, 8)))
        // Untouched outside the window, on either axis.
        assertEquals(OccurrenceResolution.DoneLate, store.get(ItemKind.Chore, "chr-9", LocalDate(2026, 6, 5))?.resolution)
        assertEquals(OccurrenceResolution.DoneLate, store.get(ItemKind.Chore, "chr-9", LocalDate(2026, 6, 20))?.resolution)
        assertEquals(OccurrenceResolution.DoneLate, store.get(ItemKind.Chore, "chr-OTHER", LocalDate(2026, 6, 7))?.resolution)
    }

    @Test
    fun transactionCommitsTheBatchAndReEmitsOnce() = runTest {
        val store = store()

        store.observeOn(day).test {
            assertEquals(emptyList(), awaitItem())
            // The optimistic apply is a read-modify-write; wrapping it means a concurrent reconcile
            // cannot interleave, and SQLDelight fires the query listener once, at commit.
            store.transaction { s ->
                s.upsert(fact(kind = ItemKind.Habit, definitionId = "hab-1", resolution = OccurrenceResolution.Scheduled, doneAt = null))
                s.upsert(fact(kind = ItemKind.Chore, definitionId = "chr-9"))
            }
            assertEquals(2, awaitItem().size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun anUnrecognisedStoredResolutionDegradesToScheduledRatherThanThrowing() = runTest {
        val db = db()
        // A token from a build newer than this one. The row is KEPT — its existence is itself evidence
        // that the server holds a record for the day, and dropping it would make the day look
        // unrecorded, which reads as Missed inside coverage. "No progress" is the honest degradation.
        db.occurrenceFactEntityQueries.insertOrReplace(
            OccurrenceFactEntity(
                kind = ItemKind.Chore.name,
                definition_id = "chr-9",
                occurrence_date = day.toString(),
                resolution = "Teleported",
                done_at = null,
                complete_by = null,
            ),
        )

        assertEquals(OccurrenceResolution.Scheduled, store(db).get(ItemKind.Chore, "chr-9", day)?.resolution)
    }

    @Test
    fun anUnrecognisedStoredKindDropsTheRowRatherThanMisFilingItAsATask() = runTest {
        val db = db()
        db.occurrenceFactEntityQueries.insertOrReplace(
            OccurrenceFactEntity(
                kind = "Ritual",
                definition_id = "rit-1",
                occurrence_date = day.toString(),
                resolution = OccurrenceResolution.DoneOnTime.name,
                done_at = null,
                complete_by = null,
            ),
        )
        val store = store(db)
        store.upsert(fact())

        // `kind` names which definition table the firing belongs to, so coercing an unknown token to
        // Task — as the retired occurrenceEntity mapping did — would attribute the firing to a
        // different definition entirely. The day agenda simply does not see it.
        store.observeOn(day).test {
            assertEquals(listOf("chr-9"), awaitItem().map { it.definitionId })
            cancelAndIgnoreRemainingEvents()
        }
    }
}
