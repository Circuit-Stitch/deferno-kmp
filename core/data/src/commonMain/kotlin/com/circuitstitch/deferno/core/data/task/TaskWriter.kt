package com.circuitstitch.deferno.core.data.task

import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
import kotlin.time.Instant

/**
 * The Task **write** seam the UI/feature layer drives (ADR-0001, #23). Deliberately separate from the
 * read-only [TaskRepository] (which several feature/demo classes implement): each call **applies
 * optimistically** to the local cache — so the UI updates the instant the user acts, online or off —
 * and **enqueues** an intent-based, idempotent mutation to the outbox for FIFO replay when
 * connectivity returns (last-writer-wins). See [OutboxTaskWriter] and
 * [com.circuitstitch.deferno.core.data.outbox.Mutation] for the intent → endpoint → minimal-body table.
 *
 * Every method targets an **existing** Task (a stable server id), which is what makes replay
 * reconcile-clean; offline *create* is deferred (no idempotency key at envelope v0.1 — see `Mutation`).
 */
interface TaskWriter {

    /** Set the Task's [WorkingState] (`PATCH tasks/{id} {"status":…}`). */
    suspend fun setWorkingState(id: TaskId, state: WorkingState)

    /** Rename the Task (`PATCH tasks/{id} {"title":…}`). */
    suspend fun rename(id: TaskId, title: String)

    /** Set the Task's deadline (`PATCH tasks/{id} {"complete_by":"<rfc3339>"}`). */
    suspend fun setDeadline(id: TaskId, completeBy: Instant)

    /** Clear the Task's deadline — `null` = "clear it" (`PATCH tasks/{id} {"complete_by":null}`). */
    suspend fun clearDeadline(id: TaskId)

    /** Set the Task's description (`PATCH tasks/{id} {"description":…}`). */
    suspend fun setDescription(id: TaskId, description: String)

    /** Clear the Task's description (`PATCH tasks/{id} {"description":null}`). */
    suspend fun clearDescription(id: TaskId)

    /** Replace the Task's labels (`PATCH tasks/{id} {"labels":[…]}`). */
    suspend fun setLabels(id: TaskId, labels: List<String>)

    /** Pin or unpin the Task (`PATCH tasks/{id} {"pinned":<bool>}`). */
    suspend fun setPinned(id: TaskId, pinned: Boolean)

    /** Soft-delete the Task (`DELETE tasks/{id}`); optimistically tombstones the local row. */
    suspend fun delete(id: TaskId)
}
