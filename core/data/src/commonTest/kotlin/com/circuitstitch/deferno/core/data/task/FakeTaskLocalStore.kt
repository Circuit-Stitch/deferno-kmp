package com.circuitstitch.deferno.core.data.task

import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory [TaskLocalStore] for the reconcile/hydration unit tests (#22, ADR-0006 JVM-fast path).
 * Backed by a [MutableStateFlow] map keyed by [TaskId], so [observeActive]/[observe] are real,
 * re-emitting `Flow`s (Turbine-observable) without a database — mirroring `FakeAccountDataStore`.
 * The reconcile/hydration *algorithm* is proved against this fake; [SqlDelightTaskLocalStore] proves
 * the SQL translation separately, so neither test has to carry both concerns.
 *
 * **Transaction semantics mirror SQLDelight.** SQLDelight only fires its query listeners *after* a
 * transaction commits, so a multi-mutation reconcile re-emits the observed list exactly once. The
 * fake reproduces that: inside [transaction] the mutations are staged on a working copy and the
 * backing [MutableStateFlow] is published a single time at commit — otherwise the fake would leak
 * intermediate per-`upsert` emissions the real store never produces.
 */
class FakeTaskLocalStore(
    initial: Map<TaskId, Task> = emptyMap(),
) : TaskLocalStore {

    private val rows = MutableStateFlow(initial)

    /** True while a [transaction] is staging mutations; suspends per-mutation publishing until commit. */
    private var inTransaction = false
    private var staged: MutableMap<TaskId, Task> = mutableMapOf()

    /** Direct read of the committed backing map (tombstones included) for assertions. */
    val all: Map<TaskId, Task> get() = rows.value

    override fun observeActive(): Flow<List<Task>> = rows.map { snapshot ->
        snapshot.values
            .filterNot { it.isDeleted }
            .sortedBy { it.sequence }
    }

    override fun observe(id: TaskId): Flow<Task?> = rows.map { it[id] }

    override suspend fun allIds(): Set<TaskId> = current().keys

    override suspend fun get(id: TaskId): Task? = current()[id]

    override suspend fun upsert(task: Task) {
        mutate { it[task.id] = task }
    }

    override suspend fun delete(id: TaskId) {
        mutate { it.remove(id) }
    }

    override suspend fun transaction(block: suspend (TaskLocalStore) -> Unit) {
        check(!inTransaction) { "nested transactions are not supported by the fake" }
        inTransaction = true
        staged = rows.value.toMutableMap()
        try {
            block(this)
            rows.value = staged.toMap() // single commit-time emission
        } finally {
            inTransaction = false
        }
    }

    /** The live view a read/mutation sees — the staged copy mid-transaction, else the committed map. */
    private fun current(): Map<TaskId, Task> = if (inTransaction) staged else rows.value

    private fun mutate(edit: (MutableMap<TaskId, Task>) -> Unit) {
        if (inTransaction) {
            edit(staged)
        } else {
            rows.value = rows.value.toMutableMap().also(edit)
        }
    }
}
