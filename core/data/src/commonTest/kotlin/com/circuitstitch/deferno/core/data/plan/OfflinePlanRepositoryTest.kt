package com.circuitstitch.deferno.core.data.plan

import app.cash.turbine.test
import com.circuitstitch.deferno.core.data.item.FakeItemLocalStore
import com.circuitstitch.deferno.core.data.item.cacheOf
import com.circuitstitch.deferno.core.data.item.cached
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
 *
 * **The resolve stopped consulting the ref's kind at #422.** #385 fixed the reported blank Plan by
 * tagging each ref with its kind and dispatching to the one store that could hold it, which left two
 * ways to lose a row: an unrecognised kind token had no store to ask, and a mis-tagged ref asked the
 * wrong one. One cache keyed by id, where the row itself says which kind it is, makes neither failure
 * expressible.
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
        items: FakeItemLocalStore = FakeItemLocalStore(),
    ) = OfflinePlanRepository(local, remote, items)

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
        val items = FakeItemLocalStore(
            cacheOf(
                task("a", sequence = 1).cached(),
                task("b", sequence = 2).cached(),
                task("c", sequence = 3).cached(),
            ),
        )

        repo(local, FakePlanRemoteSource(), items).observePlan(date, tz).test {
            assertEquals(listOf("c", "a", "b"), awaitItem().map { it.item.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * **The regression test for the reported bug (#385).** The user's day held one Habit and one Chore
     * and the Plan rendered blank on every platform. This resolve used to join the day's ordering
     * against the Task cache alone, so a recurring row matched nothing and was dropped with no
     * diagnostic. It resolves by id now, and the cached row answers with its own kind.
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
        val items = FakeItemLocalStore(
            cacheOf(
                task("t1").cached(),
                habit("h1", "Take a Walk").cached(),
                chore("c1", "Take shot").cached(),
                event("e1", "Standup").cached(),
            ),
        )

        repo(local, FakePlanRemoteSource(), items).observePlan(date, tz).test {
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
        val items = FakeItemLocalStore(cacheOf(task("t1").cached(), habit("h1").cached()))

        repo(local, FakePlanRemoteSource(), items).observePlan(date, tz).test {
            val rows = awaitItem()
            assertEquals(TaskId("t1"), rows[0].task?.id)
            assertNull(rows[1].task, "a Habit row has no Task to carry")
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * **The ref's own kind no longer decides anything (#422).** A ref whose kind token did not decode
     * used to have no store to ask and was dropped; a mis-tagged one asked a store that could not hold
     * it and was dropped just the same. Both are #385's defect class, and both are answered at the root
     * by resolving on the id and letting the stored row say what kind it is.
     */
    @Test
    fun aRefResolvesOnItsIdWhateverKindItClaims() = runTest {
        val local = FakePlanLocalStore(
            mapOf(
                date to listOf(
                    PlanItemRef("t1", kind = null), // an unrecognised server token
                    PlanItemRef("h1", ItemKind.Task), // mis-tagged: it is really a Habit
                    PlanItemRef("t2", ItemKind.Task),
                ),
            ),
        )
        val items = FakeItemLocalStore(cacheOf(task("t1").cached(), habit("h1").cached(), task("t2").cached()))

        repo(local, FakePlanRemoteSource(), items).observePlan(date, tz).test {
            val rows = awaitItem()
            assertEquals(listOf("t1", "h1", "t2"), rows.map { it.item.id })
            // And the resolved row reports the kind it actually is, not the one the ref claimed.
            assertEquals(ItemKind.Habit, rows[1].item.kind)
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
        val items = FakeItemLocalStore(cacheOf(task("a").cached(), task("b").cached()))

        repo(local, FakePlanRemoteSource(), items).observePlan(date, tz).test {
            // Both uncached refs are skipped; the resolvable rows stay in plan order. The id is real but
            // its row has not been pulled yet, and skipping lets the rest of the day render.
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
        val items = FakeItemLocalStore(cacheOf(task("a").cached()))
        val remote = FakePlanRemoteSource(plan = emptyList()) // reachable, empty plan

        val repository = repo(local, remote, items)
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
        val items = FakeItemLocalStore(cacheOf(task("a").cached(), task("b").cached()))
        val repository = repo(local, remote, items)

        repository.observePlan(date, tz).test {
            assertTrue(awaitItem().isEmpty())

            remote.plan = taskRefs("b", "a")
            repository.refreshPlan(date, tz)
            assertEquals(listOf("b", "a"), awaitItem().map { it.item.id })

            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * The resolve is a live join between the day's ordering and the item cache: a recurring definition
     * arriving *after* the plan already named it makes the row appear, with no second plan refresh.
     */
    @Test
    fun observePlanReEmitsWhenARecurringDefinitionLandsInTheCache() = runTest {
        val local = FakePlanLocalStore(mapOf(date to listOf(PlanItemRef("h1", ItemKind.Habit))))
        val items = FakeItemLocalStore()

        repo(local, FakePlanRemoteSource(), items).observePlan(date, tz).test {
            assertTrue(awaitItem().isEmpty(), "not cached yet — skipped, not stalled on")

            items.upsert(habit("h1", "Take a Walk").cached())

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
