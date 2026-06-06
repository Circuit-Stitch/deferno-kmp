package com.circuitstitch.deferno.core.database

import com.circuitstitch.deferno.core.database.sql.DefernoDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves the `dailyPlanEntry` schema + queries (issue #21): an ordered, date+tz-scoped plan that
 * reconciles as a full snapshot per `(plan_date, tz)` (#22) — delete the day, re-insert the ordered
 * set in one transaction.
 */
class DailyPlanEntryQueriesTest {
    private val date = "2026-06-06"
    private val tz = "America/New_York"

    @Test
    fun selectPlanReturnsEntriesOrderedByPosition() {
        val db = inMemoryDefernoDatabase()
        db.dailyPlanEntryQueries.insertEntry(date, tz, 1, "task-b")
        db.dailyPlanEntryQueries.insertEntry(date, tz, 0, "task-a")

        val plan = db.dailyPlanEntryQueries.selectPlan(date, tz).executeAsList()
        assertEquals(listOf("task-a", "task-b"), plan.map { it.task_id })
    }

    @Test
    fun selectPlanIsScopedByDateAndTz() {
        val db = inMemoryDefernoDatabase()
        db.dailyPlanEntryQueries.insertEntry(date, tz, 0, "today")
        db.dailyPlanEntryQueries.insertEntry("2026-06-07", tz, 0, "tomorrow")
        db.dailyPlanEntryQueries.insertEntry(date, "Europe/London", 0, "other-tz")

        val plan = db.dailyPlanEntryQueries.selectPlan(date, tz).executeAsList()
        assertEquals(listOf("today"), plan.map { it.task_id })
    }

    @Test
    fun fullSnapshotReconcileReplacesTheDayInOneTransaction() {
        val db = inMemoryDefernoDatabase()
        db.dailyPlanEntryQueries.insertEntry(date, tz, 0, "stale-1")
        db.dailyPlanEntryQueries.insertEntry(date, tz, 1, "stale-2")

        // The #22 plan reconcile shape: delete the day, re-insert the fresh ordered snapshot.
        db.transaction {
            db.dailyPlanEntryQueries.deletePlan(date, tz)
            listOf("fresh-a", "fresh-b").forEachIndexed { index, taskId ->
                db.dailyPlanEntryQueries.insertEntry(date, tz, index.toLong(), taskId)
            }
        }

        assertEquals(
            listOf("fresh-a", "fresh-b"),
            db.dailyPlanEntryQueries.selectPlan(date, tz).executeAsList().map { it.task_id },
        )
    }

    @Test
    fun deletePlanClearsOnlyThatDay() {
        val db = inMemoryDefernoDatabase()
        db.dailyPlanEntryQueries.insertEntry(date, tz, 0, "today")
        db.dailyPlanEntryQueries.insertEntry("2026-06-07", tz, 0, "tomorrow")

        db.dailyPlanEntryQueries.deletePlan(date, tz)

        assertTrue(db.dailyPlanEntryQueries.selectPlan(date, tz).executeAsList().isEmpty())
        assertEquals(1, db.dailyPlanEntryQueries.selectPlan("2026-06-07", tz).executeAsList().size)
    }
}
