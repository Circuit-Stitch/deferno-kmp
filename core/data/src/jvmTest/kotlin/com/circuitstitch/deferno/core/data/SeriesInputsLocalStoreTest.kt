package com.circuitstitch.deferno.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.turbine.test
import com.circuitstitch.deferno.core.data.item.ItemLocalStore
import com.circuitstitch.deferno.core.data.item.SqlDelightItemLocalStore
import com.circuitstitch.deferno.core.data.item.asKindRow
import com.circuitstitch.deferno.core.data.item.cached
import com.circuitstitch.deferno.core.database.sql.DefernoDatabase
import com.circuitstitch.deferno.core.model.Cadence
import com.circuitstitch.deferno.core.model.Chore
import com.circuitstitch.deferno.core.model.ChoreId
import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.Event
import com.circuitstitch.deferno.core.model.EventId
import com.circuitstitch.deferno.core.model.Expansion
import com.circuitstitch.deferno.core.model.Habit
import com.circuitstitch.deferno.core.model.HabitId
import com.circuitstitch.deferno.core.model.Recurrence
import com.circuitstitch.deferno.core.model.SeriesInputs
import com.circuitstitch.deferno.core.model.SeriesOverride
import com.circuitstitch.deferno.core.model.expandOccurrenceGrid
import com.circuitstitch.deferno.core.model.recipe.KindRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Real-SQLite integration for the series expansion inputs (#410, ADR-0053 decision 2) — the half of the
 * [[Occurrence grid]] seam that #401 could not reach.
 *
 * `expandOccurrenceGrid` landed pure, measured against the Rust on a generated corpus, and **callable
 * by nothing**: it takes a `SeriesInputs` and this client had no column to build one from. So the
 * interesting assertion here is not that rows round-trip — it is the last test, which takes a definition
 * out of a cold cache and gets real firing dates back with no network anywhere in the picture. Everything
 * above it exists to make that one honest.
 *
 * Sibling of `SqlDelightItemLocalStoreTest`, and deliberately separate: these inputs live in their own
 * tables (`seriesInputsEntity` + `seriesOverrideEntity`), not in the item row, so what is under test is
 * the *stitch* — two tables joined back onto a definition on the way out.
 *
 * **Both tables are keyed on the item id alone since #422.** They were keyed `(kind, definition_id)`,
 * which was the same discriminator the four item tables carried; `SeriesInputs` names no kind, so
 * nothing above the store noticed it go.
 */
class SeriesInputsLocalStoreTest {

    private val created = Instant.parse("2026-05-04T01:53:05Z")

    private fun db() = DefernoDatabase(
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { DefernoDatabase.Schema.create(it) },
    )

    private fun newStore(database: DefernoDatabase = db()) =
        SqlDelightItemLocalStore(database, Dispatchers.Default)

    /**
     * The full-fat block: a Segment bound, several exdates, and several overrides of which one only
     * cancels and one only moves. Every field of [SeriesInputs] is non-default, so a column dropped by
     * the codec — or filled in the wrong position by the whole-row `VALUES ?` bind — cannot hide.
     */
    private val richSeries = SeriesInputs(
        anchorLocal = LocalDateTime.parse("2026-08-04T23:59:59"),
        tzid = "America/Los_Angeles",
        untilUtc = Instant.parse("2026-09-16T06:59:59Z"),
        exdates = listOf(
            LocalDateTime.parse("2026-08-18T23:59:59"),
            LocalDateTime.parse("2026-09-01T23:59:59"),
        ),
        overrides = listOf(
            SeriesOverride(recurrenceId = LocalDateTime.parse("2026-08-11T23:59:59"), isCancelled = true),
            SeriesOverride(
                recurrenceId = LocalDateTime.parse("2026-08-25T23:59:59"),
                movedToLocal = LocalDateTime.parse("2026-08-26T18:30:00"),
            ),
        ),
    )

    private fun chore(series: SeriesInputs?, id: String = "c-1") = Chore(
        id = ChoreId(id),
        orgSlug = "u-e4h2qk",
        title = "water the plants",
        definitionState = DefinitionState.Active,
        recurrence = Recurrence(Cadence.Weekly(listOf("Tue"))),
        dateCreated = created,
        seriesId = "s-1",
        series = series,
    )

    /** The stored row's own series inputs, read back through the store's stitch. */
    private suspend fun ItemLocalStore.seriesOf(id: String): SeriesInputs? =
        when (val row = get(id)?.asKindRow()) {
            is KindRow.OfHabit -> row.habit.series
            is KindRow.OfChore -> row.chore.series
            is KindRow.OfEvent -> row.event.series
            else -> null
        }

    @Test
    fun aSeriesWithExdatesAndOverridesSurvivesTheCacheRoundTrip() = runTest {
        val store = newStore()
        store.upsert(chore(richSeries).cached())

        // Equality on the whole data class, not field-by-field: a new field added to SeriesInputs and
        // forgotten in the codec fails here rather than being silently dropped for a release.
        assertEquals(richSeries, store.seriesOf("c-1"))
    }

    @Test
    fun overridesComeBackAscendingBySlotEvenWhenWrittenOutOfOrder() = runTest {
        val store = newStore()
        // The wire guarantees ascending order; the cache must not be the thing that breaks it, and a
        // child table has no inherent order at all. `ORDER BY recurrence_id` restores it — which works
        // only because the column holds an ISO wall time, where lexicographic IS chronological.
        val shuffled = richSeries.copy(overrides = richSeries.overrides.reversed())
        store.upsert(chore(shuffled).cached())

        assertEquals(
            listOf(
                LocalDateTime.parse("2026-08-11T23:59:59"),
                LocalDateTime.parse("2026-08-25T23:59:59"),
            ),
            store.seriesOf("c-1")?.overrides?.map { it.recurrenceId },
        )
    }

    @Test
    fun anAbsentBlockStaysAbsentAndIsNotAnEmptyOne() = runTest {
        val store = newStore()
        store.upsert(chore(null).cached())

        // The distinction the whole nullable type exists for. `null` is the backend's elision — "this
        // device cannot reproduce that grid" — and must never decode as `SeriesInputs(exdates = [])`,
        // which claims the opposite: a grid that is fully known and has no exclusions.
        assertNull(store.seriesOf("c-1"))
    }

    @Test
    fun aBlockThatGoesAwayTakesItsOverridesWithIt() = runTest {
        val store = newStore()
        store.upsert(chore(richSeries).cached())
        store.upsert(chore(null).cached())

        // Clear-then-seed, including on the clear: an override left behind would haunt a grid whose
        // series no longer exists, and a parent row deleted without its children would resurrect them
        // the next time the same definition got a block.
        assertNull(store.seriesOf("c-1"))
        store.upsert(chore(richSeries.copy(overrides = emptyList())).cached())
        assertEquals(emptyList(), store.seriesOf("c-1")?.overrides)
    }

    /**
     * The key is the item id alone (#422). It was `(kind, definition_id)`, so three definitions of
     * different kinds sharing an id kept separate rows; an item id is unique across kinds, so the kind
     * was carrying no information the id did not already have — and now that one cache holds every kind,
     * two rows *cannot* share an id.
     */
    @Test
    fun eachItemGetsItsOwnRowsUnderTheSharedTables() = runTest {
        val store = newStore()
        val habitSeries = richSeries.copy(tzid = "Europe/Berlin", overrides = emptyList())

        store.upsert(
            Habit(
                id = HabitId("h-1"),
                orgSlug = "u-e4h2qk",
                title = "stretch",
                definitionState = DefinitionState.Active,
                recurrence = Recurrence(Cadence.Daily),
                dateCreated = created,
                series = habitSeries,
            ).cached(),
        )
        store.upsert(chore(richSeries, id = "c-1").cached())
        store.upsert(
            Event(
                id = EventId("e-1"),
                orgSlug = "u-e4h2qk",
                title = "standup",
                definitionState = DefinitionState.Active,
                recurrence = Recurrence(Cadence.Daily),
                dateCreated = created,
                series = null,
            ).cached(),
        )

        assertEquals(habitSeries, store.seriesOf("h-1"))
        assertEquals(richSeries, store.seriesOf("c-1"))
        assertNull(store.seriesOf("e-1"))
    }

    /**
     * The item row and its inputs commit in **one** transaction, which is why the list read can stitch
     * them inline instead of combining two observed `Flow`s. Two independently-observed tables race:
     * `mapToList` re-queries off the notification asynchronously, so an upsert would momentarily emit
     * the new series map beside the old item list and a freshly created row would flicker out of the
     * tree. A single commit-time emission carrying the row *with* its grid is the property.
     */
    @Test
    fun theRowAndItsInputsArriveTogetherInOneEmission() = runTest {
        val store = newStore()

        store.observeActive().test {
            assertTrue(awaitItem().isEmpty())

            store.upsert(chore(richSeries).cached())

            val emitted = awaitItem().single()
            assertEquals("c-1", emitted.id)
            assertEquals(richSeries, emitted.item.repeats.series)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * **The point of #410.** A recurring definition goes into the cache; the cache is then the only thing
     * consulted; and what comes out the far end is a list of dates the series actually fires on.
     *
     * No network, no `/occurrences` fetch, no server-computed answer stored anywhere — the INPUTS were
     * cached and the grid is recomputed from them (ADR-0001). Before this issue the two halves of that
     * sentence could not be written in the same test: the expander existed and the columns did not.
     */
    @Test
    fun aCachedDefinitionExpandsToRealFiringDatesWithNoNetwork() = runTest {
        val store = newStore()
        store.upsert(chore(richSeries).cached())

        val cached = assertIs<KindRow.OfChore>(store.get("c-1")?.asKindRow()).chore
        val expansion = expandOccurrenceGrid(
            recurrence = requireNotNull(cached.recurrence),
            series = requireNotNull(cached.series),
            from = LocalDate.parse("2026-08-01"),
            to = LocalDate.parse("2026-09-30"),
        )

        val firings = assertIs<Expansion.Firings>(expansion).firings
        // Weekly on Tuesday from the 4th. The 18th and the 1st are EXDATEd away; the 11th is cancelled
        // but still EMITTED (a hole the user can see, not a hole in the data); the 25th moved to the
        // Wednesday evening, so it renders on the 26th while still keyed on the slot it came from; and
        // the series stops at the exclusive `until_utc`, which is why 2026-09-15 does not appear.
        assertEquals(
            listOf("2026-08-04", "2026-08-11", "2026-08-26", "2026-09-08"),
            firings.map { it.date.toString() },
        )
        assertTrue(firings.single { it.date == LocalDate.parse("2026-08-11") }.isCancelled)
        val moved = firings.single { it.date == LocalDate.parse("2026-08-26") }
        assertEquals(LocalDateTime.parse("2026-08-25T23:59:59"), moved.recurrenceId)
        assertEquals(LocalDateTime.parse("2026-08-26T18:30:00"), moved.startLocal)
    }
}
