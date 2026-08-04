package com.circuitstitch.deferno.core.data.occurrence

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.circuitstitch.deferno.core.database.sql.DefernoDatabase
import com.circuitstitch.deferno.core.database.sql.OccurrenceCoverageEntity
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.OccurrenceCoverage
import com.circuitstitch.deferno.core.model.mergeCoverage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate

/**
 * The production [OccurrenceCoverageLocalStore] over the SQLDelight [DefernoDatabase] (ADR-0053
 * decision 4, #390) — the companion to [SqlDelightOccurrenceFactLocalStore] that makes an absent fact
 * legible: inside coverage it is evidence, outside it only ignorance.
 *
 * [record] is a read-merge-write inside **one** transaction, and the merge itself is the pure
 * `List<OccurrenceCoverage>.mergeCoverage` from core:model rather than SQL. That split is deliberate:
 * coalescing is the one place this slice can silently re-introduce the defect ADR-0053 exists to close
 * (join two windows separated by a gap and every unsynced day inside it starts reading as Missed), so
 * the rule is a pure function with its own tests rather than a clever `UPDATE … WHERE` in a string.
 * The merged set then **replaces** the definition's rows wholesale, so a coalesce that reduces the row
 * count cannot strand the row it absorbed.
 */
class SqlDelightOccurrenceCoverageLocalStore(
    private val db: DefernoDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : OccurrenceCoverageLocalStore {

    private val queries get() = db.occurrenceCoverageEntityQueries

    override fun observeCovering(date: LocalDate): Flow<List<OccurrenceCoverage>> =
        queries.selectCovering(date.toString(), date.toString()).asFlow().mapToList(dispatcher)
            .map { rows -> rows.mapNotNull { it.toDomainOrNull() } }

    override suspend fun get(kind: ItemKind, definitionId: String): List<OccurrenceCoverage> =
        queries.selectForDefinition(kind.name, definitionId).executeAsList().mapNotNull { it.toDomainOrNull() }

    override suspend fun record(coverage: OccurrenceCoverage) {
        queries.transaction {
            val existing = queries.selectForDefinition(coverage.kind.name, coverage.definitionId)
                .executeAsList().mapNotNull { it.toDomainOrNull() }
            // `existing` is already scoped to this definition, so mergeCoverage's "other definitions
            // pass through" arm contributes nothing — what comes back is exactly this definition's
            // disjoint, ascending set with the new window folded in.
            val merged = existing.mergeCoverage(coverage)
            queries.deleteForDefinition(coverage.kind.name, coverage.definitionId)
            merged.forEach { queries.insertOrReplace(it.toEntity()) }
        }
    }

    override suspend fun clear(kind: ItemKind, definitionId: String) {
        queries.deleteForDefinition(kind.name, definitionId)
    }
}

/**
 * Row -> domain. An unrecognised stored `kind` token drops the range, for the same reason the fact
 * mapping drops such a row: coverage is claimed *per definition*, and mis-filing a synced window under
 * the wrong kind would assert evidence about firings that were never fetched.
 */
private fun OccurrenceCoverageEntity.toDomainOrNull(): OccurrenceCoverage? {
    val itemKind = ItemKind.entries.firstOrNull { it.name == kind } ?: return null
    return OccurrenceCoverage(
        kind = itemKind,
        definitionId = definition_id,
        from = LocalDate.parse(from_date),
        to = LocalDate.parse(to_date),
    )
}

private fun OccurrenceCoverage.toEntity(): OccurrenceCoverageEntity = OccurrenceCoverageEntity(
    kind = kind.name,
    definition_id = definitionId,
    from_date = from.toString(),
    to_date = to.toString(),
)
