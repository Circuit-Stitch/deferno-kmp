package com.circuitstitch.deferno.core.data.habit

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.circuitstitch.deferno.core.data.reconcileTransaction
import com.circuitstitch.deferno.core.database.sql.DefernoDatabase
import com.circuitstitch.deferno.core.model.Habit
import com.circuitstitch.deferno.core.model.HabitId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The production [HabitLocalStore] over the SQLDelight [DefernoDatabase] (ADR-0001, #71) — sibling of
 * `SqlDelightTaskLocalStore`. Thin SQL<->domain plumbing (via `HabitEntityMapping.kt`); reads are
 * observed via `Query.asFlow().mapToList(...)` (ADR-0001 observe-via-Flow-only) so a freshly created
 * Habit's `upsert` re-emits the list with no manual refresh.
 */
class SqlDelightHabitLocalStore(
    private val db: DefernoDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : HabitLocalStore {

    private val queries get() = db.habitEntityQueries

    override fun observeActive(): Flow<List<Habit>> =
        queries.selectAllActive().asFlow().mapToList(dispatcher).map { rows -> rows.map { it.toDomain() } }

    override fun observe(id: HabitId): Flow<Habit?> =
        queries.selectById(id.value).asFlow().mapToOneOrNull(dispatcher).map { it?.toDomain() }

    override suspend fun allIds(): Set<HabitId> =
        queries.selectAllIds().executeAsList().mapTo(mutableSetOf(), ::HabitId)

    override suspend fun get(id: HabitId): Habit? =
        queries.selectById(id.value).executeAsOneOrNull()?.toDomain()

    override suspend fun upsert(habit: Habit) {
        queries.insertOrReplace(habit.toEntity())
    }

    override suspend fun delete(id: HabitId) {
        queries.deleteById(id.value)
    }

    override suspend fun transaction(block: suspend (HabitLocalStore) -> Unit) =
        db.reconcileTransaction(this, block)
}
