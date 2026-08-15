package com.circuitstitch.deferno.core.data.create

import com.circuitstitch.deferno.core.data.item.FakeItemLocalStore
import com.circuitstitch.deferno.core.data.item.cached
import com.circuitstitch.deferno.core.data.outbox.OutboxMethod
import com.circuitstitch.deferno.core.data.outbox.OutboxRequest
import com.circuitstitch.deferno.core.data.outbox.FakeOutboxStore
import com.circuitstitch.deferno.core.data.plan.FakePlanLocalStore
import com.circuitstitch.deferno.core.data.plan.taskRefs
import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.Habit
import com.circuitstitch.deferno.core.model.HabitId
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.PlanItemRef
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
 *
 * **The tree sweep stopped being Task-only at #422**, which closed a real gap rather than just tidying
 * one: it walked the Task store alone, because that was the only store it could cross in one pass, so a
 * recurring definition parented to an offline-created Task kept a dead `parentId` through the heal.
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

    private fun habit(id: String, parentId: String? = null) = Habit(
        id = HabitId(id),
        orgSlug = "u-test",
        title = "stretch",
        definitionState = DefinitionState.Active,
        parentId = parentId?.let(::TaskId),
        dateCreated = created,
    )

    private class Fixture {
        val items = FakeItemLocalStore()
        val planStore = FakePlanLocalStore()
        val outbox = FakeOutboxStore()
        val attachmentReKeys = mutableListOf<Pair<String, String>>()
        val healer = ItemIdHealer(items, planStore, outbox) { from, to ->
            attachmentReKeys += from to to
        }

        /** The cached row's tree position, which is all the heal reads or writes. */
        fun core(id: String) = items.all.getValue(id).item.core
    }

    @Test
    fun healTaskRepointsRowParentChildPlanAndOutbox() = runTest {
        val f = Fixture()
        // The offline-created task (client id), a parent that lists it as a child, and a child of it.
        f.items.upsert(task("client", parentId = "parent", children = listOf("kid")).cached())
        f.items.upsert(task("parent", children = listOf("client")).cached())
        f.items.upsert(task("kid", parentId = "client").cached())
        f.planStore.replacePlan(day, "UTC", taskRefs("parent", "client"))
        // A queued edit against the offline-created task (enqueued before its create replayed).
        f.outbox.enqueue("task:client", OutboxRequest(OutboxMethod.Patch, listOf("tasks", "client"), """{"title":"x"}"""), created)

        val changed = f.healer.heal(clientId = "client", canonicalId = "server", kind = ItemKind.Task)

        assertTrue(changed)
        // Row re-keyed: old id gone, new id present, its own refs preserved.
        assertNull(f.items.all["client"])
        assertEquals("parent", f.core("server").parentId)
        assertEquals(listOf("kid"), f.core("server").childIds)
        // Parent's child ref + child's parent ref follow.
        assertEquals(listOf("server"), f.core("parent").childIds)
        assertEquals("server", f.core("kid").parentId)
        // Plan slot follows.
        assertEquals(taskRefs("parent", "server"), f.planStore.all.values.single())
        // Queued outbox entry re-pointed (target, path, body).
        val entry = f.outbox.all.single()
        assertEquals("task:server", entry.target)
        assertEquals(listOf("tasks", "server"), entry.request.path)
        assertEquals("""{"title":"x"}""", entry.request.body) // unrelated body unchanged
        // gh#223: on-device attachments (brain-dump recording) follow the heal too, or they orphan.
        assertEquals(listOf("client" to "server"), f.attachmentReKeys)
    }

    /**
     * The gap the one-cache sweep closed (#422). The forest has always nested a child of any kind under
     * a parent of any kind, but the sweep could only walk the Task table — so a recurring definition
     * parented to an offline-created Task pointed at a dead id for as long as the row lived.
     */
    @Test
    fun healRepointsATreeEdgeOnARowOfAnotherKind() = runTest {
        val f = Fixture()
        f.items.upsert(task("client").cached())
        f.items.upsert(habit("h", parentId = "client").cached())

        f.healer.heal("client", "server", ItemKind.Task)

        assertEquals("server", f.core("h").parentId)
    }

    @Test
    fun healHabitDoesNotTouchAttachments() = runTest {
        // Attachments are Task-only (the Task-detail on-device seam) — recurring kinds never re-key them.
        val f = Fixture()
        f.items.upsert(habit("client").cached())

        f.healer.heal("client", "server", ItemKind.Habit)

        assertTrue(f.attachmentReKeys.isEmpty())
    }

    /**
     * The plan sweep stopped being Task-only in #385. A recurring definition can be planned, so an
     * offline-created Habit/Chore/Event that landed on a day before its id was healed leaves a plan slot
     * pointing at a dead client id — exactly the Task case, now reachable for the other three. The slot's
     * `kind` is untouched: only the id was wrong.
     */
    @Test
    fun healRepointsAPlanSlotForEveryRecurringKind() = runTest {
        for (kind in listOf(ItemKind.Habit, ItemKind.Chore, ItemKind.Event)) {
            val f = Fixture()
            f.planStore.replacePlan(day, "UTC", listOf(PlanItemRef("client", kind), PlanItemRef("other", kind)))

            f.healer.heal("client", "server", kind)

            assertEquals(
                listOf(PlanItemRef("server", kind), PlanItemRef("other", kind)),
                f.planStore.all.values.single(),
                "a planned $kind follows its canonical id",
            )
        }
    }

    @Test
    fun healHabitRepointsRowAndOutboxOnly() = runTest {
        val f = Fixture()
        f.items.upsert(habit("client").cached())
        f.outbox.enqueue("occurrence:Habit:client:2026-06-07", OutboxRequest(OutboxMethod.Post, listOf("habits", "client", "occurrences"), """{"done":true}"""), created)

        val changed = f.healer.heal("client", "server", ItemKind.Habit)

        assertTrue(changed)
        assertNull(f.items.all["client"])
        assertEquals("server", f.core("server").id)
        // The row keeps the endpoint it round-trips to; only its identity moved.
        assertEquals(ItemKind.Habit, f.items.all.getValue("server").kind)
        assertEquals(listOf("habits", "server", "occurrences"), f.outbox.all.single().request.path)
    }

    @Test
    fun healIsANoOpWhenIdsAreEqual() = runTest {
        val f = Fixture()
        f.items.upsert(task("same").cached())

        val changed = f.healer.heal("same", "same", ItemKind.Task)

        assertFalse(changed)
        assertEquals(setOf("same"), f.items.all.keys)
    }
}
