package com.circuitstitch.deferno.core.data.task

import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import kotlinx.coroutines.flow.Flow

/**
 * The local source-of-truth port for Tasks (ADR-0001, #22). The repository talks to *this*, never
 * the network: UI-facing reads are [observeActive]/[observe] DB `Flow`s, and a refresh reconciles
 * through the suspend mutators. Extracting the persistence behind a port keeps the reconcile +
 * hydration *algorithm* (the heart of #22) pure and unit-testable against an in-memory fake on the
 * ADR-0006 JVM-fast path — exactly the AccountManager / `AccountDataStore` split already in this
 * module — while [SqlDelightTaskLocalStore] proves the real SQLite path in its own integration test.
 */
interface TaskLocalStore {

    /**
     * The live (non-tombstoned) Tasks in `sequence` order — the UI-facing list (ADR-0001
     * observe-via-Flow-only). Re-emits whenever the cache changes (insert/upsert/delete/reconcile).
     */
    fun observeActive(): Flow<List<Task>>

    /**
     * The single Task by [id], or `null` when absent — the detail-screen stream that re-emits when
     * a [hydrate] upgrades the row from summary to full. Emits a tombstoned row too (it is not
     * absent — the detail screen can decide how to render a server-deleted Task).
     */
    fun observe(id: TaskId): Flow<Task?>

    /** Every cached id, including tombstones — the reconcile diffs this against a fresh snapshot. */
    suspend fun allIds(): Set<TaskId>

    /** The current row for [id] (tombstone included), or `null` — used to merge before an upsert. */
    suspend fun get(id: TaskId): Task?

    /** Inserts or replaces [task] by its [Task.id]. */
    suspend fun upsert(task: Task)

    /** Hard-deletes the row [id] (the reconcile's "absent from the snapshot" purge). */
    suspend fun delete(id: TaskId)

    /**
     * Runs [block] as one atomic unit against this store, so a full-snapshot reconcile (a batch of
     * upserts + deletes) commits or rolls back together — never leaving the cache half-reconciled
     * (ADR-0001). The [block] receives the same store to issue its mutations through.
     */
    suspend fun transaction(block: suspend (TaskLocalStore) -> Unit)
}
