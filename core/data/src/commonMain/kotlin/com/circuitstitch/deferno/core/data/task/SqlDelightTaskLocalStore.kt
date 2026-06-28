package com.circuitstitch.deferno.core.data.task

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.circuitstitch.deferno.core.data.reconcileTransaction
import com.circuitstitch.deferno.core.database.sql.DefernoDatabase
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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
            deadline_time_of_day = e.deadline_time_of_day,
            productive = e.productive,
            desire = e.desire,
            pinned = e.pinned,
            date_created = e.date_created,
            finished_at = e.finished_at,
            deleted_at = e.deleted_at,
            hydration_state = e.hydration_state,
            description = e.description,
            next_task_id = e.next_task_id,
            descendant_done = e.descendant_done,
            descendant_total = e.descendant_total,
            blocked = e.blocked,
            is_blocker = e.is_blocker,
            external_source = e.external_source,
            external_id = e.external_id,
            external_url = e.external_url,
        )
    }

    override suspend fun delete(id: TaskId) {
        queries.deleteById(id.value)
    }

    override suspend fun transaction(block: suspend (TaskLocalStore) -> Unit) =
        db.reconcileTransaction(this, block)
}
