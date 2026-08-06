package com.circuitstitch.deferno.core.data.habit

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.circuitstitch.deferno.core.data.reconcileTransaction
import com.circuitstitch.deferno.core.data.recurring.SeriesInputsTable
import com.circuitstitch.deferno.core.database.sql.DefernoDatabase
import com.circuitstitch.deferno.core.model.Habit
import com.circuitstitch.deferno.core.model.HabitId
import com.circuitstitch.deferno.core.model.ItemKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The production [HabitLocalStore] over the SQLDelight [DefernoDatabase] (ADR-0001, #71) — sibling of
 * `SqlDelightTaskLocalStore`. Thin SQL<->domain plumbing (via `HabitEntityMapping.kt`); reads are
 * observed via `Query.asFlow().mapToList(...)` (ADR-0001 observe-via-Flow-only) so a freshly created
 * Habit's `upsert` re-emits the list with no manual refresh.
 *
 * The series expansion inputs (#410) live in their own kind-neutral tables rather than in
 * `habitEntity`, so every read here stitches them back on through [SeriesInputsTable] and every write
 * replaces them alongside the row. A Habit that leaves this store therefore always carries whatever
 * grid its cache can reproduce — the seam that lets a caller reach `expandOccurrenceGrid` with no
 * network at all.
 */
class SqlDelightHabitLocalStore(
    private val db: DefernoDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : HabitLocalStore {

    private val queries get() = db.habitEntityQueries
    private val series = SeriesInputsTable(db, ItemKind.Habit)

    // The inputs are read inline rather than observed as a second Flow: `SeriesInputsTable.write`
    // commits them in the same transaction as the definition row, so this query cannot re-emit until
    // they are already there. Combining two flows instead races — see that class's KDoc.
    override fun observeActive(): Flow<List<Habit>> =
        queries.selectAllActive().asFlow().mapToList(dispatcher).map { rows ->
            val inputs = series.readAll()
            rows.map { it.toDomain(inputs[it.id]) }
        }

    override fun observe(id: HabitId): Flow<Habit?> =
        queries.selectById(id.value).asFlow().mapToOneOrNull(dispatcher)
            .map { row -> row?.toDomain(series.read(id.value)) }

    override suspend fun allIds(): Set<HabitId> =
        queries.selectAllIds().executeAsList().mapTo(mutableSetOf(), ::HabitId)

    override suspend fun get(id: HabitId): Habit? =
        queries.selectById(id.value).executeAsOneOrNull()?.toDomain(series.read(id.value))

    override suspend fun upsert(habit: Habit) {
        series.write(habit.id.value, habit.series) { queries.insertOrReplace(habit.toEntity()) }
    }

    override suspend fun delete(id: HabitId) {
        series.write(id.value, series = null) { queries.deleteById(id.value) }
    }

    override suspend fun transaction(block: suspend (HabitLocalStore) -> Unit) =
        db.reconcileTransaction(this, block)
}
