package com.circuitstitch.deferno.core.data.history

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.circuitstitch.deferno.core.database.sql.DefernoDatabase
import com.circuitstitch.deferno.core.database.sql.ItemHistoryEntry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * A persisted item-history row, wire-shaped and network-agnostic: the RFC3339 [recordedAt] (the feed's
 * sort key) plus the opaque [payload] (the serialized `TaskAction` kind). The repository owns turning a
 * [payload] into a domain `ItemHistoryEvent`; this keeps the store free of any network/serialization
 * coupling.
 */
data class StoredHistoryRow(val recordedAt: String, val payload: String)

/**
 * The local cache of a Item's server-authored history (ADR-0043, #197). The detail observes [observe]
 * as a `Flow`; a task-detail open fires a best-effort fetch whose result [replaceForItem] writes,
 * REPLACING the item's rows wholesale — server history is append-only + server-derived, so a whole
 * replace is dedup-free (there is no stable event id to merge on). Distinct from #260's global
 * `activityLedgerEntry`.
 */
interface ItemHistoryLocalStore {

    /** The cached history for [itemId], oldest-first (the View interleaves + sorts the merged feed). */
    fun observe(itemId: String): Flow<List<StoredHistoryRow>>

    /** Replace [itemId]'s cached history with [rows] (delete-by-item then insert-each, atomically). */
    suspend fun replaceForItem(itemId: String, rows: List<StoredHistoryRow>)

    /** Drop every cached history row (account sign-out cleanup). */
    suspend fun clear()
}

/** The production [ItemHistoryLocalStore] over the SQLDelight [DefernoDatabase]. */
class SqlDelightItemHistoryLocalStore(
    private val db: DefernoDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ItemHistoryLocalStore {

    private val queries get() = db.itemHistoryEntryQueries

    override fun observe(itemId: String): Flow<List<StoredHistoryRow>> =
        queries.selectByItem(itemId)
            .asFlow()
            .mapToList(dispatcher)
            .map { rows -> rows.map { it.toRow() } }

    override suspend fun replaceForItem(itemId: String, rows: List<StoredHistoryRow>) {
        queries.transaction {
            queries.deleteByItem(itemId)
            rows.forEach { queries.insert(item_id = itemId, recorded_at = it.recordedAt, payload = it.payload) }
        }
    }

    override suspend fun clear() {
        queries.deleteAll()
    }
}

private fun ItemHistoryEntry.toRow(): StoredHistoryRow = StoredHistoryRow(recordedAt = recorded_at, payload = payload)
