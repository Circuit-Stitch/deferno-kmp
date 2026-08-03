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
import com.circuitstitch.deferno.core.model.MonthlyAnchor
import com.circuitstitch.deferno.core.model.OrgId
import com.circuitstitch.deferno.core.model.Recurrence
import com.circuitstitch.deferno.core.model.RecurrenceBound
import com.circuitstitch.deferno.core.model.RecurrenceFrequency
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.LocalDate
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
            // Server-derived dependency flags must survive the real-SQLite round-trip for recurring kinds
            // too (#290): a recurring row inherits `blocked` from a blocked ancestor.
            blocked = true,
            isBlocker = true,
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

    /**
     * **The #382 regression, against real SQLite.** The reported symptom was a chore: `every_n_days` had
     * no domain representation, so it persisted as the literal enum name `"Unknown"` and the interval was
     * discarded — an every-30-days and an every-29-days chore became the same cached row, and the
     * original cadence was unrecoverable. The `end` bound had no column at all.
     *
     * This asserts the whole rule survives write → read **unchanged** (`assertEquals` on the domain
     * object, so any dropped column fails it), through the genuine `DefernoDatabase` schema rather than
     * a fake store.
     */
    @Test
    fun anEveryNDaysRuleWithAnAfterCountBoundSurvivesRealSqliteUnchanged() = runTest {
        val store = SqlDelightChoreLocalStore(db(), Dispatchers.Default)
        val chore = Chore(
            id = ChoreId("c-2"),
            orgSlug = "u-e4h2qk",
            title = "replace the filter",
            definitionState = DefinitionState.Active,
            recurrence = Recurrence(
                frequency = RecurrenceFrequency.EveryNDays,
                interval = 30,
                bound = RecurrenceBound.AfterCount(10),
            ),
            cadenceMode = "rolling",
            dateCreated = created,
        )

        store.upsert(chore)
        val reread = store.get(ChoreId("c-2"))

        assertEquals(chore, reread)
        // Spelled out, because `assertEquals` on the whole row would still pass if BOTH sides were wrong
        // in the same way — these are the two values the bug destroyed.
        assertEquals(RecurrenceFrequency.EveryNDays, reread?.recurrence?.frequency)
        assertEquals(30, reread?.recurrence?.interval)
        assertEquals(RecurrenceBound.AfterCount(10), reread?.recurrence?.bound)

        // …and the neighbouring interval is genuinely a DIFFERENT cached row, which it was not before.
        val faster = chore.copy(
            id = ChoreId("c-3"),
            recurrence = chore.recurrence?.copy(interval = 29),
        )
        store.upsert(faster)
        assertEquals(29, store.get(ChoreId("c-3"))?.recurrence?.interval)
        assertEquals(30, store.get(ChoreId("c-2"))?.recurrence?.interval)
    }

    /**
     * Every cadence shape and every bound, on all three recurring tables — the three `.sq` column sets
     * and the three entity mappings are separate code, so one of them silently dropping a column is
     * exactly the failure this catches.
     */
    @Test
    fun everyCadenceAndBoundRoundTripsThroughRealSqliteOnAllThreeRecurringKinds() = runTest {
        val rules = listOf(
            Recurrence(RecurrenceFrequency.Daily),
            Recurrence(RecurrenceFrequency.EveryNDays, interval = 3),
            Recurrence(RecurrenceFrequency.Weekly, days = listOf("Mon", "Wed")),
            Recurrence(
                RecurrenceFrequency.Monthly,
                interval = 1,
                monthlyAnchor = MonthlyAnchor.DayOfMonth(15),
            ),
            Recurrence(
                RecurrenceFrequency.Monthly,
                interval = 2,
                // nth = -1 is the "last Friday" sentinel; it must survive as a NEGATIVE integer.
                monthlyAnchor = MonthlyAnchor.NthWeekday(nth = -1, weekday = "Fri"),
                bound = RecurrenceBound.OnDate(LocalDate(2027, 1, 31)),
            ),
            Recurrence(RecurrenceFrequency.Yearly, interval = 1, month = 6, day = 14),
            Recurrence(RecurrenceFrequency.Custom, rrule = "FREQ=WEEKLY;BYDAY=MO,WE;UNTIL=20270131T235959Z"),
            // A cadence this client cannot model still round-trips under its preserved wire token,
            // instead of becoming the literal string "Unknown" and being lost.
            Recurrence(RecurrenceFrequency.Unknown, rawType = "fortnightly", interval = 2),
        )
        val database = db()
        val habits = SqlDelightHabitLocalStore(database, Dispatchers.Default)
        val chores = SqlDelightChoreLocalStore(database, Dispatchers.Default)
        val events = SqlDelightEventLocalStore(database, Dispatchers.Default)

        rules.forEachIndexed { i, rule ->
            habits.upsert(habitOf("h-$i").copy(recurrence = rule))
            assertEquals(rule, habits.get(HabitId("h-$i"))?.recurrence, "habit $rule")

            chores.upsert(choreOf("c-$i").copy(recurrence = rule))
            assertEquals(rule, chores.get(ChoreId("c-$i"))?.recurrence, "chore $rule")

            events.upsert(eventOf("e-$i").copy(recurrence = rule))
            assertEquals(rule, events.get(EventId("e-$i"))?.recurrence, "event $rule")
        }
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
