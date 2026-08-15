package com.circuitstitch.deferno.core.data.item

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.circuitstitch.deferno.core.data.reconcileTransaction
import com.circuitstitch.deferno.core.data.recurring.SeriesInputsTable
import com.circuitstitch.deferno.core.database.sql.DefernoDatabase
import com.circuitstitch.deferno.core.database.sql.ItemEntity
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.SeriesInputs
import com.circuitstitch.deferno.core.model.recipe.KindRecipe
import com.circuitstitch.deferno.core.model.recipe.KindRow
import com.circuitstitch.deferno.core.model.recipe.ParityRecipe
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The production [ItemLocalStore] over the SQLDelight [DefernoDatabase] (ADR-0001, ADR-0055, #422). It
 * is the thin translation between the adapter-free SQL rows and the plugin-shaped record, and it does
 * nothing else: the reconcile and hydration *policy* lives in the repositories, proved against the
 * in-memory fake, while this class's SQL plumbing is proved by `SqlDelightItemLocalStoreTest` on the
 * JVM-fast path (ADR-0006).
 *
 * **Two translations, each gated by its own round trip.** A stored row becomes a [KindRow] through
 * `ItemEntityMapping.kt`, and that becomes an [com.circuitstitch.deferno.core.model.plugin.Item]
 * through the [recipe]. Storage fidelity is therefore the composition of two identities, and both are
 * asserted: `ItemEntityMappingTest` for the first and `KindRecipeRoundTripTest` for the second.
 *
 * [recipe] is a constructor seam because ADR-0056 puts two recipes behind one interface. The target
 * recipe lands later, one Family at a time, and swaps in here — the same seam `PluginItemRepository`
 * carries.
 *
 * Reads are observed via `Query.asFlow().mapToList(...)`, the observe-via-Flow-only seam of ADR-0001.
 * The observe [dispatcher] is injected so a test can run the Flow on its own scheduler. SQLDelight
 * notifies its query listeners after a [transaction] commits, so a reconcile's batch of mutations
 * re-emits the list exactly once.
 *
 * The series expansion inputs live in their own tables and are stitched onto every read and replaced
 * alongside every write through [SeriesInputsTable]. They are read inline rather than observed as a
 * second Flow: `SeriesInputsTable.write` commits them in the same transaction as the item row, so this
 * query cannot re-emit until they are already there. Combining two flows instead races — see that
 * class's KDoc.
 */
class SqlDelightItemLocalStore(
    private val db: DefernoDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val recipe: KindRecipe = ParityRecipe,
) : ItemLocalStore {

    private val queries get() = db.itemEntityQueries
    private val series = SeriesInputsTable(db)

    override fun observeActive(): Flow<List<CachedItem>> =
        queries.selectAllActive().asFlow().mapToList(dispatcher).map { it.toCachedItems() }

    override fun observeActive(kind: ItemKind): Flow<List<CachedItem>> =
        queries.selectActiveOfKind(kind.name).asFlow().mapToList(dispatcher).map { it.toCachedItems() }

    override fun observe(id: String): Flow<CachedItem?> =
        queries.selectById(id).asFlow().mapToOneOrNull(dispatcher).map { row ->
            row?.toCachedItem(series.read(id))
        }

    override suspend fun allIds(): Set<String> = queries.selectAllIds().executeAsList().toSet()

    override suspend fun get(id: String): CachedItem? =
        queries.selectById(id).executeAsOneOrNull()?.toCachedItem(series.read(id))

    override suspend fun upsert(row: CachedItem) {
        val kindRow = recipe.write(row.item, row.kind)
        series.write(row.id, row.item.repeats.series) { queries.insertOrReplace(kindRow.toEntity()) }
    }

    override suspend fun delete(id: String) {
        series.write(id, series = null) { queries.deleteById(id) }
    }

    override suspend fun transaction(block: suspend (ItemLocalStore) -> Unit) =
        db.reconcileTransaction(this, block)

    /**
     * One list read plus one pass over the series tables, not two queries per row.
     *
     * A row whose `kind` column names none of the four is dropped rather than degraded. There is no safe
     * kind to fall back to, and rendering a Habit as a Task would give it a working state it has never
     * had — see `ItemEntityMapping.toKindRow`.
     */
    private fun List<ItemEntity>.toCachedItems(): List<CachedItem> {
        val inputs = series.readAll()
        return mapNotNull { it.toCachedItem(inputs[it.id]) }
    }

    private fun ItemEntity.toCachedItem(series: SeriesInputs?): CachedItem? =
        toKindRow(series)?.let { CachedItem(recipe.read(it), it.kind) }
}
