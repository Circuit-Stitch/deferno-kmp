package com.circuitstitch.deferno.core.data.create

import com.circuitstitch.deferno.core.data.chore.ChoreLocalStore
import com.circuitstitch.deferno.core.data.event.EventLocalStore
import com.circuitstitch.deferno.core.data.habit.HabitLocalStore
import com.circuitstitch.deferno.core.data.outbox.OutboxStore
import com.circuitstitch.deferno.core.data.plan.PlanLocalStore
import com.circuitstitch.deferno.core.data.task.TaskLocalStore
import com.circuitstitch.deferno.core.model.ChoreId
import com.circuitstitch.deferno.core.model.EventId
import com.circuitstitch.deferno.core.model.HabitId
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.TaskId

/**
 * Repairs every local reference to an offline-created Item's **client** id when the server assigns it a
 * **different** canonical id (#185). Client-supplied ids are the normal path (the backend honors them —
 * Kyle-Falconer/Deferno#402), so this fires only on the rare divergence, but it must leave the cache
 * fully consistent when it does. Driven by the outbox replay listener the instant a create replays
 * (before the processor advances to any queued edit against the same id).
 *
 * What it re-points (client → canonical):
 * - the Item row itself in its kind's local store (insert under the canonical id, delete the client row);
 * - **Task only** — `parentId` / `children` references on every other Task row (the decomposition tree);
 * - **Task only** — plan slots referencing the id ([PlanLocalStore.rekeyTask]);
 * - any already-queued outbox entry whose `target` / `path` / `body` mentions the client id
 *   ([OutboxStore.update], in place so FIFO order is preserved). A UUID substring replace is
 *   collision-safe (ids don't appear as substrings of unrelated content).
 *
 * The recurring kinds (Habit/Chore/Event) are recurring *definitions* — not parents/children of each
 * other and not in the daily plan (the plan holds Tasks) — so healing one is just the row re-key + the
 * outbox sweep. The pending-create row's own re-key + confirm is owned by the listener, not here.
 */
class ItemIdHealer(
    private val taskStore: TaskLocalStore,
    private val habitStore: HabitLocalStore,
    private val choreStore: ChoreLocalStore,
    private val eventStore: EventLocalStore,
    private val planStore: PlanLocalStore,
    private val outbox: OutboxStore,
) {

    /**
     * Re-points all local references for the [kind] Item from [clientId] to [canonicalId]. A no-op
     * returning `false` when the ids are equal (the normal path); otherwise performs the heal and
     * returns `true` (the processor uses this to know the outbox queue may have changed).
     */
    suspend fun heal(clientId: String, canonicalId: String, kind: ItemKind): Boolean {
        if (clientId == canonicalId) return false
        when (kind) {
            ItemKind.Task -> healTask(clientId, canonicalId)
            ItemKind.Habit -> healHabit(clientId, canonicalId)
            ItemKind.Chore -> healChore(clientId, canonicalId)
            ItemKind.Event -> healEvent(clientId, canonicalId)
        }
        healOutbox(clientId, canonicalId)
        return true
    }

    private suspend fun healTask(clientId: String, canonicalId: String) {
        val from = TaskId(clientId)
        val to = TaskId(canonicalId)
        taskStore.transaction { store ->
            // Snapshot the ids before mutating — upsert/delete below changes the row set, and a store
            // whose allIds() is a live view would otherwise fault mid-iteration.
            for (id in store.allIds().toList()) {
                val task = store.get(id) ?: continue
                val newParent = if (task.parentId == from) to else task.parentId
                val newChildren = task.children.map { if (it == from) to else it }
                if (task.id == from) {
                    // Re-key the created row itself, carrying any refs it itself held.
                    store.upsert(task.copy(id = to, parentId = newParent, children = newChildren))
                    store.delete(from)
                } else if (newParent != task.parentId || newChildren != task.children) {
                    store.upsert(task.copy(parentId = newParent, children = newChildren))
                }
            }
        }
        planStore.rekeyTask(from, to)
    }

    private suspend fun healHabit(clientId: String, canonicalId: String) {
        val row = habitStore.get(HabitId(clientId)) ?: return
        habitStore.upsert(row.copy(id = HabitId(canonicalId)))
        habitStore.delete(HabitId(clientId))
    }

    private suspend fun healChore(clientId: String, canonicalId: String) {
        val row = choreStore.get(ChoreId(clientId)) ?: return
        choreStore.upsert(row.copy(id = ChoreId(canonicalId)))
        choreStore.delete(ChoreId(clientId))
    }

    private suspend fun healEvent(clientId: String, canonicalId: String) {
        val row = eventStore.get(EventId(clientId)) ?: return
        eventStore.upsert(row.copy(id = EventId(canonicalId)))
        eventStore.delete(EventId(clientId))
    }

    private suspend fun healOutbox(clientId: String, canonicalId: String) {
        for (entry in outbox.pending()) {
            val newTarget = entry.target.replace(clientId, canonicalId)
            val newPath = entry.request.path.map { it.replace(clientId, canonicalId) }
            val newBody = entry.request.body?.replace(clientId, canonicalId)
            if (newTarget != entry.target || newPath != entry.request.path || newBody != entry.request.body) {
                outbox.update(entry.seq, newTarget, entry.request.copy(path = newPath, body = newBody))
            }
        }
    }
}
