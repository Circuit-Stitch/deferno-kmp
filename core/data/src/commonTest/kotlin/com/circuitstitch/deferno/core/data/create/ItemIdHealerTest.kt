package com.circuitstitch.deferno.core.data.create

import com.circuitstitch.deferno.core.data.outbox.OutboxMethod
import com.circuitstitch.deferno.core.data.outbox.OutboxRequest
import com.circuitstitch.deferno.core.data.outbox.FakeOutboxStore
import com.circuitstitch.deferno.core.data.plan.FakePlanLocalStore
import com.circuitstitch.deferno.core.data.task.FakeTaskLocalStore
import com.circuitstitch.deferno.core.model.HabitId
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * The id-heal path (#185): when the server assigns a *different* canonical id than the client supplied
 * (the rare divergence — the backend normally honors the client id), [ItemIdHealer] re-points every
 * local reference. Proves the Item row, parent/child refs, plan rows, and queued outbox entries all
 * follow the client → canonical id, and that an equal id is a no-op.
 */
class ItemIdHealerTest {

    private val created = Instant.parse("2026-06-07T12:00:00Z")
    private val day = LocalDate(2026, 6, 7)

    private fun task(id: String, parentId: String? = null, children: List<String> = emptyList()) = Task(
        id = TaskId(id),
        orgSlug = "u-test",
        title = "task-$id",
        workingState = WorkingState.Open,
        parentId = parentId?.let(::TaskId),
        children = children.map(::TaskId),
        dateCreated = created,
    )

    private class Fixture {
        val taskStore = FakeTaskLocalStore()
        val habitStore = FakeHabitLocalStore()
        val choreStore = FakeChoreLocalStore()
        val eventStore = FakeEventLocalStore()
        val planStore = FakePlanLocalStore()
        val outbox = FakeOutboxStore()
        val healer = ItemIdHealer(taskStore, habitStore, choreStore, eventStore, planStore, outbox)
    }

    @Test
    fun healTaskRepointsRowParentChildPlanAndOutbox() = runTest {
        val f = Fixture()
        // The offline-created task (client id), a parent that lists it as a child, and a child of it.
        f.taskStore.upsert(task("client", parentId = "parent", children = listOf("kid")))
        f.taskStore.upsert(task("parent", children = listOf("client")))
        f.taskStore.upsert(task("kid", parentId = "client"))
        f.planStore.replacePlan(day, "UTC", listOf(TaskId("parent"), TaskId("client")))
        // A queued edit against the offline-created task (enqueued before its create replayed).
        f.outbox.enqueue("task:client", OutboxRequest(OutboxMethod.Patch, listOf("tasks", "client"), """{"title":"x"}"""), created)

        val changed = f.healer.heal(clientId = "client", canonicalId = "server", kind = ItemKind.Task)

        assertTrue(changed)
        // Row re-keyed: old id gone, new id present, its own refs preserved.
        assertNull(f.taskStore.all[TaskId("client")])
        val healed = f.taskStore.all.getValue(TaskId("server"))
        assertEquals(TaskId("parent"), healed.parentId)
        assertEquals(listOf(TaskId("kid")), healed.children)
        // Parent's child ref + child's parent ref follow.
        assertEquals(listOf(TaskId("server")), f.taskStore.all.getValue(TaskId("parent")).children)
        assertEquals(TaskId("server"), f.taskStore.all.getValue(TaskId("kid")).parentId)
        // Plan slot follows.
        assertEquals(listOf(TaskId("parent"), TaskId("server")), f.planStore.all.values.single())
        // Queued outbox entry re-pointed (target, path, body).
        val entry = f.outbox.all.single()
        assertEquals("task:server", entry.target)
        assertEquals(listOf("tasks", "server"), entry.request.path)
        assertEquals("""{"title":"x"}""", entry.request.body) // unrelated body unchanged
    }

    @Test
    fun healHabitRepointsRowAndOutboxOnly() = runTest {
        val f = Fixture()
        f.habitStore.upsert(
            com.circuitstitch.deferno.core.model.Habit(
                id = HabitId("client"),
                orgSlug = "u-test",
                title = "stretch",
                definitionState = com.circuitstitch.deferno.core.model.DefinitionState.Active,
                dateCreated = created,
            ),
        )
        f.outbox.enqueue("occurrence:Habit:client:2026-06-07", OutboxRequest(OutboxMethod.Post, listOf("habits", "client", "occurrences"), """{"done":true}"""), created)

        val changed = f.healer.heal("client", "server", ItemKind.Habit)

        assertTrue(changed)
        assertNull(f.habitStore.all[HabitId("client")])
        assertEquals(HabitId("server"), f.habitStore.all.getValue(HabitId("server")).id)
        assertEquals(listOf("habits", "server", "occurrences"), f.outbox.all.single().request.path)
    }

    @Test
    fun healIsANoOpWhenIdsAreEqual() = runTest {
        val f = Fixture()
        f.taskStore.upsert(task("same"))

        val changed = f.healer.heal("same", "same", ItemKind.Task)

        assertFalse(changed)
        assertEquals(setOf(TaskId("same")), f.taskStore.all.keys)
    }
}
