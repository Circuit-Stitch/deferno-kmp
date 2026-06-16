package com.circuitstitch.deferno.core.data.item

import com.circuitstitch.deferno.core.data.chore.ChoreLocalStore
import com.circuitstitch.deferno.core.data.event.EventLocalStore
import com.circuitstitch.deferno.core.data.habit.HabitLocalStore
import com.circuitstitch.deferno.core.data.outbox.Move
import com.circuitstitch.deferno.core.data.outbox.OutboxStore
import com.circuitstitch.deferno.core.data.task.TaskLocalStore
import com.circuitstitch.deferno.core.model.ChoreId
import com.circuitstitch.deferno.core.model.EventId
import com.circuitstitch.deferno.core.model.HabitId
import com.circuitstitch.deferno.core.model.Item
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.TaskId
import kotlinx.coroutines.flow.first
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The offline-first [ItemWriter] (ADR-0001, ADR-0034 #228) — the cross-kind sibling of
 * [com.circuitstitch.deferno.core.data.task.OutboxTaskWriter]. A move spans all four kinds, so it holds
 * the four per-kind stores (like [com.circuitstitch.deferno.core.data.create.OfflineCreateWriter]) plus
 * the outbox.
 *
 * [move] applies optimistically — [planMove] computes the destination group's new `sequence`s from a
 * fresh cross-kind snapshot, then each touched kind commits in one transaction (one observe re-emit) so
 * the tree re-flattens at once — and enqueues a [Move] (`POST items/{id}/move`) for replay. A server
 * **400** (cycle) is a terminal rejection the next cold-snapshot reconcile corrects (LWW).
 *
 * [now] is injected (default the system clock) so the enqueue time is deterministic under test (ADR-0006).
 */
class OutboxItemWriter(
    private val taskStore: TaskLocalStore,
    private val habitStore: HabitLocalStore,
    private val choreStore: ChoreLocalStore,
    private val eventStore: EventLocalStore,
    private val outbox: OutboxStore,
    private val now: () -> Instant = { Clock.System.now() },
) : ItemWriter {

    override suspend fun move(id: String, newParentId: String?, position: Int) {
        applyOptimistically(planMove(snapshot(), id, newParentId, position), movedId = id, newParentId = newParentId)
        Move(id, newParentId, position).let { outbox.enqueue(it.target, it.toRequest(), now()) }
    }

    /** The current cross-kind Item set — only the fields [planMove] orders on (id/kind/title/parent/seq). */
    private suspend fun snapshot(): List<Item> = buildList {
        taskStore.observeActive().first().forEach { add(Item(it.id.value, ItemKind.Task, it.title, it.parentId?.value, it.sequence)) }
        habitStore.observeActive().first().forEach { add(Item(it.id.value, ItemKind.Habit, it.title, it.parentId?.value, it.sequence)) }
        choreStore.observeActive().first().forEach { add(Item(it.id.value, ItemKind.Chore, it.title, it.parentId?.value, it.sequence)) }
        eventStore.observeActive().first().forEach { add(Item(it.id.value, ItemKind.Event, it.title, it.parentId?.value, it.sequence)) }
    }

    /**
     * Writes the planned [assignments] back per kind. Only the moved row is reparented ([newParentId]); a
     * non-moved sibling keeps its own `parentId` (its raw pointer — possibly an orphan's absent parent —
     * is never rewritten) and only shifts its `sequence`. All four kinds carry `parentId: TaskId?`.
     */
    private suspend fun applyOptimistically(assignments: List<MoveAssignment>, movedId: String, newParentId: String?) {
        val parent = newParentId?.let(::TaskId)
        for ((kind, group) in assignments.groupBy { it.kind }) when (kind) {
            ItemKind.Task -> taskStore.transaction { s ->
                group.forEach { a -> s.get(TaskId(a.id))?.let { s.upsert(it.copy(sequence = a.sequence, parentId = if (a.id == movedId) parent else it.parentId)) } }
            }
            ItemKind.Habit -> habitStore.transaction { s ->
                group.forEach { a -> s.get(HabitId(a.id))?.let { s.upsert(it.copy(sequence = a.sequence, parentId = if (a.id == movedId) parent else it.parentId)) } }
            }
            ItemKind.Chore -> choreStore.transaction { s ->
                group.forEach { a -> s.get(ChoreId(a.id))?.let { s.upsert(it.copy(sequence = a.sequence, parentId = if (a.id == movedId) parent else it.parentId)) } }
            }
            ItemKind.Event -> eventStore.transaction { s ->
                group.forEach { a -> s.get(EventId(a.id))?.let { s.upsert(it.copy(sequence = a.sequence, parentId = if (a.id == movedId) parent else it.parentId)) } }
            }
        }
    }
}

/** One row's optimistic reassignment from a planned move: its new sibling [sequence] in the destination group. */
internal data class MoveAssignment(val id: String, val kind: ItemKind, val sequence: Long)

/**
 * Pure optimistic plan for moving [movedId] under [newParentId] (`null` = root) to insertion index
 * [position]: the new `sequence` for each affected row in the **destination** sibling group (ADR-0034 #228).
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
