package com.circuitstitch.deferno.core.data.create

import com.circuitstitch.deferno.core.database.sql.DefernoDatabase
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.database.sql.PendingItemCreate as PendingRow

/**
 * The production [PendingCreateStore] over the SQLDelight [DefernoDatabase] (#185). Thin SQL ↔ domain
 * plumbing for the `pendingItemCreate` rows; the lifecycle *policy* (when to confirm/reject/heal) lives
 * in the outbox replay listener, proved against the in-memory fake on the ADR-0006 JVM-fast path, while
 * this class proves the SQL mapping. `state`/`kind` encode via the enum `.name` (decoded defensively).
 */
class SqlDelightPendingCreateStore(
    private val db: DefernoDatabase,
) : PendingCreateStore {

    private val queries get() = db.pendingItemCreateQueries

    override suspend fun add(itemId: String, itemKind: ItemKind) {
        queries.add(itemId, itemKind.name)
    }

    override suspend fun confirm(itemId: String, canonicalId: String) {
        // confirm: SET canonical_id = ? WHERE item_id = ?
        queries.confirm(canonicalId, itemId)
    }

    override suspend fun rekey(fromId: String, toId: String) {
        // rekey: SET item_id = ? WHERE item_id = ?  → (new, old)
        queries.rekey(toId, fromId)
    }

    override suspend fun reject(itemId: String) {
        queries.deleteById(itemId)
    }

    override suspend fun pendingIds(): Set<String> =
        queries.selectPendingIds().executeAsList().toSet()

    override suspend fun all(): List<PendingCreate> =
        queries.selectAll().executeAsList().map { it.toDomain() }
}

/** Decodes a stored `pendingItemCreate` row; an unrecognised kind/state token degrades defensively. */
private fun PendingRow.toDomain(): PendingCreate = PendingCreate(
    itemId = item_id,
    itemKind = ItemKind.entries.firstOrNull { it.name == item_kind } ?: ItemKind.Task,
    state = if (state == "confirmed") PendingCreateState.Confirmed else PendingCreateState.Pending,
    canonicalId = canonical_id,
)
