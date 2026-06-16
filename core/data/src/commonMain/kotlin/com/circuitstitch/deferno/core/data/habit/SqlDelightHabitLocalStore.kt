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
        val e = habit.toEntity()
        queries.insertOrReplace(
            id = e.id,
            org_slug = e.org_slug,
            owner_org_id = e.owner_org_id,
            ref = e.ref,
            sequence = e.sequence,
            title = e.title,
            definition_state = e.definition_state,
            recurrence_type = e.recurrence_type,
            recurrence_days = e.recurrence_days,
            labels = e.labels,
            parent_id = e.parent_id,
            complete_by = e.complete_by,
            deadline_time_of_day = e.deadline_time_of_day,
            pinned = e.pinned,
            date_created = e.date_created,
            deleted_at = e.deleted_at,
            hydration_state = e.hydration_state,
            description = e.description,
            series_id = e.series_id,
        )
    }

    override suspend fun delete(id: HabitId) {
        queries.deleteById(id.value)
    }

    override suspend fun transaction(block: suspend (HabitLocalStore) -> Unit) =
        db.reconcileTransaction(this, block)
}
