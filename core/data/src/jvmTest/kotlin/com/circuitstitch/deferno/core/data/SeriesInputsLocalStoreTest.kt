package com.circuitstitch.deferno.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.circuitstitch.deferno.core.data.chore.SqlDelightChoreLocalStore
import com.circuitstitch.deferno.core.data.event.SqlDelightEventLocalStore
import com.circuitstitch.deferno.core.data.habit.SqlDelightHabitLocalStore
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
 * Sibling of `RecurringLocalStoreTest`, and deliberately separate: these inputs live in their own
 * kind-neutral tables (`seriesInputsEntity` + `seriesOverrideEntity`), not in the three definition rows,
 * so what is under test is the *stitch* — two tables joined back onto a definition on the way out.
 */
class SeriesInputsLocalStoreTest {

    private val created = Instant.parse("2026-05-04T01:53:05Z")

    private fun db() = DefernoDatabase(
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { DefernoDatabase.Schema.create(it) },
    )

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

    private fun chore(series: SeriesInputs?) = Chore(
        id = ChoreId("c-1"),
        orgSlug = "u-e4h2qk",
        title = "water the plants",
        definitionState = DefinitionState.Active,
        recurrence = Recurrence(Cadence.Weekly(listOf("Tue"))),
        dateCreated = created,
        seriesId = "s-1",
        series = series,
    )

    @Test
    fun aSeriesWithExdatesAndOverridesSurvivesTheCacheRoundTrip() = runTest {
        val store = SqlDelightChoreLocalStore(db(), Dispatchers.Default)
        store.upsert(chore(richSeries))

        // Equality on the whole data class, not field-by-field: a new field added to SeriesInputs and
        // forgotten in the codec fails here rather than being silently dropped for a release.
        assertEquals(richSeries, store.get(ChoreId("c-1"))?.series)
    }

    @Test
    fun overridesComeBackAscendingBySlotEvenWhenWrittenOutOfOrder() = runTest {
        val store = SqlDelightChoreLocalStore(db(), Dispatchers.Default)
        // The wire guarantees ascending order; the cache must not be the thing that breaks it, and a
        // child table has no inherent order at all. `ORDER BY recurrence_id` restores it — which works
        // only because the column holds an ISO wall time, where lexicographic IS chronological.
        val shuffled = richSeries.copy(overrides = richSeries.overrides.reversed())
        store.upsert(chore(shuffled))

        assertEquals(
            listOf(
                LocalDateTime.parse("2026-08-11T23:59:59"),
                LocalDateTime.parse("2026-08-25T23:59:59"),
            ),
            store.get(ChoreId("c-1"))?.series?.overrides?.map { it.recurrenceId },
        )
    }

    @Test
    fun anAbsentBlockStaysAbsentAndIsNotAnEmptyOne() = runTest {
        val store = SqlDelightChoreLocalStore(db(), Dispatchers.Default)
        store.upsert(chore(null))

        // The distinction the whole nullable type exists for. `null` is the backend's elision — "this
        // device cannot reproduce that grid" — and must never decode as `SeriesInputs(exdates = [])`,
        // which claims the opposite: a grid that is fully known and has no exclusions.
        assertNull(store.get(ChoreId("c-1"))?.series)
    }

    @Test
    fun aBlockThatGoesAwayTakesItsOverridesWithIt() = runTest {
        val store = SqlDelightChoreLocalStore(db(), Dispatchers.Default)
        store.upsert(chore(richSeries))
        store.upsert(chore(null))

        // Clear-then-seed, including on the clear: an override left behind would haunt a grid whose
        // series no longer exists, and a parent row deleted without its children would resurrect them
        // the next time the same definition got a block.
        assertNull(store.get(ChoreId("c-1"))?.series)
        store.upsert(chore(richSeries.copy(overrides = emptyList())))
        assertEquals(emptyList(), store.get(ChoreId("c-1"))?.series?.overrides)
    }

    @Test
    fun eachKindGetsItsOwnRowsUnderTheSharedTables() = runTest {
        // The tables are kind-neutral, so `kind` is the only thing keeping three definitions that happen
        // to share an id apart. Ids collide in practice far less than this, but the key is the contract.
        val database = db()
        val habits = SqlDelightHabitLocalStore(database, Dispatchers.Default)
        val events = SqlDelightEventLocalStore(database, Dispatchers.Default)
        val chores = SqlDelightChoreLocalStore(database, Dispatchers.Default)

        val habitSeries = richSeries.copy(tzid = "Europe/Berlin", overrides = emptyList())
        habits.upsert(
            Habit(
                id = HabitId("shared-id"),
                orgSlug = "u-e4h2qk",
                title = "stretch",
                definitionState = DefinitionState.Active,
                recurrence = Recurrence(Cadence.Daily),
                dateCreated = created,
                series = habitSeries,
            ),
        )
        chores.upsert(chore(richSeries).copy(id = ChoreId("shared-id")))
        events.upsert(
            Event(
                id = EventId("shared-id"),
                orgSlug = "u-e4h2qk",
                title = "standup",
                definitionState = DefinitionState.Active,
                recurrence = Recurrence(Cadence.Daily),
                dateCreated = created,
                series = null,
            ),
        )

        assertEquals(habitSeries, habits.get(HabitId("shared-id"))?.series)
        assertEquals(richSeries, chores.get(ChoreId("shared-id"))?.series)
        assertNull(events.get(EventId("shared-id"))?.series)
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
        val store = SqlDelightChoreLocalStore(db(), Dispatchers.Default)
        store.upsert(chore(richSeries))

        val cached = assertNotNullChore(store.get(ChoreId("c-1")))
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

    private fun assertNotNullChore(chore: Chore?): Chore = requireNotNull(chore) { "not cached" }
}
