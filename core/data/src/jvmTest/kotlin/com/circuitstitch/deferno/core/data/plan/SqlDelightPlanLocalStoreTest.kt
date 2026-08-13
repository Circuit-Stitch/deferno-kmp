package com.circuitstitch.deferno.core.data.plan

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.turbine.test
import com.circuitstitch.deferno.core.data.item.SqlDelightItemLocalStore
import com.circuitstitch.deferno.core.data.item.cached
import com.circuitstitch.deferno.core.database.sql.DefernoDatabase
import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.Habit
import com.circuitstitch.deferno.core.model.HabitId
import com.circuitstitch.deferno.core.model.HydrationState
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.PlanItemRef
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The real-SQLite integration test for the plan path (#22, #385, ADR-0006 JVM-fast path). Proves
 * [SqlDelightPlanLocalStore]'s ordered observe + atomic per-day replace round-trip through a genuine
 * `DefernoDatabase`, that the kind tag survives the round-trip, and that the full plan flow — cache
 * the items, then the plan ordering — resolves to domain rows in plan order end to end through real
 * SQLite.
 */
class SqlDelightPlanLocalStoreTest {

    private val date = LocalDate.parse("2026-06-06")
    private val tz = "America/Chicago"
    private val created = Instant.parse("2026-05-20T16:11:42Z")

    private fun newDb() = DefernoDatabase(
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { DefernoDatabase.Schema.create(it) },
    )

    private fun task(id: String, sequence: Long) = Task(
        id = TaskId(id),
        orgSlug = "u-e4h2qk",
        title = "task-$id",
        workingState = WorkingState.Open,
        sequence = sequence,
        dateCreated = created,
        hydration = HydrationState.Summary,
    )

    private fun habit(id: String, title: String = "habit-$id") = Habit(
        id = HabitId(id),
        orgSlug = "u-e4h2qk",
        title = title,
        definitionState = DefinitionState.Active,
        dateCreated = created,
    )

    @Test
    fun replacePlanStoresOrderedEntriesAndObserveReturnsThemInOrder() = runTest {
        val store = SqlDelightPlanLocalStore(newDb(), Dispatchers.Default)

        store.replacePlan(date, tz, taskRefs("c", "a", "b"))

        store.observePlan(date).test {
            assertEquals(taskRefs("c", "a", "b"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * The kind is what the resolve dispatches on, so it has to survive the table (#385) — a row that
     * came back untagged would be skipped as unresolvable no matter how well the ordering round-tripped.
     */
    @Test
    fun theKindTagRoundTripsThroughTheTable() = runTest {
        val store = SqlDelightPlanLocalStore(newDb(), Dispatchers.Default)
        val refs = listOf(
            PlanItemRef("h1", ItemKind.Habit),
            PlanItemRef("t1", ItemKind.Task),
            PlanItemRef("c1", ItemKind.Chore),
            PlanItemRef("e1", ItemKind.Event),
        )

        store.replacePlan(date, tz, refs)

        assertEquals(refs, store.currentPlan(date))
    }

    /**
     * An unrecognised `kind` token decodes as `null` rather than being coerced. Defaulting it to Task is
     * exactly the defect #385 exists to fix, so the read surfaces the row as unresolvable instead.
     */
    @Test
    fun anUnknownKindTokenReadsBackAsNullNotAsATask() = runTest {
        val db = newDb()
        val store = SqlDelightPlanLocalStore(db, Dispatchers.Default)
        db.dailyPlanEntryQueries.insertEntry(date.toString(), 0L, "x1", "Sprocket", tz)

        assertEquals(listOf(PlanItemRef("x1", kind = null)), store.currentPlan(date))
    }

    @Test
    fun currentPlanReadsTheOrderedSnapshotForTheWritePath() = runTest {
        // The non-Flow read the offline write path (#23) uses to apply a plan mutation optimistically.
        val store = SqlDelightPlanLocalStore(newDb(), Dispatchers.Default)
        assertEquals(emptyList(), store.currentPlan(date)) // empty day
        store.replacePlan(date, tz, taskRefs("c", "a", "b"))

        assertEquals(taskRefs("c", "a", "b"), store.currentPlan(date))
    }

    @Test
    fun replacePlanIsAFullPerDayReplace() = runTest {
        val store = SqlDelightPlanLocalStore(newDb(), Dispatchers.Default)
        store.replacePlan(date, tz, taskRefs("old1", "old2"))

        store.replacePlan(date, tz, taskRefs("new"))

        store.observePlan(date).test {
            assertEquals(taskRefs("new"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun replaceForOneDayDoesNotTouchAnotherDay() = runTest {
        val store = SqlDelightPlanLocalStore(newDb(), Dispatchers.Default)
        val otherDay = LocalDate.parse("2026-06-07")
        store.replacePlan(date, tz, taskRefs("a"))
        store.replacePlan(otherDay, tz, taskRefs("b"))

        store.replacePlan(date, tz, emptyList())

        store.observePlan(otherDay).test {
            assertEquals(taskRefs("b"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * A day is one plan whatever zone it was captured under (#385). The old `(plan_date, tz, position)`
     * key manufactured a second plan per zone — days the server cannot represent — so a zone flip read
     * as an empty day. The zone written last is simply recorded on the rows.
     */
    @Test
    fun aReplaceUnderADifferentZoneReplacesTheSameDay() = runTest {
        val store = SqlDelightPlanLocalStore(newDb(), Dispatchers.Default)
        store.replacePlan(date, "America/Chicago", taskRefs("a", "b"))

        store.replacePlan(date, "Europe/Berlin", taskRefs("c"))

        assertEquals(taskRefs("c"), store.currentPlan(date))
    }

    @Test
    fun rekeyItemRepointsEverySlotForAnyKind() = runTest {
        val store = SqlDelightPlanLocalStore(newDb(), Dispatchers.Default)
        val otherDay = LocalDate.parse("2026-06-07")
        store.replacePlan(date, tz, listOf(PlanItemRef("client-h", ItemKind.Habit)))
        store.replacePlan(otherDay, tz, listOf(PlanItemRef("client-h", ItemKind.Habit)))

        store.rekeyItem("client-h", "canonical-h")

        assertEquals(listOf(PlanItemRef("canonical-h", ItemKind.Habit)), store.currentPlan(date))
        assertEquals(listOf(PlanItemRef("canonical-h", ItemKind.Habit)), store.currentPlan(otherDay))
    }

    @Test
    fun fullPlanFlowResolvesToDomainRowsInPlanOrderThroughRealSqlite() = runTest {
        val db = newDb()
        val items = SqlDelightItemLocalStore(db, Dispatchers.Default)
        val planStore = SqlDelightPlanLocalStore(db, Dispatchers.Default)

        // Cache the items first (sequence order differs from plan order).
        listOf(task("a", 1), task("b", 2), task("c", 3)).forEach { items.upsert(it.cached()) }
        items.upsert(habit("h1", "Take a Walk").cached())
        // Then the plan ordering: a cross-kind day, plus one entry whose row isn't cached (skipped).
        val plan = OfflinePlanRepository(
            planStore,
            FakePlanRemoteSource(
                plan = listOf(
                    PlanItemRef("c", ItemKind.Task),
                    PlanItemRef("h1", ItemKind.Habit),
                    PlanItemRef("a", ItemKind.Task),
                    PlanItemRef("uncached", ItemKind.Task),
                    PlanItemRef("b", ItemKind.Task),
                ),
            ),
            items,
        )
        plan.refreshPlan(date, tz)

        plan.observePlan(date, tz).test {
            val rows = awaitItem()
            assertEquals(listOf("c", "h1", "a", "b"), rows.map { it.item.id })
            assertEquals(
                listOf(ItemKind.Task, ItemKind.Habit, ItemKind.Task, ItemKind.Task),
                rows.map { it.item.kind },
            )
            cancelAndIgnoreRemainingEvents()
        }
    }
}
