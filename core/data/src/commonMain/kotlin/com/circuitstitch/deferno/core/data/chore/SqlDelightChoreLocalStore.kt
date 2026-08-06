package com.circuitstitch.deferno.core.data.chore

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.circuitstitch.deferno.core.data.reconcileTransaction
import com.circuitstitch.deferno.core.data.recurring.SeriesInputsTable
import com.circuitstitch.deferno.core.database.sql.DefernoDatabase
import com.circuitstitch.deferno.core.model.Chore
import com.circuitstitch.deferno.core.model.ChoreId
import com.circuitstitch.deferno.core.model.ItemKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The production [ChoreLocalStore] over SQLDelight (ADR-0001, #71) — sibling of the Habit store, down
 * to how it stitches the series expansion inputs (#410) back on from their own tables.
 */
class SqlDelightChoreLocalStore(
    private val db: DefernoDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ChoreLocalStore {

    private val queries get() = db.choreEntityQueries
    private val series = SeriesInputsTable(db, ItemKind.Chore)

    // The inputs are read inline rather than observed as a second Flow: `SeriesInputsTable.write`
    // commits them in the same transaction as the definition row, so this query cannot re-emit until
    // they are already there. Combining two flows instead races — see that class's KDoc.
    override fun observeActive(): Flow<List<Chore>> =
        queries.selectAllActive().asFlow().mapToList(dispatcher).map { rows ->
            val inputs = series.readAll()
            rows.map { it.toDomain(inputs[it.id]) }
        }

    override fun observe(id: ChoreId): Flow<Chore?> =
        queries.selectById(id.value).asFlow().mapToOneOrNull(dispatcher)
            .map { row -> row?.toDomain(series.read(id.value)) }

    override suspend fun allIds(): Set<ChoreId> =
        queries.selectAllIds().executeAsList().mapTo(mutableSetOf(), ::ChoreId)

    override suspend fun get(id: ChoreId): Chore? =
        queries.selectById(id.value).executeAsOneOrNull()?.toDomain(series.read(id.value))

    override suspend fun upsert(chore: Chore) {
        series.write(chore.id.value, chore.series) { queries.insertOrReplace(chore.toEntity()) }
    }

    override suspend fun delete(id: ChoreId) {
        series.write(id.value, series = null) { queries.deleteById(id.value) }
    }

    override suspend fun transaction(block: suspend (ChoreLocalStore) -> Unit) =
        db.reconcileTransaction(this, block)
}
