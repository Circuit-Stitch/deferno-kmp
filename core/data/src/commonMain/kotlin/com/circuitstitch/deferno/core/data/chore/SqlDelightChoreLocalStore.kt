package com.circuitstitch.deferno.core.data.chore

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.circuitstitch.deferno.core.data.reconcileTransaction
import com.circuitstitch.deferno.core.database.sql.DefernoDatabase
import com.circuitstitch.deferno.core.model.Chore
import com.circuitstitch.deferno.core.model.ChoreId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The production [ChoreLocalStore] over SQLDelight (ADR-0001, #71) — sibling of the Habit store. */
class SqlDelightChoreLocalStore(
    private val db: DefernoDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ChoreLocalStore {

    private val queries get() = db.choreEntityQueries

    override fun observeActive(): Flow<List<Chore>> =
        queries.selectAllActive().asFlow().mapToList(dispatcher).map { rows -> rows.map { it.toDomain() } }

    override fun observe(id: ChoreId): Flow<Chore?> =
        queries.selectById(id.value).asFlow().mapToOneOrNull(dispatcher).map { it?.toDomain() }

    override suspend fun allIds(): Set<ChoreId> =
        queries.selectAllIds().executeAsList().mapTo(mutableSetOf(), ::ChoreId)

    override suspend fun get(id: ChoreId): Chore? =
        queries.selectById(id.value).executeAsOneOrNull()?.toDomain()

    override suspend fun upsert(chore: Chore) {
        queries.insertOrReplace(chore.toEntity())
    }

    override suspend fun delete(id: ChoreId) {
        queries.deleteById(id.value)
    }

    override suspend fun transaction(block: suspend (ChoreLocalStore) -> Unit) =
        db.reconcileTransaction(this, block)
}
