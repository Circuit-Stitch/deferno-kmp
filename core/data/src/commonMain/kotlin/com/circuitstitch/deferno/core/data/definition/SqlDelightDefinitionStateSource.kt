package com.circuitstitch.deferno.core.data.definition

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.circuitstitch.deferno.core.data.recurring.toDefinitionStateOrDefault
import com.circuitstitch.deferno.core.database.sql.DefernoDatabase
import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.ItemKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The production [DefinitionStateSource] over the SQLDelight [DefernoDatabase] (ADR-0053 decision 4,
 * #390).
 *
 * It reads the item table **directly**, through a narrow `(id, kind, definition_state)` query, rather
 * than going through [com.circuitstitch.deferno.core.data.item.ItemLocalStore]. The reading wants one
 * enum per definition; going through the store would rebuild the whole plugin-shaped record — fourteen
 * recurrence columns, a sealed `Cadence` and a recipe pass per row — on every emission of a flow the day
 * agenda re-collects on every write.
 *
 * It was three kind-qualified queries over three tables until #422. One table means one query, and the
 * `kind` a [DefinitionRef] needs comes off the row rather than from which query answered.
 *
 * Tombstoned rows are excluded by the query itself, so a soft-deleted definition resolves to `null`,
 * which the resolver reads as Unknown and never as Missed. A Task has no light switch at all, so its
 * NULL `definition_state` excludes it by the same clause. An unrecognised stored token degrades to
 * [DefinitionState.Active], the defensive rule every other decode here uses.
 */
class SqlDelightDefinitionStateSource(
    private val db: DefernoDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : DefinitionStateSource {

    private val queries get() = db.itemEntityQueries

    override fun observeAll(): Flow<Map<DefinitionRef, DefinitionState>> =
        queries.selectDefinitionStates().asFlow().mapToList(dispatcher).map { rows ->
            buildMap {
                for (row in rows) {
                    val kind = ItemKind.entries.firstOrNull { it.name == row.kind } ?: continue
                    put(DefinitionRef(kind, row.id), row.definition_state.toDefinitionStateOrDefault())
                }
            }
        }

    override suspend fun get(kind: ItemKind, definitionId: String): DefinitionState? {
        // A Task has no light switch — its lifecycle is a WorkingState — so there is nothing to look up
        // rather than nothing found. Both answer `null`, and the resolver reads either as Unknown, which
        // is the honest reading for a firing whose definition this device cannot see.
        if (kind == ItemKind.Task) return null
        val token = queries.selectDefinitionStateById(definitionId).executeAsOneOrNull() ?: return null
        return token.toDefinitionStateOrDefault()
    }
}
