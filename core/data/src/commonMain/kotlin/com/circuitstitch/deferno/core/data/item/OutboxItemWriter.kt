package com.circuitstitch.deferno.core.data.item

import com.circuitstitch.deferno.core.data.outbox.DeleteItem
import com.circuitstitch.deferno.core.data.outbox.Move
import com.circuitstitch.deferno.core.data.outbox.OutboxStore
import com.circuitstitch.deferno.core.model.Item
import com.circuitstitch.deferno.core.model.ItemKind
import kotlinx.coroutines.flow.first
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The offline-first [ItemWriter] (ADR-0001, ADR-0049 #228) — the cross-kind sibling of
 * [com.circuitstitch.deferno.core.data.task.OutboxTaskWriter]. It held the four per-kind stores until
 * #422 flipped the cache onto plugins; it holds the one store and the outbox now.
 *
 * [move] applies optimistically — [planMove] computes the destination group's new `sequence`s from a
 * fresh snapshot, then commits them in one transaction so the tree re-flattens at once — and enqueues a
 * [Move] (`POST items/{id}/move`) for replay. A server **400** (a cycle) is a terminal rejection the next
 * cold-snapshot reconcile corrects (LWW).
 *
 * [delete] tombstones the row and enqueues a [DeleteItem] (`DELETE items/{id}`).
 *
 * **A move and a delete were always kind-neutral acts, and now the writes are too.** Both addressed a
 * raw id, because the tree row they act on is the cross-kind projection; underneath, each had to probe
 * or dispatch across four stores, and a delete cost three empty reads every time. One cache removes all
 * of it, and every edit here touches nothing but `Core` — id, tree position, tombstone — which is the
 * shape ADR-0055 predicts, since a move is not a Family.
 *
 * [now] is injected (default the system clock) so the enqueue time is deterministic under test (ADR-0006).
 */
class OutboxItemWriter(
    private val items: ItemLocalStore,
    private val outbox: OutboxStore,
    private val now: () -> Instant = { Clock.System.now() },
) : ItemWriter {

    override suspend fun move(id: String, newParentId: String?, position: Int) {
        applyOptimistically(planMove(snapshot(), id, newParentId, position), movedId = id, newParentId = newParentId)
        Move(id, newParentId, position).let { outbox.enqueue(it.target, it.toRequest(), now()) }
    }

    /**
     * A **tombstone**, not a row removal, even though the store also exposes a hard `delete(id)`. A
     * tombstoned row still exists to reconcile against if the replay ever fails terminally; a
     * hard-deleted one leaves nothing behind and the next snapshot would resurrect it as if it were new.
     * `observeActive` filters on `deletedAt`, so the tree drops the row immediately either way.
     *
     * One `now()` for both the tombstone and the enqueue, so the local delete time and the queued write
     * agree.
     */
    override suspend fun delete(id: String) {
        val deletedAt = now()
        items.transaction { s ->
            s.get(id)?.let { s.upsert(it.copy(item = it.item.copy(core = it.item.core.copy(deletedAt = deletedAt)))) }
        }
        DeleteItem(id).let { outbox.enqueue(it.target, it.toRequest(), deletedAt) }
    }

    /** The current Item set — only the fields [planMove] orders on (id, kind, title, parent, sequence). */
    private suspend fun snapshot(): List<Item> = items.observeActive().first().map { row ->
        Item(row.id, row.kind, row.item.core.title, row.item.core.parentId, row.item.core.sequence)
    }

    /**
     * Writes the planned [assignments] back. Only the moved row is reparented ([newParentId]); a
     * non-moved sibling keeps its own `parentId` — its raw pointer, possibly an orphan's absent parent,
     * is never rewritten — and only shifts its `sequence`.
     */
    private suspend fun applyOptimistically(assignments: List<MoveAssignment>, movedId: String, newParentId: String?) {
        items.transaction { s ->
            for (assignment in assignments) {
                val row = s.get(assignment.id) ?: continue
                val core = row.item.core
                s.upsert(
                    row.copy(
                        item = row.item.copy(
                            core = core.copy(
                                sequence = assignment.sequence,
                                parentId = if (assignment.id == movedId) newParentId else core.parentId,
                            ),
                        ),
                    ),
                )
            }
        }
    }
}

/** One row's optimistic reassignment from a planned move: its new sibling [sequence] in the destination group. */
internal data class MoveAssignment(val id: String, val kind: ItemKind, val sequence: Long)

/**
 * Pure optimistic plan for moving [movedId] under [newParentId] (`null` = root) to insertion index
 * [position]: the new `sequence` for each affected row in the **destination** sibling group (ADR-0049 #228).
 *
 * Mirrors the tree's own sibling grouping (`buildItemTree`) — an absent parent collapses to root, so an
 * orphan is treated as a root sibling — meaning the optimistic order matches what the flatten will render.
 * Returns the moved row (always — it at least reparents) plus any sibling whose index shifted; a row whose
 * `sequence` already equals its new index is omitted. Only the destination group is renumbered: the moved
 * row's former group keeps its now-gapped order, which still sorts correctly. The server reassigns the
 * canonical `sequence`s on reconcile. A move of an uncached [movedId] plans nothing (the writer still enqueues).
 */
internal fun planMove(items: List<Item>, movedId: String, newParentId: String?, position: Int): List<MoveAssignment> {
    val moved = items.firstOrNull { it.id == movedId } ?: return emptyList()
    val visibleIds = items.mapTo(HashSet(items.size)) { it.id }
    fun effectiveParent(item: Item): String? = item.parentId?.takeIf(visibleIds::contains)
    val siblings = items.filter { it.id != movedId && effectiveParent(it) == newParentId }.sortedWith(SIBLING_ORDER)
    val ordered = siblings.toMutableList().apply { add(position.coerceIn(0, size), moved) }
    return ordered.mapIndexedNotNull { index, item ->
        val seq = index.toLong()
        // The moved row always reassigns (reparent + seq); a sibling only when its index actually shifted.
        if (item.id == movedId || item.sequence != seq) MoveAssignment(item.id, item.kind, seq) else null
    }
}

// Mirrors feature `ItemTree.SIBLING_ORDER` (sequence nulls-last, then title, then id) so the optimistic
// insertion index matches the order the tree flatten renders — keep the two in step.
private val SIBLING_ORDER: Comparator<Item> =
    compareBy<Item>({ it.sequence == null }, { it.sequence }, { it.title }, { it.id })
