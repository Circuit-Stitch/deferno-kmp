package com.circuitstitch.deferno.core.data.occurrence

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.circuitstitch.deferno.core.database.sql.DefernoDatabase
import com.circuitstitch.deferno.core.model.Occurrence
import com.circuitstitch.deferno.core.model.OccurrenceId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The production [OccurrenceLocalStore] over the SQLDelight [DefernoDatabase] (ADR-0001, #71) — the
 * firing-level sibling of `SqlDelightHabitLocalStore`. Thin SQL<->domain plumbing (via
 * `OccurrenceEntityMapping.kt`); reads are observed via `Query.asFlow().mapToList(...)` (ADR-0001
 * observe-via-Flow-only) so a freshly read firing's `upsert` re-emits the list with no manual refresh.
 */
class SqlDelightOccurrenceLocalStore(
    private val db: DefernoDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : OccurrenceLocalStore {

    private val queries get() = db.occurrenceEntityQueries

    override fun observeForDefinition(definitionId: String): Flow<List<Occurrence>> =
        queries.selectByDefinition(definitionId).asFlow().mapToList(dispatcher).map { rows -> rows.map { it.toDomain() } }

    override fun observe(id: OccurrenceId): Flow<Occurrence?> =
        queries.selectById(id.value).asFlow().mapToOneOrNull(dispatcher).map { it?.toDomain() }

    override suspend fun get(id: OccurrenceId): Occurrence? =
        queries.selectById(id.value).executeAsOneOrNull()?.toDomain()

    override suspend fun upsert(occurrence: Occurrence) {
        val e = occurrence.toEntity()
        queries.insertOrReplace(
            id = e.id,
            definition_id = e.definition_id,
            kind = e.kind,
            occurrence_date = e.occurrence_date,
            occurrence_state = e.occurrence_state,
        )
    }

    override suspend fun delete(id: OccurrenceId) {
        queries.deleteById(id.value)
    }
}
