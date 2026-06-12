package com.circuitstitch.deferno.core.data.plan

import app.cash.turbine.test
import com.circuitstitch.deferno.core.data.task.FakeTaskLocalStore
import com.circuitstitch.deferno.core.model.HydrationState
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The plan reconcile + resolve behaviour of [OfflinePlanRepository] (ADR-0001, #22), against the
 * in-memory fakes. Covers: a full-snapshot per-day replace of the ordering; resolving the ordered
 * ids back to cached domain Tasks *in plan order*; gracefully skipping a plan entry whose Task is
 * not yet cached; the offline-first no-op on a failed refresh; and that the observed plan re-emits.
 */
class OfflinePlanRepositoryTest {

    private val date = LocalDate.parse("2026-06-06")
    private val tz = "America/Chicago"
    private val created = Instant.parse("2026-05-20T16:11:42Z")

    private fun task(id: String, sequence: Long = 1) = Task(
        id = TaskId(id),
        orgSlug = "u-e4h2qk",
        title = "task-$id",
        workingState = WorkingState.Open,
        sequence = sequence,
        dateCreated = created,
        hydration = HydrationState.Summary,
    )

    private fun repo(
        local: FakePlanLocalStore = FakePlanLocalStore(),
        remote: FakePlanRemoteSource = FakePlanRemoteSource(),
        tasks: FakeTaskLocalStore = FakeTaskLocalStore(),
    ) = OfflinePlanRepository(local, remote, tasks)

    @Test
    fun refreshPlanReplacesTheDaysOrdering() = runTest {
        val local = FakePlanLocalStore()
        val remote = FakePlanRemoteSource(plan = listOf(TaskId("c"), TaskId("a"), TaskId("b")))

        repo(local, remote).refreshPlan(date, tz)

        assertEquals(
            listOf(TaskId("c"), TaskId("a"), TaskId("b")),
            local.all.getValue(FakePlanLocalStore.PlanKey(date, tz)),
        )
    }

    @Test
    fun refreshPlanIsAFullReplaceNotAMerge() = runTest {
        val key = FakePlanLocalStore.PlanKey(date, tz)
        val local = FakePlanLocalStore(mapOf(key to listOf(TaskId("old1"), TaskId("old2"))))
        val remote = FakePlanRemoteSource(plan = listOf(TaskId("new")))

        repo(local, remote).refreshPlan(date, tz)

        assertEquals(listOf(TaskId("new")), local.all.getValue(key))
    }

    @Test
    fun observePlanResolvesOrderedIdsToCachedTasksInPlanOrder() = runTest {
        val key = FakePlanLocalStore.PlanKey(date, tz)
        // Plan order (c, a, b) deliberately differs from the Tasks' sequence order.
        val local = FakePlanLocalStore(mapOf(key to listOf(TaskId("c"), TaskId("a"), TaskId("b"))))
        val tasks = FakeTaskLocalStore(
            mapOf(
                TaskId("a") to task("a", sequence = 1),
                TaskId("b") to task("b", sequence = 2),
                TaskId("c") to task("c", sequence = 3),
            ),
        )

        repo(local, FakePlanRemoteSource(), tasks).observePlan(date, tz).test {
            assertEquals(listOf(TaskId("c"), TaskId("a"), TaskId("b")), awaitItem().map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun observePlanSkipsEntriesWhoseTaskIsNotYetCached() = runTest {
        val key = FakePlanLocalStore.PlanKey(date, tz)
        val local = FakePlanLocalStore(mapOf(key to listOf(TaskId("a"), TaskId("missing"), TaskId("b"))))
        val tasks = FakeTaskLocalStore(
            mapOf(TaskId("a") to task("a"), TaskId("b") to task("b")),
        )

        repo(local, FakePlanRemoteSource(), tasks).observePlan(date, tz).test {
            // "missing" is skipped; the resolvable tasks stay in plan order.
            assertEquals(listOf(TaskId("a"), TaskId("b")), awaitItem().map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun aFailedRefreshLeavesTheCachedPlanIntact() = runTest {
        val key = FakePlanLocalStore.PlanKey(date, tz)
        val local = FakePlanLocalStore(mapOf(key to listOf(TaskId("a"))))
        val remote = FakePlanRemoteSource(failNext = true)

        repo(local, remote).refreshPlan(date, tz)

        assertEquals(listOf(TaskId("a")), local.all.getValue(key))
    }

    @Test
    fun refreshWithAGenuinelyEmptyPlanClearsTheDay() = runTest {
        // A reachable server with an empty plan (Available, empty) clears the day — distinct from an
        // Unavailable pull (above), which leaves the cached ordering intact.
        val key = FakePlanLocalStore.PlanKey(date, tz)
        val local = FakePlanLocalStore(mapOf(key to listOf(TaskId("a"))))
        val tasks = FakeTaskLocalStore(mapOf(TaskId("a") to task("a")))
        val remote = FakePlanRemoteSource(plan = emptyList()) // reachable, empty plan

        val repository = repo(local, remote, tasks)
        repository.observePlan(date, tz).test {
            assertEquals(listOf(TaskId("a")), awaitItem().map { it.id })
            repository.refreshPlan(date, tz)
            assertTrue(awaitItem().isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun observePlanReEmitsAfterARefresh() = runTest {
        val local = FakePlanLocalStore()
        val remote = FakePlanRemoteSource()
        val tasks = FakeTaskLocalStore(
            mapOf(TaskId("a") to task("a"), TaskId("b") to task("b")),
        )
        val repository = repo(local, remote, tasks)

        repository.observePlan(date, tz).test {
            assertTrue(awaitItem().isEmpty())

            remote.plan = listOf(TaskId("b"), TaskId("a"))
            repository.refreshPlan(date, tz)
            assertEquals(listOf(TaskId("b"), TaskId("a")), awaitItem().map { it.id })

            cancelAndIgnoreRemainingEvents()
        }
    }
}
