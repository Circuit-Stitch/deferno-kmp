package com.circuitstitch.deferno.core.data.event

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.circuitstitch.deferno.core.data.reconcileTransaction
import com.circuitstitch.deferno.core.data.recurring.SeriesInputsTable
import com.circuitstitch.deferno.core.database.sql.DefernoDatabase
import com.circuitstitch.deferno.core.model.Event
import com.circuitstitch.deferno.core.model.EventId
import com.circuitstitch.deferno.core.model.ItemKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The production [EventLocalStore] over SQLDelight (ADR-0001, #71) — sibling of the Habit store, down
 * to how it stitches the series expansion inputs (#410) back on from their own tables.
 */
class SqlDelightEventLocalStore(
    private val db: DefernoDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : EventLocalStore {

    private val queries get() = db.eventEntityQueries
    private val series = SeriesInputsTable(db, ItemKind.Event)

    // The inputs are read inline rather than observed as a second Flow: `SeriesInputsTable.write`
    // commits them in the same transaction as the definition row, so this query cannot re-emit until
    // they are already there. Combining two flows instead races — see that class's KDoc.
    override fun observeActive(): Flow<List<Event>> =
        queries.selectAllActive().asFlow().mapToList(dispatcher).map { rows ->
            val inputs = series.readAll()
            rows.map { it.toDomain(inputs[it.id]) }
        }

    override fun observe(id: EventId): Flow<Event?> =
        queries.selectById(id.value).asFlow().mapToOneOrNull(dispatcher)
            .map { row -> row?.toDomain(series.read(id.value)) }

    override suspend fun allIds(): Set<EventId> =
        queries.selectAllIds().executeAsList().mapTo(mutableSetOf(), ::EventId)

    override suspend fun get(id: EventId): Event? =
        queries.selectById(id.value).executeAsOneOrNull()?.toDomain(series.read(id.value))

    override suspend fun upsert(event: Event) {
        series.write(event.id.value, event.series) { queries.insertOrReplace(event.toEntity()) }
    }

    override suspend fun delete(id: EventId) {
        series.write(id.value, series = null) { queries.deleteById(id.value) }
    }

    override suspend fun transaction(block: suspend (EventLocalStore) -> Unit) =
        db.reconcileTransaction(this, block)
}
