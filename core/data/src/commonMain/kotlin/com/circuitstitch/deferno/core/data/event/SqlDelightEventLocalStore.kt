package com.circuitstitch.deferno.core.data.event

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.circuitstitch.deferno.core.data.reconcileTransaction
import com.circuitstitch.deferno.core.database.sql.DefernoDatabase
import com.circuitstitch.deferno.core.model.Event
import com.circuitstitch.deferno.core.model.EventId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The production [EventLocalStore] over SQLDelight (ADR-0001, #71) — sibling of the Habit store. */
class SqlDelightEventLocalStore(
    private val db: DefernoDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : EventLocalStore {

    private val queries get() = db.eventEntityQueries

    override fun observeActive(): Flow<List<Event>> =
        queries.selectAllActive().asFlow().mapToList(dispatcher).map { rows -> rows.map { it.toDomain() } }

    override fun observe(id: EventId): Flow<Event?> =
        queries.selectById(id.value).asFlow().mapToOneOrNull(dispatcher).map { it?.toDomain() }

    override suspend fun allIds(): Set<EventId> =
        queries.selectAllIds().executeAsList().mapTo(mutableSetOf(), ::EventId)

    override suspend fun get(id: EventId): Event? =
        queries.selectById(id.value).executeAsOneOrNull()?.toDomain()

    override suspend fun upsert(event: Event) {
        val e = event.toEntity()
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
            all_day = e.all_day,
            complete_by = e.complete_by,
            end_time = e.end_time,
            start_time_of_day = e.start_time_of_day,
            end_time_of_day = e.end_time_of_day,
            labels = e.labels,
            parent_id = e.parent_id,
            pinned = e.pinned,
            date_created = e.date_created,
            deleted_at = e.deleted_at,
            hydration_state = e.hydration_state,
            description = e.description,
            series_id = e.series_id,
        )
    }

    override suspend fun delete(id: EventId) {
        queries.deleteById(id.value)
    }

    override suspend fun transaction(block: suspend (EventLocalStore) -> Unit) =
        db.reconcileTransaction(this, block)
}
