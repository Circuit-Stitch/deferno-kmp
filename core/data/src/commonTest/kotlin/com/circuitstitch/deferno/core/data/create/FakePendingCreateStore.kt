package com.circuitstitch.deferno.core.data.create

import com.circuitstitch.deferno.core.model.ItemKind

/**
 * In-memory [PendingCreateStore] for the offline-create tests (#185, ADR-0006 JVM-fast path). A plain
 * map keyed by item id; [add] starts a row pending, [confirm] flips it, [rekey] moves the key, [reject]
 * drops it. [SqlDelightPendingCreateStore] proves the SQL mapping separately.
 */
class FakePendingCreateStore(
    initial: List<PendingCreate> = emptyList(),
) : PendingCreateStore {

    private val rows = initial.associateBy { it.itemId }.toMutableMap()

    /** Direct read of the backing rows for assertions. */
    val all: List<PendingCreate> get() = rows.values.toList()

    override suspend fun add(itemId: String, itemKind: ItemKind) {
        rows[itemId] = PendingCreate(itemId, itemKind, PendingCreateState.Pending, canonicalId = null)
    }

    override suspend fun confirm(itemId: String, canonicalId: String) {
        rows[itemId]?.let { rows[itemId] = it.copy(state = PendingCreateState.Confirmed, canonicalId = canonicalId) }
    }

    override suspend fun rekey(fromId: String, toId: String) {
        rows.remove(fromId)?.let { rows[toId] = it.copy(itemId = toId) }
    }

    override suspend fun reject(itemId: String) {
        rows.remove(itemId)
    }

    override suspend fun pendingIds(): Set<String> =
        rows.values.filter { it.state == PendingCreateState.Pending }.map { it.itemId }.toSet()

    override suspend fun all(): List<PendingCreate> = rows.values.toList()
}
