package com.circuitstitch.deferno.core.data.task

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.circuitstitch.deferno.core.database.sql.DefernoDatabase
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * The production [TaskLocalStore] over the SQLDelight [DefernoDatabase] (ADR-0001, #22). It is the
 * thin translation layer between the adapter-free SQL rows (#21) and the domain [Task]
 * (via `TaskEntityMapping.kt`); the reconcile/hydration *policy* lives in [OfflineTaskRepository],
 * proved against the in-memory fake, while *this* class's job is only the SQL <-> domain plumbing,
 * proved by the real-SQLite `SqlDelightTaskLocalStoreTest` (ADR-0006 JVM-fast path).
 *
 * Reads are observed via `Query.asFlow().mapToList(...)` — the observe-via-Flow-only seam of
 * ADR-0001. The observe [dispatcher] is injected (default [Dispatchers.Default]) so a test can run
 * the Flow on its own scheduler; SQLDelight notifies its query listeners after a [transaction]
 * commits, so the reconcile's batch of mutations re-emits the list exactly once.
 */
class SqlDelightTaskLocalStore(
    private val db: DefernoDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : TaskLocalStore {

    private val queries get() = db.taskEntityQueries

    override fun observeActive(): Flow<List<Task>> =
        queries.selectAllActive().asFlow().mapToList(dispatcher).map { rows -> rows.map { it.toDomain() } }

    override fun observe(id: TaskId): Flow<Task?> =
        queries.selectById(id.value).asFlow().mapToOneOrNull(dispatcher).map { it?.toDomain() }

    override suspend fun allIds(): Set<TaskId> =
        queries.selectAllIds().executeAsList().mapTo(mutableSetOf(), ::TaskId)

    override suspend fun get(id: TaskId): Task? =
        queries.selectById(id.value).executeAsOneOrNull()?.toDomain()

    override suspend fun upsert(task: Task) {
        val e = task.toEntity()
        queries.insertOrReplace(
            id = e.id,
            org_slug = e.org_slug,
            owner_org_id = e.owner_org_id,
            ref = e.ref,
            sequence = e.sequence,
            title = e.title,
            working_state = e.working_state,
            labels = e.labels,
            parent_id = e.parent_id,
            child_ids = e.child_ids,
            complete_by = e.complete_by,
            productive = e.productive,
            desire = e.desire,
            pinned = e.pinned,
            date_created = e.date_created,
            finished_at = e.finished_at,
            deleted_at = e.deleted_at,
            hydration_state = e.hydration_state,
            description = e.description,
            next_task_id = e.next_task_id,
        )
    }

    override suspend fun delete(id: TaskId) {
        queries.deleteById(id.value)
    }

    override suspend fun transaction(block: suspend (TaskLocalStore) -> Unit) {
        // SQLDelight's `transaction { }` body is non-suspending, so we can't simply `await` the
        // suspend [block] inside it. But every mutation the reconcile issues through this store is a
        // synchronous SQLDelight query under the hood — the `suspend` is on the interface only to let
        // an alternative store (or the fake) be genuinely async. So the block runs to completion
        // synchronously: we drive it with [startCoroutine] and surface any failure (or a — never
        // expected — real suspension) by inspecting the completion result. Running it inside
        // `db.transaction { }` makes the whole reconcile atomic and fires query listeners once, at
        // commit (so `observeActive()` re-emits the reconciled list exactly once).
        var outcome: Result<Unit>? = null
        db.transaction {
            block.startCoroutine(
                this@SqlDelightTaskLocalStore,
                Continuation(EmptyCoroutineContext) { result -> outcome = result },
            )
        }
        val result = checkNotNull(outcome) {
            "the reconcile block suspended on something other than the synchronous local store; " +
                "TaskLocalStore.transaction requires a non-suspending body"
        }
        result.getOrThrow()
    }
}
