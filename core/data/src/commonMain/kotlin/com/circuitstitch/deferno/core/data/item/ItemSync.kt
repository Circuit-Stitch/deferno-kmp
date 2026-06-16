package com.circuitstitch.deferno.core.data.item

import com.circuitstitch.deferno.core.data.RemoteSnapshot
import com.circuitstitch.deferno.core.data.chore.ChoreLocalStore
import com.circuitstitch.deferno.core.data.create.PendingCreateStore
import com.circuitstitch.deferno.core.data.event.EventLocalStore
import com.circuitstitch.deferno.core.data.habit.HabitLocalStore
import com.circuitstitch.deferno.core.data.task.TaskLocalStore
import com.circuitstitch.deferno.core.model.ChoreId
import com.circuitstitch.deferno.core.model.EventId
import com.circuitstitch.deferno.core.model.HabitId
import com.circuitstitch.deferno.core.model.TaskId

/**
 * The offline-first cold sync that migrates the client from the legacy task-only `GET /tasks` to the
 * `GET /items` snapshot (ADR-0034, #226). It is the **Item-store** generalization of the old
 * `OfflineTaskRepository.refresh` reconcile: one snapshot pull, reconciled into the four per-kind
 * local stores (Task / Habit / Chore / Event) so the tree persists every kind offline, not just Tasks.
 *
 * **Done-visibility window — honored for free.** `/items` is windowed **server-side**, so this does
 * **no** client-side window math: a terminal, non-recurring item aged past the window is simply absent
 * from the snapshot and the per-kind orphan-purge drops it; recurring kinds (Habit/Chore/recurring
 * Event) are never returned as terminal, so they never age out.
 *
 * **Reconcile (per kind, atomic).** Each kind is reconciled in its store's [transaction] — upsert every
 * snapshot row by id, then hard-delete the rows the snapshot dropped entirely (`allIds - snapshotIds`).
 * `/items` rows are always [com.circuitstitch.deferno.core.model.HydrationState.Full], so an upsert
 * replaces wholesale (no summary-downgrade merge needed). Tombstones in the snapshot are kept (the
 * upsert stores them as deleted rows), so a re-run is idempotent (ADR-0001 LWW).
 *
 * **Offline-first (ADR-0001).** An [RemoteSnapshot.Unavailable] pull (couldn't reach the server) skips
 * the reconcile entirely, leaving every cache intact; an [RemoteSnapshot.Available] snapshot always
 * reconciles, even when empty (a genuinely-emptied server purges the caches).
 *
 * **Offline creates protected from the purge (#185).** A row created offline rides the outbox and is
 * absent from the server snapshot until its create replays, so the still-[pending][PendingCreateStore]
 * ids are excluded from every kind's orphan set (a pending id is a global UUID — mapping it into each
 * kind's id type is harmless, since it only collides with its own kind's `allIds`).
 */
class ItemSync(
    private val taskStore: TaskLocalStore,
    private val habitStore: HabitLocalStore,
    private val choreStore: ChoreLocalStore,
    private val eventStore: EventLocalStore,
    private val source: ItemSnapshotSource,
    private val pendingCreateStore: PendingCreateStore,
) {

    suspend fun refresh() {
        val snapshot = when (val result = source.fetchAll()) {
            is RemoteSnapshot.Available -> result.value
            RemoteSnapshot.Unavailable -> return
        }
        val pending = pendingCreateStore.pendingIds()

        taskStore.transaction { s ->
            reconcileKind(snapshot.tasks, { it.id }, pending.mapTo(mutableSetOf(), ::TaskId), s::allIds, s::upsert, s::delete)
        }
        habitStore.transaction { s ->
            reconcileKind(snapshot.habits, { it.id }, pending.mapTo(mutableSetOf(), ::HabitId), s::allIds, s::upsert, s::delete)
        }
        choreStore.transaction { s ->
            reconcileKind(snapshot.chores, { it.id }, pending.mapTo(mutableSetOf(), ::ChoreId), s::allIds, s::upsert, s::delete)
        }
        eventStore.transaction { s ->
            reconcileKind(snapshot.events, { it.id }, pending.mapTo(mutableSetOf(), ::EventId), s::allIds, s::upsert, s::delete)
        }
    }

    /**
     * Upserts every [rows] row by id, then hard-deletes the locally-held ids absent from the snapshot
     * and not a still-[pending] offline create. Runs inside an already-open store transaction.
     */
    private suspend fun <T, ID> reconcileKind(
        rows: List<T>,
        idOf: (T) -> ID,
        pending: Set<ID>,
        allIds: suspend () -> Set<ID>,
        upsert: suspend (T) -> Unit,
        delete: suspend (ID) -> Unit,
    ) {
        val snapshotIds = rows.mapTo(mutableSetOf(), idOf)
        for (row in rows) upsert(row)
        for (orphan in allIds() - snapshotIds - pending) delete(orphan)
    }
}
