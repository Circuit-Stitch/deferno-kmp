package com.circuitstitch.deferno.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.turbine.test
import com.circuitstitch.deferno.core.data.occurrence.SqlDelightOccurrenceLocalStore
import com.circuitstitch.deferno.core.database.sql.DefernoDatabase
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.Occurrence
import com.circuitstitch.deferno.core.model.OccurrenceId
import com.circuitstitch.deferno.core.model.OccurrenceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Real-SQLite integration for the Occurrence local store (#71, ADR-0006 JVM-fast path) — the
 * firing-level sibling of `RecurringLocalStoreTest`. It proves the SQL path: the row<->domain mapping
 * (the [OccurrenceState], the [LocalDate], the [ItemKind], the [OccurrenceId] value class) round-trips
 * through a genuine `DefernoDatabase` over an in-memory `JdbcSqliteDriver`, that `observeForDefinition`
 * re-emits on upsert (the AC #4 "appears via the local DB Flow"), and that delete removes the row.
 */
class OccurrenceLocalStoreTest {

    private fun db() = DefernoDatabase(
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { DefernoDatabase.Schema.create(it) },
    )

    private fun occurrence(
        id: String = "occ-1",
        definitionId: String = "evt-9",
        kind: ItemKind = ItemKind.Event,
        date: LocalDate = LocalDate(2026, 6, 8),
        state: OccurrenceState = OccurrenceState.DoneLate,
    ) = Occurrence(OccurrenceId(id), definitionId, kind, date, state)

    @Test
    fun occurrenceRoundTripsThroughRealSqliteAndObserveReEmitsOnUpsert() = runTest {
        val store = SqlDelightOccurrenceLocalStore(db(), Dispatchers.Default)
        val occ = occurrence()

        store.observeForDefinition("evt-9").test {
            assertEquals(emptyList(), awaitItem())
            store.upsert(occ)
            assertEquals(listOf(OccurrenceId("occ-1")), awaitItem().map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
        // Faithful round-trip including the condensed state, the kind, and the calendar date.
        assertEquals(occ, store.get(OccurrenceId("occ-1")))
    }

    @Test
    fun observeForDefinitionScopesToTheDefinition_andOrdersByDate() = runTest {
        val store = SqlDelightOccurrenceLocalStore(db(), Dispatchers.Default)
        store.upsert(occurrence(id = "b", definitionId = "evt-9", date = LocalDate(2026, 6, 9)))
        store.upsert(occurrence(id = "a", definitionId = "evt-9", date = LocalDate(2026, 6, 8)))
        store.upsert(occurrence(id = "other", definitionId = "evt-OTHER", date = LocalDate(2026, 6, 8)))

        store.observeForDefinition("evt-9").test {
            // Only this definition's firings, ordered by occurrence_date (ASC) — not evt-OTHER.
            assertEquals(listOf(OccurrenceId("a"), OccurrenceId("b")), awaitItem().map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun deleteRemovesTheRow() = runTest {
        val store = SqlDelightOccurrenceLocalStore(db(), Dispatchers.Default)
        store.upsert(occurrence())
        assertEquals(occurrence(), store.get(OccurrenceId("occ-1")))

        store.delete(OccurrenceId("occ-1"))
        assertNull(store.get(OccurrenceId("occ-1")))
    }
}
