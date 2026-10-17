package com.circuitstitch.deferno.core.data.item

import com.circuitstitch.deferno.core.data.outbox.FakeOutboxStore
import com.circuitstitch.deferno.core.data.outbox.OutboxMethod
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
 * The cross-kind Item move + delete write path (ADR-0049 #228): [planMove] renumbers the destination
 * sibling group purely, and [OutboxItemWriter] applies that to the item cache and enqueues the
 * `POST items/{id}/move`. Run against the in-memory fake (ADR-0006 JVM-fast path).
 *
 * **Both acts were always kind-neutral, and since #422 the writes are too.** Each addressed a raw id and
 * then had to dispatch across four stores underneath — a delete cost three empty reads before it found
 * its row. One cache removes that, and every edit here touches nothing but `Core`: id, tree position,
 * tombstone. Which is what ADR-0055 predicts, since a move is not a Family.
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

    private fun chore(id: String) = Chore(
        id = ChoreId(id), orgSlug = "u-test", title = "c-$id",
        definitionState = DefinitionState.Active, dateCreated = now,
    )

    private fun event(id: String) = Event(
        id = EventId(id), orgSlug = "u-test", title = "e-$id",
        definitionState = DefinitionState.Active, dateCreated = now,
    )

    private fun writer(items: FakeItemLocalStore, outbox: FakeOutboxStore) =
        OutboxItemWriter(items, outbox, now = { now })

    /** The cached row's tree position, which is all a move reads or writes. */
    private fun FakeItemLocalStore.core(id: String) = all.getValue(id).item.core

    @Test
    fun moveReparentsTheRowOptimisticallyAndEnqueuesTheMove() = runTest {
        val items = FakeItemLocalStore(cacheOf(task("a", 0).cached(), task("b", 1).cached()))
        val outbox = FakeOutboxStore()

        writer(items, outbox).move(id = "a", newParentId = "b", position = 0)

        // Optimistic: a reparented under b at sequence 0; b untouched (not in the destination group).
        assertEquals("b", items.core("a").parentId)
        assertEquals(0, items.core("a").sequence)
        assertEquals(1, items.core("b").sequence)
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
        // A Task and a Habit are root siblings, ordered by `sequence` across kinds.
        val items = FakeItemLocalStore(cacheOf(task("t1", 0).cached(), habit("h1", 1).cached()))
        val outbox = FakeOutboxStore()

        // Move the Habit to the front of root: the non-moved Task sibling must renumber to index 1.
        writer(items, outbox).move(id = "h1", newParentId = null, position = 0)

        assertEquals(0, items.core("h1").sequence)
        assertNull(items.core("h1").parentId) // detached to / stays at root
        assertEquals(1, items.core("t1").sequence)
        assertEquals("""{"new_parent_id":null,"position":0}""", outbox.all.single().request.body)
    }

    @Test
    fun moveOfAnUncachedIdStillEnqueuesButTouchesNoRow() = runTest {
        val items = FakeItemLocalStore(cacheOf(task("a", 0).cached()))
        val outbox = FakeOutboxStore()

        writer(items, outbox).move(id = "ghost", newParentId = "a", position = 0)

        assertEquals(task("a", 0).cached(), items.all.getValue("a")) // unchanged
        assertEquals(1, outbox.all.size) // the write is not lost — it reconciles on replay
    }

    // --- delete (#389): kind-neutral, chain-wide, optimistically a tombstone ---

    @Test
    fun deleteTombstonesTheRowAndEnqueuesTheItemDelete() = runTest {
        val items = FakeItemLocalStore(cacheOf(habit("h1", 0).cached(), task("t1", 1).cached()))
        val outbox = FakeOutboxStore()

        writer(items, outbox).delete("h1")

        // A tombstone, not a row removal: the row survives to reconcile against, and observeActive
        // filters it out, so the tree drops it immediately.
        assertEquals(now, items.core("h1").deletedAt)
        assertTrue(items.core("h1").isDeleted)
        assertNull(items.core("t1").deletedAt, "a sibling of another kind must not be touched")

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
        val rows = listOf(task("t1", 0).cached(), chore("c1").cached(), event("e1").cached(), habit("h1", 1).cached())
        for (row in rows) {
            val items = FakeItemLocalStore(cacheOf(row))
            val outbox = FakeOutboxStore()

            writer(items, outbox).delete(row.id)

            assertEquals(now, items.core(row.id).deletedAt, "${row.id} must be tombstoned")
            // One route for all four kinds — the server resolves the kind itself.
            assertEquals(listOf("items", row.id), outbox.all.single().request.path)
        }
    }

    @Test
    fun deleteOfAnUncachedIdStillEnqueuesButMaterialisesNoRow() = runTest {
        val items = FakeItemLocalStore(cacheOf(task("a", 0).cached()))
        val outbox = FakeOutboxStore()

        writer(items, outbox).delete("ghost")

        assertEquals(task("a", 0).cached(), items.all.getValue("a")) // unchanged
        assertEquals(1, items.all.size, "no phantom row materialised")
        assertEquals(1, outbox.all.size) // the write is not lost — it reconciles on replay
    }
}
