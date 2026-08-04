package com.circuitstitch.deferno.core.data.occurrence

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.circuitstitch.deferno.core.data.reconcileTransaction
import com.circuitstitch.deferno.core.database.sql.DefernoDatabase
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.OccurrenceFact
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate

/**
 * The production [OccurrenceFactLocalStore] over the SQLDelight [DefernoDatabase] (ADR-0001, ADR-0053
 * decision 4, #390). Thin SQL<->domain plumbing only (via `OccurrenceFactEntityMapping.kt`); the
 * *reading* built on top of these facts is `resolveOccurrenceState`, which is pure and lives in
 * core:model — nothing in this class knows what today is, which is the point.
 *
 * Reads are observed via `Query.asFlow().mapToList(...)` — the observe-via-Flow-only seam of ADR-0001
 * — with the dispatcher injected (default [Dispatchers.Default]) so a test can run the Flow on its own
 * scheduler. SQLDelight notifies its query listeners after a transaction commits, so a
 * [replaceRange] batch re-emits each affected query exactly once.
 *
 * Every read maps with `mapNotNull`: a row whose stored `kind` token this build cannot model is dropped
 * rather than mis-filed (see `OccurrenceFactEntityMapping.kt` for why the two defensive decodes
 * deliberately differ).
 */
class SqlDelightOccurrenceFactLocalStore(
    private val db: DefernoDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : OccurrenceFactLocalStore {

    private val queries get() = db.occurrenceFactEntityQueries

    override fun observeOn(date: LocalDate): Flow<List<OccurrenceFact>> =
        queries.selectOn(date.toString()).asFlow().mapToList(dispatcher)
            .map { rows -> rows.mapNotNull { it.toDomainOrNull() } }

    override fun observeInRange(
        kind: ItemKind,
        definitionId: String,
        from: LocalDate,
        to: LocalDate,
    ): Flow<List<OccurrenceFact>> =
        queries.selectInRange(kind.name, definitionId, from.toString(), to.toString())
            .asFlow().mapToList(dispatcher)
            .map { rows -> rows.mapNotNull { it.toDomainOrNull() } }

    override fun observe(kind: ItemKind, definitionId: String, date: LocalDate): Flow<OccurrenceFact?> =
        queries.selectOne(kind.name, definitionId, date.toString())
            .asFlow().mapToOneOrNull(dispatcher)
            .map { it?.toDomainOrNull() }

    override suspend fun get(kind: ItemKind, definitionId: String, date: LocalDate): OccurrenceFact? =
        queries.selectOne(kind.name, definitionId, date.toString()).executeAsOneOrNull()?.toDomainOrNull()

    override suspend fun upsert(fact: OccurrenceFact) {
        queries.insertOrReplace(fact.toEntity())
    }

    override suspend fun delete(kind: ItemKind, definitionId: String, date: LocalDate) {
        queries.deleteOne(kind.name, definitionId, date.toString())
    }

    override suspend fun replaceRange(
        kind: ItemKind,
        definitionId: String,
        from: LocalDate,
        to: LocalDate,
        facts: List<OccurrenceFact>,
    ) {
        queries.transaction {
            queries.deleteInRange(kind.name, definitionId, from.toString(), to.toString())
            facts.forEach { queries.insertOrReplace(it.toEntity()) }
        }
    }

    override suspend fun transaction(block: suspend (OccurrenceFactLocalStore) -> Unit) =
        db.reconcileTransaction(this, block)
}
