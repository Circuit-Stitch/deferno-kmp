package com.circuitstitch.deferno.core.data.plan

import com.circuitstitch.deferno.core.data.outbox.FakeOutboxStore
import com.circuitstitch.deferno.core.data.outbox.OutboxMethod
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.PlanItemRef
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The plan write path (ADR-0001, #23): [OutboxPlanWriter] applies each plan intent optimistically to
 * the cached day ordering and enqueues its idempotent request. Run against the in-memory fakes.
 */
class OutboxPlanWriterTest {

    private val now = Instant.parse("2026-06-07T12:00:00Z")
    private val date = LocalDate(2026, 6, 7)
    private val tz = "America/Los_Angeles"

    private fun writer(plan: FakePlanLocalStore, outbox: FakeOutboxStore) =
        OutboxPlanWriter(plan, outbox, now = { now })

    @Test
    fun addAppendsOptimisticallyAndEnqueues() = runTest {
        val plan = FakePlanLocalStore()
        val outbox = FakeOutboxStore()

        writer(plan, outbox).add(PlanItemRef("t1", ItemKind.Task), date, tz)

        assertEquals(taskRefs("t1"), plan.currentPlan(date))
        val entry = outbox.all.single()
        assertEquals("plan:2026-06-07", entry.target)
        assertEquals(OutboxMethod.Post, entry.request.method)
        assertEquals(listOf("items", "plan", "add"), entry.request.path)
        assertEquals("""{"task_id":"t1","date":"2026-06-07","tz":"America/Los_Angeles"}""", entry.request.body)
    }

    @Test
    fun removeDropsOptimisticallyAndEnqueues() = runTest {
        val plan = FakePlanLocalStore(mapOf(date to taskRefs("t1", "t2")))
        val outbox = FakeOutboxStore()

        writer(plan, outbox).remove("t1", date, tz)

        assertEquals(taskRefs("t2"), plan.currentPlan(date))
        assertEquals(listOf("items", "plan", "remove"), outbox.all.single().request.path)
    }

    @Test
    fun reorderReplacesOptimisticallyAndEnqueues() = runTest {
        val plan = FakePlanLocalStore(mapOf(date to taskRefs("t1", "t2")))
        val outbox = FakeOutboxStore()

        writer(plan, outbox).reorder(taskRefs("t2", "t1"), date, tz)

        assertEquals(taskRefs("t2", "t1"), plan.currentPlan(date))
        val entry = outbox.all.single()
        assertEquals(listOf("items", "plan", "reorder"), entry.request.path)
        assertEquals("""{"task_ids":["t2","t1"],"date":"2026-06-07","tz":"America/Los_Angeles"}""", entry.request.body)
    }

    /**
     * The optimistic local write keeps each ref's kind (#385). A reorder is the drag-drop result, so it
     * carries whatever the Plan is showing; writing a Habit into the cached ordering tagged as a Task
     * would make the very next resolve look for it in the Task cache, find nothing, and drop the row —
     * the disappearance this issue fixes, reintroduced from the client's own write.
     */
    @Test
    fun theOptimisticOrderingKeepsEachRefsKind() = runTest {
        val plan = FakePlanLocalStore()
        val writer = writer(plan, FakeOutboxStore())
        val habit = PlanItemRef("h1", ItemKind.Habit)
        val task = PlanItemRef("t1", ItemKind.Task)

        writer.add(habit, date, tz)
        writer.reorder(listOf(task, habit), date, tz)

        assertEquals(listOf(task, habit), plan.currentPlan(date))
    }

    /** The zone is stamped on the day the write touched — recorded, never part of the key (#385). */
    @Test
    fun theWriteRecordsTheZoneItWasMadeUnder() = runTest {
        val plan = FakePlanLocalStore()

        writer(plan, FakeOutboxStore()).add(PlanItemRef("t1", ItemKind.Task), date, tz)

        assertEquals(tz, plan.zoneOf(date))
    }
}
