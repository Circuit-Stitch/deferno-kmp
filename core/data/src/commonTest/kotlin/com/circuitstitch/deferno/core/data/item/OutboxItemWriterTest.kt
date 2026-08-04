package com.circuitstitch.deferno.core.data.item

import com.circuitstitch.deferno.core.data.create.FakeChoreLocalStore
import com.circuitstitch.deferno.core.data.create.FakeEventLocalStore
import com.circuitstitch.deferno.core.data.create.FakeHabitLocalStore
import com.circuitstitch.deferno.core.data.outbox.FakeOutboxStore
import com.circuitstitch.deferno.core.data.outbox.OutboxMethod
import com.circuitstitch.deferno.core.data.task.FakeTaskLocalStore
import com.circuitstitch.deferno.core.model.Chore
import com.circuitstitch.deferno.core.model.ChoreId
import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.Event
import com.circuitstitch.deferno.core.model.EventId
import com.circuitstitch.deferno.core.model.Habit
import com.circuitstitch.deferno.core.model.HabitId
import com.circuitstitch.deferno.core.model.Item
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The cross-kind Item move write path (ADR-0049 #228): [planMove] renumbers the destination sibling
 * group purely, and [OutboxItemWriter] applies that across the four per-kind stores + enqueues the
 * `POST items/{id}/move`. Run against the in-memory fakes (ADR-0006 JVM-fast path).
 */
class OutboxItemWriterTest {

    private val now = Instant.parse("2026-06-07T12:00:00Z")

    private fun item(id: String, sequence: Long?, kind: ItemKind = ItemKind.Task, parent: String? = null) =
        Item(id = id, kind = kind, title = "title-$id", parentId = parent, sequence = sequence)

    // --- planMove (pure) ---

    @Test
    fun planMoveReordersWithinAGroupRenumberingTheShiftedSiblings() {
        val items = listOf(item("a", 0), item("b", 1), item("c", 2))
        // Move c to the front of root.
        val plan = planMove(items, movedId = "c", newParentId = null, position = 0)
        assertEquals(
            setOf(MoveAssignment("c", ItemKind.Task, 0), MoveAssignment("a", ItemKind.Task, 1), MoveAssignment("b", ItemKind.Task, 2)),
            plan.toSet(),
        )
    }

    @Test
    fun planMoveReparentEmitsOnlyTheMovedRowWhenTheDestinationGroupIsEmpty() {
        val items = listOf(item("a", 0), item("b", 1))
        // a under b — b has no other children, so only the moved row is renumbered.
        assertEquals(listOf(MoveAssignment("a", ItemKind.Task, 0)), planMove(items, movedId = "a", newParentId = "b", position = 0))
    }

    @Test
    fun planMoveOfAnUncachedIdPlansNothing() {
        assertTrue(planMove(listOf(item("a", 0)), movedId = "ghost", newParentId = null, position = 0).isEmpty())
    }

    // --- OutboxItemWriter ---

    private fun task(id: String, sequence: Long?, parent: String? = null) = Task(
        id = TaskId(id),
        orgSlug = "u-test",
        title = "t-$id",
        workingState = WorkingState.Open,
        parentId = parent?.let(::TaskId),
        sequence = sequence,
        dateCreated = now,
    )

    private fun habit(id: String, sequence: Long?, parent: String? = null) = Habit(
        id = HabitId(id),
        orgSlug = "u-test",
        title = "h-$id",
        definitionState = DefinitionState.Active,
        parentId = parent?.let(::TaskId),
        sequence = sequence,
        dateCreated = now,
    )

    private fun writer(task: FakeTaskLocalStore, habit: FakeHabitLocalStore, outbox: FakeOutboxStore) =
        OutboxItemWriter(task, habit, FakeChoreLocalStore(), FakeEventLocalStore(), outbox, now = { now })

    @Test
    fun moveReparentsTheRowOptimisticallyAndEnqueuesTheMove() = runTest {
        val tasks = FakeTaskLocalStore(mapOf(TaskId("a") to task("a", 0), TaskId("b") to task("b", 1)))
        val habits = FakeHabitLocalStore()
        val outbox = FakeOutboxStore()

        writer(tasks, habits, outbox).move(id = "a", newParentId = "b", position = 0)

        // Optimistic: a reparented under b at sequence 0; b untouched (not in the destination group).
        val moved = tasks.all.getValue(TaskId("a"))
        assertEquals(TaskId("b"), moved.parentId)
        assertEquals(0, moved.sequence)
        assertEquals(1, tasks.all.getValue(TaskId("b")).sequence)
        // Enqueued exactly once, addressed by the item target, body carrying the destination + index.
        val entry = outbox.all.single()
        assertEquals("item:a", entry.target)
        assertEquals(OutboxMethod.Post, entry.request.method)
        assertEquals(listOf("items", "a", "move"), entry.request.path)
        assertEquals("""{"new_parent_id":"b","position":0}""", entry.request.body)
        assertEquals(now, entry.nextAttemptAt)
    }

    @Test
    fun moveRenumbersAcrossKindsAndDetachesToRoot() = runTest {
        // A Task and a Habit are root siblings (Task read first → sequence 0, Habit 1 by the fake order).
        val tasks = FakeTaskLocalStore(mapOf(TaskId("t1") to task("t1", 0)))
        val habits = FakeHabitLocalStore(mapOf(HabitId("h1") to habit("h1", 1)))
        val outbox = FakeOutboxStore()

        // Move the Habit to the front of root: the non-moved Task sibling must renumber to index 1.
        writer(tasks, habits, outbox).move(id = "h1", newParentId = null, position = 0)

        val movedHabit = habits.all.getValue(HabitId("h1"))
        assertEquals(0, movedHabit.sequence)
        assertNull(movedHabit.parentId) // detached to / stays at root
        assertEquals(1, tasks.all.getValue(TaskId("t1")).sequence)
        assertEquals("""{"new_parent_id":null,"position":0}""", outbox.all.single().request.body)
    }

    @Test
    fun moveOfAnUncachedIdStillEnqueuesButTouchesNoRow() = runTest {
        val tasks = FakeTaskLocalStore(mapOf(TaskId("a") to task("a", 0)))
        val outbox = FakeOutboxStore()

        writer(tasks, FakeHabitLocalStore(), outbox).move(id = "ghost", newParentId = "a", position = 0)

        assertEquals(task("a", 0), tasks.all.getValue(TaskId("a"))) // unchanged
        assertEquals(1, outbox.all.size) // the write is not lost — it reconciles on replay
    }

    // --- delete (#389): kind-neutral, chain-wide, optimistically a tombstone ---

    private fun chore(id: String) = Chore(
        id = ChoreId(id), orgSlug = "u-test", title = "c-$id",
        definitionState = DefinitionState.Active, dateCreated = now,
    )

    private fun event(id: String) = Event(
        id = EventId(id), orgSlug = "u-test", title = "e-$id",
        definitionState = DefinitionState.Active, dateCreated = now,
    )

    private fun fullWriter(
        tasks: FakeTaskLocalStore = FakeTaskLocalStore(),
        habits: FakeHabitLocalStore = FakeHabitLocalStore(),
        chores: FakeChoreLocalStore = FakeChoreLocalStore(),
        events: FakeEventLocalStore = FakeEventLocalStore(),
        outbox: FakeOutboxStore = FakeOutboxStore(),
    ) = OutboxItemWriter(tasks, habits, chores, events, outbox, now = { now })

    @Test
    fun deleteTombstonesTheRowInWhicheverStoreHoldsItAndEnqueuesTheItemDelete() = runTest {
        val habits = FakeHabitLocalStore(mapOf(HabitId("h1") to habit("h1", 0)))
        val tasks = FakeTaskLocalStore(mapOf(TaskId("t1") to task("t1", 1)))
        val outbox = FakeOutboxStore()

        fullWriter(tasks = tasks, habits = habits, outbox = outbox).delete("h1")

        // A tombstone, not a row removal: the row survives to reconcile against, and every
        // observeActive filters it out, so the tree drops it immediately.
        val tombstoned = habits.all.getValue(HabitId("h1"))
        assertEquals(now, tombstoned.deletedAt)
        assertTrue(tombstoned.isDeleted)
        assertNull(tasks.all.getValue(TaskId("t1")).deletedAt, "a sibling of another kind must not be touched")

        val entry = outbox.all.single()
        assertEquals("item:h1", entry.target)
        assertEquals(OutboxMethod.Delete, entry.request.method)
        // `items`, not `habits`: the per-kind route archives ONE Segment and leaves the chain alive,
        // so the survivor would pop back as an item the user just deleted.
        assertEquals(listOf("items", "h1"), entry.request.path)
        assertNull(entry.request.body)
        assertEquals(now, entry.nextAttemptAt)
    }

    @Test
    fun deleteReachesEveryKindIncludingTheTaskItTakesNoKindOperandFor() = runTest {
        for ((id, stores) in listOf<Pair<String, () -> Triple<FakeTaskLocalStore, FakeChoreLocalStore, FakeEventLocalStore>>>(
            "t1" to { Triple(FakeTaskLocalStore(mapOf(TaskId("t1") to task("t1", 0))), FakeChoreLocalStore(), FakeEventLocalStore()) },
            "c1" to { Triple(FakeTaskLocalStore(), FakeChoreLocalStore(mapOf(ChoreId("c1") to chore("c1"))), FakeEventLocalStore()) },
            "e1" to { Triple(FakeTaskLocalStore(), FakeChoreLocalStore(), FakeEventLocalStore(mapOf(EventId("e1") to event("e1")))) },
        )) {
            val (tasks, chores, events) = stores()
            val outbox = FakeOutboxStore()

            fullWriter(tasks = tasks, chores = chores, events = events, outbox = outbox).delete(id)

            val deletedAt = tasks.all[TaskId(id)]?.deletedAt
                ?: chores.all[ChoreId(id)]?.deletedAt
                ?: events.all[EventId(id)]?.deletedAt
            assertEquals(now, deletedAt, "$id must be tombstoned in its own store")
            // One route for all four kinds — the server resolves the kind itself.
            assertEquals(listOf("items", id), outbox.all.single().request.path)
        }
    }

    @Test
    fun deleteOfAnUncachedIdStillEnqueuesButMaterialisesNoRow() = runTest {
        val tasks = FakeTaskLocalStore(mapOf(TaskId("a") to task("a", 0)))
        val outbox = FakeOutboxStore()

        fullWriter(tasks = tasks, outbox = outbox).delete("ghost")

        assertEquals(task("a", 0), tasks.all.getValue(TaskId("a"))) // unchanged
        assertEquals(1, tasks.all.size, "no phantom row materialised")
        assertEquals(1, outbox.all.size) // the write is not lost — it reconciles on replay
    }
}
