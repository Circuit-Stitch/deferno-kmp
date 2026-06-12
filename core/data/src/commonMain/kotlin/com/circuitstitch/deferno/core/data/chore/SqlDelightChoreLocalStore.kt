package com.circuitstitch.deferno.core.data.chore

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
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

    override suspend fun get(id: ChoreId): Chore? =
        queries.selectById(id.value).executeAsOneOrNull()?.toDomain()

    override suspend fun upsert(chore: Chore) {
        val e = chore.toEntity()
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
            cadence_mode = e.cadence_mode,
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

    override suspend fun delete(id: ChoreId) {
        queries.deleteById(id.value)
    }
}
