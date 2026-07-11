package com.circuitstitch.deferno.core.data.task

import com.circuitstitch.deferno.core.data.outbox.ClearDeadline
import com.circuitstitch.deferno.core.data.outbox.ClearDescription
import com.circuitstitch.deferno.core.data.outbox.DeleteTask
import com.circuitstitch.deferno.core.data.outbox.OutboxStore
import com.circuitstitch.deferno.core.data.outbox.beforeValues
import com.circuitstitch.deferno.core.data.outbox.Rename
import com.circuitstitch.deferno.core.data.outbox.SetDeadline
import com.circuitstitch.deferno.core.data.outbox.SetDescription
import com.circuitstitch.deferno.core.data.outbox.SetLabels
import com.circuitstitch.deferno.core.data.outbox.SetPinned
import com.circuitstitch.deferno.core.data.outbox.SetWorkingState
import com.circuitstitch.deferno.core.data.outbox.TaskMutation
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The offline-first [TaskWriter] (ADR-0001, #23): optimistic local apply + enqueue to the outbox. Each
 * write [submit]s a [TaskMutation] — applying its pure optimistic transform to the cached row and
 * queueing its idempotent wire request — so the local source of truth (and the UI `Flow`s observing
 * it) reflects the change immediately, and the server catches up on the next flush ([OutboxProcessor]).
 *
 * **Atomicity (ADR-0001).** The optimistic apply runs inside [TaskLocalStore.transaction], so a
 * concurrent reconcile (`refresh`) can't interleave with this read-modify-write and lose the update —
 * the same transaction seam the reconcile itself uses. The enqueue then happens just after: a crash in
 * that tiny window leaves an optimistic change with no queued request, which the next reconcile reverts
 * to server truth (LWW) — application-level atomicity, no cross-store transaction needed.
 *
 * A mutation whose target row isn't cached locally still enqueues (the write isn't lost) but skips the
 * optimistic apply — there is no row to transform; the reconcile after replay materialises server truth.
 *
 * [now] is injected (default the system clock) so the tombstone timestamp + enqueue time are
 * deterministic under test (ADR-0006).
 */
class OutboxTaskWriter(
    private val localStore: TaskLocalStore,
    private val outbox: OutboxStore,
    private val now: () -> Instant = { Clock.System.now() },
) : TaskWriter {

    override suspend fun setWorkingState(id: TaskId, state: WorkingState) = submit(SetWorkingState(id, state))

    override suspend fun rename(id: TaskId, title: String) = submit(Rename(id, title))

    override suspend fun setDeadline(id: TaskId, completeBy: Instant) = submit(SetDeadline(id, completeBy))

    override suspend fun clearDeadline(id: TaskId) = submit(ClearDeadline(id))

    override suspend fun setDescription(id: TaskId, description: String) = submit(SetDescription(id, description))

    override suspend fun clearDescription(id: TaskId) = submit(ClearDescription(id))

    override suspend fun setLabels(id: TaskId, labels: List<String>) = submit(SetLabels(id, labels))

    override suspend fun setPinned(id: TaskId, pinned: Boolean) = submit(SetPinned(id, pinned))

    override suspend fun delete(id: TaskId) = submit(DeleteTask(id, now()))

    private suspend fun submit(mutation: TaskMutation) {
        // Snapshot the pre-apply old values INSIDE the transaction (the same read-modify-write the reconcile
        // uses), right before applyTo overwrites them, so the ledger can show a true old->new diff (#260).
        // Null when the target row isn't cached (nothing to diff) or the intent has no field diff (delete).
        var before: String? = null
        localStore.transaction { store ->
            store.get(mutation.taskId)?.let { current ->
                before = mutation.beforeValues(current)?.toString()
                store.upsert(mutation.applyTo(current))
            }
        }
        outbox.enqueue(mutation.target, mutation.toRequest(), now(), before)
    }
}
