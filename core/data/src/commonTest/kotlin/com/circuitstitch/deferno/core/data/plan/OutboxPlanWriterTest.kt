package com.circuitstitch.deferno.core.data.plan

import com.circuitstitch.deferno.core.data.outbox.FakeOutboxStore
import com.circuitstitch.deferno.core.data.outbox.OutboxMethod
import com.circuitstitch.deferno.core.model.TaskId
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

        writer(plan, outbox).add(TaskId("t1"), date, tz)

        assertEquals(listOf(TaskId("t1")), plan.currentPlan(date, tz))
        val entry = outbox.all.single()
        assertEquals("plan:2026-06-07:America/Los_Angeles", entry.target)
        assertEquals(OutboxMethod.Post, entry.request.method)
        assertEquals(listOf("tasks", "plan", "add"), entry.request.path)
        assertEquals("""{"task_id":"t1","date":"2026-06-07","tz":"America/Los_Angeles"}""", entry.request.body)
    }

    @Test
    fun removeDropsOptimisticallyAndEnqueues() = runTest {
        val plan = FakePlanLocalStore(mapOf(FakePlanLocalStore.PlanKey(date, tz) to listOf(TaskId("t1"), TaskId("t2"))))
        val outbox = FakeOutboxStore()

        writer(plan, outbox).remove(TaskId("t1"), date, tz)

        assertEquals(listOf(TaskId("t2")), plan.currentPlan(date, tz))
        assertEquals(listOf("tasks", "plan", "remove"), outbox.all.single().request.path)
    }

    @Test
    fun reorderReplacesOptimisticallyAndEnqueues() = runTest {
        val plan = FakePlanLocalStore(mapOf(FakePlanLocalStore.PlanKey(date, tz) to listOf(TaskId("t1"), TaskId("t2"))))
        val outbox = FakeOutboxStore()

        writer(plan, outbox).reorder(listOf(TaskId("t2"), TaskId("t1")), date, tz)

        assertEquals(listOf(TaskId("t2"), TaskId("t1")), plan.currentPlan(date, tz))
        val entry = outbox.all.single()
        assertEquals(listOf("tasks", "plan", "reorder"), entry.request.path)
        assertEquals("""{"task_ids":["t2","t1"],"date":"2026-06-07","tz":"America/Los_Angeles"}""", entry.request.body)
    }
}
