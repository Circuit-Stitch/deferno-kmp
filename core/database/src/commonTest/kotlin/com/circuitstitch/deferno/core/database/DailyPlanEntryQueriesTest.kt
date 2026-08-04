package com.circuitstitch.deferno.core.database

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Proves the `dailyPlanEntry` schema + queries (#21, #385): an ordered, **date-scoped, kind-tagged**
 * plan that reconciles as a full snapshot per day (#22) — delete the day, re-insert the ordered set in
 * one transaction.
 */
class DailyPlanEntryQueriesTest {
    private val date = "2026-06-06"
    private val tz = "America/New_York"

    @Test
    fun selectPlanReturnsEntriesOrderedByPosition() {
        val db = inMemoryDefernoDatabase()
        db.dailyPlanEntryQueries.insertEntry(date, 1, "task-b", "Task", tz)
        db.dailyPlanEntryQueries.insertEntry(date, 0, "task-a", "Task", tz)

        val plan = db.dailyPlanEntryQueries.selectPlan(date).executeAsList()
        assertEquals(listOf("task-a", "task-b"), plan.map { it.item_id })
    }

    /** A slot names its kind so the read can resolve the id against the one cache that holds it. */
    @Test
    fun aSlotCarriesTheKindItPointsAt() {
        val db = inMemoryDefernoDatabase()
        db.dailyPlanEntryQueries.insertEntry(date, 0, "h-1", "Habit", tz)
        db.dailyPlanEntryQueries.insertEntry(date, 1, "c-1", "Chore", tz)

        val plan = db.dailyPlanEntryQueries.selectPlan(date).executeAsList()
        assertEquals(listOf("Habit", "Chore"), plan.map { it.kind })
    }

    /**
     * The inversion of the old `selectPlanIsScopedByDateAndTz` (#385). The server has exactly one plan
     * per date (`user:{id}:daily_plan:{date}`, no zone) and reads the zone only to decide *which* date,
     * so a per-zone key modelled days the server cannot represent: a device whose zone flipped read the
     * cached day as empty. `tz` is now a recorded column and `(plan_date, position)` is the key — so a
     * second write to the same slot under another zone **replaces** rather than forking the day.
     */
    @Test
    fun aDayIsOnePlanRegardlessOfZone() {
        val db = inMemoryDefernoDatabase()
        db.dailyPlanEntryQueries.insertEntry(date, 0, "today", "Task", tz)
        db.dailyPlanEntryQueries.insertEntry("2026-06-07", 0, "tomorrow", "Task", tz)
        db.dailyPlanEntryQueries.insertEntry(date, 0, "same-slot-other-zone", "Task", "Europe/London")

        val plan = db.dailyPlanEntryQueries.selectPlan(date).executeAsList()
        assertEquals(listOf("same-slot-other-zone"), plan.map { it.item_id }, "one slot, replaced not forked")
        assertEquals("Europe/London", plan.single().tz, "the zone is recorded on the row")
    }

    /** An unknown token is preserved as-is; `kind` is nullable so it is never coerced on the way in. */
    @Test
    fun kindIsNullableSoAnAbsentTagIsPreservedNotCoerced() {
        val db = inMemoryDefernoDatabase()
        db.dailyPlanEntryQueries.insertEntry(date, 0, "x-1", null, tz)

        assertNull(db.dailyPlanEntryQueries.selectPlan(date).executeAsList().single().kind)
    }

    @Test
    fun fullSnapshotReconcileReplacesTheDayInOneTransaction() {
        val db = inMemoryDefernoDatabase()
        db.dailyPlanEntryQueries.insertEntry(date, 0, "stale-1", "Task", tz)
        db.dailyPlanEntryQueries.insertEntry(date, 1, "stale-2", "Task", tz)

        // The #22 plan reconcile shape: delete the day, re-insert the fresh ordered snapshot.
        db.transaction {
            db.dailyPlanEntryQueries.deletePlan(date)
            listOf("fresh-a", "fresh-b").forEachIndexed { index, itemId ->
                db.dailyPlanEntryQueries.insertEntry(date, index.toLong(), itemId, "Task", tz)
            }
        }

        assertEquals(
            listOf("fresh-a", "fresh-b"),
            db.dailyPlanEntryQueries.selectPlan(date).executeAsList().map { it.item_id },
        )
    }

    @Test
    fun deletePlanClearsOnlyThatDay() {
        val db = inMemoryDefernoDatabase()
        db.dailyPlanEntryQueries.insertEntry(date, 0, "today", "Task", tz)
        db.dailyPlanEntryQueries.insertEntry("2026-06-07", 0, "tomorrow", "Task", tz)

        db.dailyPlanEntryQueries.deletePlan(date)

        assertTrue(db.dailyPlanEntryQueries.selectPlan(date).executeAsList().isEmpty())
        assertEquals(1, db.dailyPlanEntryQueries.selectPlan("2026-06-07").executeAsList().size)
    }

    /**
     * The id-heal sweep (#185) stopped being Task-only in #385: a recurring definition can be planned,
     * so it can hold a slot pointing at a dead client id exactly as a Task can. Every day's slot follows.
     */
    @Test
    fun rekeyItemRepointsEverySlotAcrossDaysAndKinds() {
        val db = inMemoryDefernoDatabase()
        db.dailyPlanEntryQueries.insertEntry(date, 0, "client-h", "Habit", tz)
        db.dailyPlanEntryQueries.insertEntry("2026-06-07", 0, "client-h", "Habit", tz)
        db.dailyPlanEntryQueries.insertEntry(date, 1, "untouched", "Task", tz)

        db.dailyPlanEntryQueries.rekeyItem("canonical-h", "client-h")

        assertEquals(
            listOf("canonical-h", "untouched"),
            db.dailyPlanEntryQueries.selectPlan(date).executeAsList().map { it.item_id },
        )
        assertEquals(
            listOf("canonical-h"),
            db.dailyPlanEntryQueries.selectPlan("2026-06-07").executeAsList().map { it.item_id },
        )
    }
}
