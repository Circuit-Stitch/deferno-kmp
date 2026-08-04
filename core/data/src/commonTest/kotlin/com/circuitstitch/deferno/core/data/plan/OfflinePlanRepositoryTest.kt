package com.circuitstitch.deferno.core.data.plan

import app.cash.turbine.test
import com.circuitstitch.deferno.core.data.create.FakeChoreLocalStore
import com.circuitstitch.deferno.core.data.create.FakeEventLocalStore
import com.circuitstitch.deferno.core.data.create.FakeHabitLocalStore
import com.circuitstitch.deferno.core.data.task.FakeTaskLocalStore
import com.circuitstitch.deferno.core.model.Chore
import com.circuitstitch.deferno.core.model.ChoreId
import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.Event
import com.circuitstitch.deferno.core.model.EventId
import com.circuitstitch.deferno.core.model.Habit
import com.circuitstitch.deferno.core.model.HabitId
import com.circuitstitch.deferno.core.model.HydrationState
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.PlanItemRef
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The plan reconcile + resolve behaviour of [OfflinePlanRepository] (ADR-0001, #22, #385), against the
 * in-memory fakes. Covers: a full-snapshot per-day replace of the ordering; resolving the ordered refs
 * back to cached domain rows *in plan order* **across all four kinds**; gracefully skipping a ref that
 * cannot be resolved; the offline-first no-op on a failed refresh; and that the observed plan re-emits.
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

    private fun habit(id: String, title: String = "habit-$id") = Habit(
        id = HabitId(id),
        orgSlug = "u-e4h2qk",
        title = title,
        definitionState = DefinitionState.Active,
        dateCreated = created,
    )

    private fun chore(id: String, title: String = "chore-$id") = Chore(
        id = ChoreId(id),
        orgSlug = "u-e4h2qk",
        title = title,
        definitionState = DefinitionState.Active,
        dateCreated = created,
    )

    private fun event(id: String, title: String = "event-$id") = Event(
        id = EventId(id),
        orgSlug = "u-e4h2qk",
        title = title,
        definitionState = DefinitionState.Active,
        dateCreated = created,
    )

    private fun repo(
        local: FakePlanLocalStore = FakePlanLocalStore(),
        remote: FakePlanRemoteSource = FakePlanRemoteSource(),
        tasks: FakeTaskLocalStore = FakeTaskLocalStore(),
        habits: FakeHabitLocalStore = FakeHabitLocalStore(),
        chores: FakeChoreLocalStore = FakeChoreLocalStore(),
        events: FakeEventLocalStore = FakeEventLocalStore(),
    ) = OfflinePlanRepository(local, remote, tasks, habits, chores, events)

    @Test
    fun refreshPlanReplacesTheDaysOrdering() = runTest {
        val local = FakePlanLocalStore()
        val remote = FakePlanRemoteSource(plan = taskRefs("c", "a", "b"))

        repo(local, remote).refreshPlan(date, tz)

        assertEquals(taskRefs("c", "a", "b"), local.all.getValue(date))
    }

    @Test
    fun refreshPlanIsAFullReplaceNotAMerge() = runTest {
        val local = FakePlanLocalStore(mapOf(date to taskRefs("old1", "old2")))
        val remote = FakePlanRemoteSource(plan = taskRefs("new"))

        repo(local, remote).refreshPlan(date, tz)

        assertEquals(taskRefs("new"), local.all.getValue(date))
    }

    @Test
    fun observePlanResolvesOrderedIdsToCachedTasksInPlanOrder() = runTest {
        // Plan order (c, a, b) deliberately differs from the Tasks' sequence order.
        val local = FakePlanLocalStore(mapOf(date to taskRefs("c", "a", "b")))
        val tasks = FakeTaskLocalStore(
            mapOf(
                TaskId("a") to task("a", sequence = 1),
                TaskId("b") to task("b", sequence = 2),
                TaskId("c") to task("c", sequence = 3),
            ),
        )

        repo(local, FakePlanRemoteSource(), tasks).observePlan(date, tz).test {
            assertEquals(listOf("c", "a", "b"), awaitItem().map { it.item.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * **The regression test for the reported bug (#385).** The user's day held one Habit and one Chore
     * and the Plan rendered blank on every platform. This resolve used to join the day's ordering
     * against the Task cache alone, so a recurring row matched nothing and was dropped with no
     * diagnostic. Each ref now carries its kind and is looked up in the one store that can hold it.
     */
    @Test
    fun observePlanResolvesEveryKindNotJustTasks() = runTest {
        val local = FakePlanLocalStore(
            mapOf(
                date to listOf(
                    PlanItemRef("h1", ItemKind.Habit),
                    PlanItemRef("t1", ItemKind.Task),
                    PlanItemRef("c1", ItemKind.Chore),
                    PlanItemRef("e1", ItemKind.Event),
                ),
            ),
        )

        repo(
            local,
            FakePlanRemoteSource(),
            tasks = FakeTaskLocalStore(mapOf(TaskId("t1") to task("t1"))),
            habits = FakeHabitLocalStore(mapOf(HabitId("h1") to habit("h1", "Take a Walk"))),
            chores = FakeChoreLocalStore(mapOf(ChoreId("c1") to chore("c1", "Take shot"))),
            events = FakeEventLocalStore(mapOf(EventId("e1") to event("e1", "Standup"))),
        ).observePlan(date, tz).test {
            val rows = awaitItem()
            assertEquals(listOf("h1", "t1", "c1", "e1"), rows.map { it.item.id }, "in plan order")
            assertEquals(
                listOf(ItemKind.Habit, ItemKind.Task, ItemKind.Chore, ItemKind.Event),
                rows.map { it.item.kind },
            )
            assertEquals(listOf("Take a Walk", "task-t1", "Take shot", "Standup"), rows.map { it.item.title })
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * `task` is populated for **exactly** the Task arm. It is what the four shipped Task-only
     * affordances read (the ✦ suggestion, the deadline subline, the overdue footer), and `null` is the
     * honest answer for a recurring row rather than a missing value to substitute a default for.
     */
    @Test
    fun onlyATaskRowCarriesItsConcreteTask() = runTest {
        val local = FakePlanLocalStore(
            mapOf(date to listOf(PlanItemRef("t1", ItemKind.Task), PlanItemRef("h1", ItemKind.Habit))),
        )

        repo(
            local,
            FakePlanRemoteSource(),
            tasks = FakeTaskLocalStore(mapOf(TaskId("t1") to task("t1"))),
            habits = FakeHabitLocalStore(mapOf(HabitId("h1") to habit("h1"))),
        ).observePlan(date, tz).test {
            val rows = awaitItem()
            assertEquals(TaskId("t1"), rows[0].task?.id)
            assertNull(rows[1].task, "a Habit row has no Task to carry")
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * A ref whose kind did not decode is skipped, **not** coerced to Task. Guessing Task is precisely
     * how a Habit came to be looked up in the Task cache and vanish, so an unknown token is surfaced as
     * unresolvable rather than mis-resolved — even when an id of that name happens to be cached.
     */
    @Test
    fun aRefWithNoKindIsSkippedNotTreatedAsATask() = runTest {
        val local = FakePlanLocalStore(
            mapOf(date to listOf(PlanItemRef("t1", kind = null), PlanItemRef("t2", ItemKind.Task))),
        )
        val tasks = FakeTaskLocalStore(mapOf(TaskId("t1") to task("t1"), TaskId("t2") to task("t2")))

        repo(local, FakePlanRemoteSource(), tasks).observePlan(date, tz).test {
            assertEquals(listOf("t2"), awaitItem().map { it.item.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun observePlanSkipsEntriesWhoseItemIsNotYetCached() = runTest {
        val local = FakePlanLocalStore(
            mapOf(
                date to listOf(
                    PlanItemRef("a", ItemKind.Task),
                    PlanItemRef("missing", ItemKind.Task),
                    PlanItemRef("h-missing", ItemKind.Habit),
                    PlanItemRef("b", ItemKind.Task),
                ),
            ),
        )
        val tasks = FakeTaskLocalStore(
            mapOf(TaskId("a") to task("a"), TaskId("b") to task("b")),
        )

        repo(local, FakePlanRemoteSource(), tasks).observePlan(date, tz).test {
            // Both uncached refs are skipped; the resolvable rows stay in plan order.
            assertEquals(listOf("a", "b"), awaitItem().map { it.item.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun aFailedRefreshLeavesTheCachedPlanIntact() = runTest {
        val local = FakePlanLocalStore(mapOf(date to taskRefs("a")))
        val remote = FakePlanRemoteSource(failNext = true)

        repo(local, remote).refreshPlan(date, tz)

        assertEquals(taskRefs("a"), local.all.getValue(date))
    }

    @Test
    fun refreshWithAGenuinelyEmptyPlanClearsTheDay() = runTest {
        // A reachable server with an empty plan (Available, empty) clears the day — distinct from an
        // Unavailable pull (above), which leaves the cached ordering intact.
        val local = FakePlanLocalStore(mapOf(date to taskRefs("a")))
        val tasks = FakeTaskLocalStore(mapOf(TaskId("a") to task("a")))
        val remote = FakePlanRemoteSource(plan = emptyList()) // reachable, empty plan

        val repository = repo(local, remote, tasks)
        repository.observePlan(date, tz).test {
            assertEquals(listOf("a"), awaitItem().map { it.item.id })
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

            remote.plan = taskRefs("b", "a")
            repository.refreshPlan(date, tz)
            assertEquals(listOf("b", "a"), awaitItem().map { it.item.id })

            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * The resolve is a live join across all five stores: a recurring definition arriving in its own
     * cache *after* the plan already named it makes the row appear, with no second plan refresh.
     */
    @Test
    fun observePlanReEmitsWhenARecurringDefinitionLandsInItsCache() = runTest {
        val local = FakePlanLocalStore(mapOf(date to listOf(PlanItemRef("h1", ItemKind.Habit))))
        val habits = FakeHabitLocalStore()

        repo(local, FakePlanRemoteSource(), habits = habits).observePlan(date, tz).test {
            assertTrue(awaitItem().isEmpty(), "not cached yet — skipped, not stalled on")

            habits.upsert(habit("h1", "Take a Walk"))

            assertEquals(listOf("Take a Walk"), awaitItem().map { it.item.title })
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** The zone rides the replace as a recorded fact; the day it writes is keyed on the date alone. */
    @Test
    fun aRefreshRecordsTheZoneItWasCapturedUnder() = runTest {
        val local = FakePlanLocalStore()

        repo(local, FakePlanRemoteSource(plan = taskRefs("a"))).refreshPlan(date, tz)

        assertEquals(tz, local.zoneOf(date))
    }
}
