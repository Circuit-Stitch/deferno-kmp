package com.circuitstitch.deferno.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.turbine.test
import com.circuitstitch.deferno.core.data.chore.SqlDelightChoreLocalStore
import com.circuitstitch.deferno.core.data.event.SqlDelightEventLocalStore
import com.circuitstitch.deferno.core.data.habit.SqlDelightHabitLocalStore
import com.circuitstitch.deferno.core.database.sql.DefernoDatabase
import com.circuitstitch.deferno.core.model.Chore
import com.circuitstitch.deferno.core.model.ChoreId
import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.Event
import com.circuitstitch.deferno.core.model.EventId
import com.circuitstitch.deferno.core.model.Habit
import com.circuitstitch.deferno.core.model.HabitId
import com.circuitstitch.deferno.core.model.HydrationState
import com.circuitstitch.deferno.core.model.OrgId
import com.circuitstitch.deferno.core.model.Recurrence
import com.circuitstitch.deferno.core.model.RecurrenceFrequency
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Real-SQLite integration for the recurring local stores (#71, ADR-0006 JVM-fast path). The commonTest
 * fakes prove the repository/writer *behaviour*; this proves the *SQL path* — the row<->domain mapping
 * (the DefinitionState light switch, the flattened Recurrence, instants, the boolean<->INTEGER, the id
 * value classes) round-trips through a genuine `DefernoDatabase` over an in-memory `JdbcSqliteDriver`,
 * and that `upsert` re-emits the observe Flow.
 */
class RecurringLocalStoreTest {

    private val created = Instant.parse("2026-05-04T01:53:05Z")

    private fun db() = DefernoDatabase(
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { DefernoDatabase.Schema.create(it) },
    )

    @Test
    fun habitRoundTripsThroughRealSqliteAndObserveReEmitsOnUpsert() = runTest {
        val store = SqlDelightHabitLocalStore(db(), Dispatchers.Default)
        val habit = Habit(
            id = HabitId("h-1"),
            orgSlug = "u-e4h2qk",
            title = "stretch",
            definitionState = DefinitionState.Active,
            recurrence = Recurrence(RecurrenceFrequency.Weekly, days = listOf("Mon", "Wed")),
            labels = listOf("health"),
            completeBy = Instant.parse("2026-05-04T08:00:00Z"),
            pinned = true,
            sequence = 5,
            ref = "u-e4h2qk-1",
            dateCreated = created,
            hydration = HydrationState.Full,
            ownerOrgId = OrgId("org-1"),
            description = "body",
            seriesId = "s-1",
        )

        store.observeActive().test {
            assertEquals(emptyList(), awaitItem())
            store.upsert(habit)
            assertEquals(listOf(HabitId("h-1")), awaitItem().map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
        // Faithful round-trip including the flattened recurrence + days.
        assertEquals(habit, store.get(HabitId("h-1")))
    }

    @Test
    fun choreRoundTripsCadenceAndDelete() = runTest {
        val store = SqlDelightChoreLocalStore(db(), Dispatchers.Default)
        val chore = Chore(
            id = ChoreId("c-1"),
            orgSlug = "u-e4h2qk",
            title = "trash",
            definitionState = DefinitionState.Archived,
            recurrence = Recurrence(RecurrenceFrequency.Daily),
            cadenceMode = "rolling",
            dateCreated = created,
        )
        store.upsert(chore)
        assertEquals(chore, store.get(ChoreId("c-1")))

        store.delete(ChoreId("c-1"))
        assertNull(store.get(ChoreId("c-1")))
    }

    @Test
    fun eventRoundTripsItsFixedWindowAndNullRecurrence() = runTest {
        val store = SqlDelightEventLocalStore(db(), Dispatchers.Default)
        val event = Event(
            id = EventId("e-1"),
            orgSlug = "u-e4h2qk",
            title = "standup",
            definitionState = DefinitionState.Active,
            recurrence = null,
            allDay = true,
            completeBy = Instant.parse("2026-04-18T16:00:00Z"),
            endTime = Instant.parse("2026-04-18T17:30:00Z"),
            dateCreated = created,
        )
        store.upsert(event)
        assertEquals(event, store.get(EventId("e-1")))
    }

    /**
     * The `/items` reconcile seam (#226): `allIds()` (the purge diff) and `transaction { }` (the atomic
     * batch) round-trip through real SQLite for every recurring kind, sharing the extracted
     * `reconcileTransaction` helper proved on the Task store.
     */
    @Test
    fun allIdsAndTransactionRoundTripThroughRealSqliteForEveryRecurringKind() = runTest {
        val database = db()
        val habits = SqlDelightHabitLocalStore(database, Dispatchers.Default)
        val chores = SqlDelightChoreLocalStore(database, Dispatchers.Default)
        val events = SqlDelightEventLocalStore(database, Dispatchers.Default)

        habits.transaction { it.upsert(habitOf("h-1")); it.upsert(habitOf("h-2")) }
        chores.transaction { it.upsert(choreOf("c-1")) }
        events.transaction { it.upsert(eventOf("e-1")) }

        assertEquals(setOf(HabitId("h-1"), HabitId("h-2")), habits.allIds())
        assertEquals(setOf(ChoreId("c-1")), chores.allIds())
        assertEquals(setOf(EventId("e-1")), events.allIds())

        // A transaction that deletes commits atomically; allIds reflects the purge.
        habits.transaction { it.delete(HabitId("h-1")) }
        assertEquals(setOf(HabitId("h-2")), habits.allIds())
    }

    private fun habitOf(id: String) =
        Habit(HabitId(id), "u-e4h2qk", "habit-$id", DefinitionState.Active, dateCreated = created)

    private fun choreOf(id: String) =
        Chore(ChoreId(id), "u-e4h2qk", "chore-$id", DefinitionState.Active, dateCreated = created)

    private fun eventOf(id: String) =
        Event(EventId(id), "u-e4h2qk", "event-$id", DefinitionState.Active, dateCreated = created)
}
