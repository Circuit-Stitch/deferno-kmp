package com.circuitstitch.deferno.core.data.plan

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.turbine.test
import com.circuitstitch.deferno.core.data.create.FakePendingCreateStore
import com.circuitstitch.deferno.core.data.task.FakeTaskRemoteSource
import com.circuitstitch.deferno.core.data.task.OfflineTaskRepository
import com.circuitstitch.deferno.core.data.task.SqlDelightTaskLocalStore
import com.circuitstitch.deferno.core.database.sql.DefernoDatabase
import com.circuitstitch.deferno.core.model.HydrationState
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
 * The real-SQLite integration test for the plan path (#22, ADR-0006 JVM-fast path). Proves
 * [SqlDelightPlanLocalStore]'s ordered observe + atomic per-day replace round-trip through a genuine
 * `DefernoDatabase`, and that the full plan flow — reconcile the Tasks via the Task repository, then
 * the plan ordering — resolves to domain Tasks in plan order end to end through real SQLite.
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

    @Test
    fun replacePlanStoresOrderedEntriesAndObserveReturnsThemInOrder() = runTest {
        val store = SqlDelightPlanLocalStore(newDb(), Dispatchers.Default)

        store.replacePlan(date, tz, listOf(TaskId("c"), TaskId("a"), TaskId("b")))

        store.observePlan(date, tz).test {
            assertEquals(listOf(TaskId("c"), TaskId("a"), TaskId("b")), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun currentPlanReadsTheOrderedSnapshotForTheWritePath() = runTest {
        // The non-Flow read the offline write path (#23) uses to apply a plan mutation optimistically.
        val store = SqlDelightPlanLocalStore(newDb(), Dispatchers.Default)
        assertEquals(emptyList(), store.currentPlan(date, tz)) // empty day
        store.replacePlan(date, tz, listOf(TaskId("c"), TaskId("a"), TaskId("b")))

        assertEquals(listOf(TaskId("c"), TaskId("a"), TaskId("b")), store.currentPlan(date, tz))
    }

    @Test
    fun replacePlanIsAFullPerDayReplace() = runTest {
        val store = SqlDelightPlanLocalStore(newDb(), Dispatchers.Default)
        store.replacePlan(date, tz, listOf(TaskId("old1"), TaskId("old2")))

        store.replacePlan(date, tz, listOf(TaskId("new")))

        store.observePlan(date, tz).test {
            assertEquals(listOf(TaskId("new")), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun replaceForOneDayDoesNotTouchAnotherDay() = runTest {
        val store = SqlDelightPlanLocalStore(newDb(), Dispatchers.Default)
        val otherDay = LocalDate.parse("2026-06-07")
        store.replacePlan(date, tz, listOf(TaskId("a")))
        store.replacePlan(otherDay, tz, listOf(TaskId("b")))

        store.replacePlan(date, tz, emptyList())

        store.observePlan(otherDay, tz).test {
            assertEquals(listOf(TaskId("b")), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun fullPlanFlowResolvesToDomainTasksInPlanOrderThroughRealSqlite() = runTest {
        val db = newDb()
        val taskStore = SqlDelightTaskLocalStore(db, Dispatchers.Default)
        val planStore = SqlDelightPlanLocalStore(db, Dispatchers.Default)

        // Reconcile the Tasks first (sequence order differs from plan order).
        OfflineTaskRepository(
            taskStore,
            FakeTaskRemoteSource(snapshot = listOf(task("a", 1), task("b", 2), task("c", 3))),
            FakePendingCreateStore(),
        ).refresh()
        // Then the plan ordering, plus one entry whose Task isn't cached (must be skipped).
        val plan = OfflinePlanRepository(
            planStore,
            FakePlanRemoteSource(plan = listOf(TaskId("c"), TaskId("a"), TaskId("uncached"), TaskId("b"))),
            taskStore,
        )
        plan.refreshPlan(date, tz)

        plan.observePlan(date, tz).test {
            assertEquals(listOf(TaskId("c"), TaskId("a"), TaskId("b")), awaitItem().map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }
}
