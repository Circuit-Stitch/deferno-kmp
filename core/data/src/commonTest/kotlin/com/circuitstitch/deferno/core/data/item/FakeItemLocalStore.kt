package com.circuitstitch.deferno.core.data.item

import com.circuitstitch.deferno.core.model.ItemKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory [ItemLocalStore] for the reconcile/hydration unit tests (#22, #422, ADR-0006 JVM-fast
 * path). Backed by a [MutableStateFlow] map keyed by the row id, so [observeActive] and [observe] are
 * real, re-emitting `Flow`s (Turbine-observable) without a database. It replaces `FakeTaskLocalStore`
 * and the three recurring-kind fakes, which held one copy each of this same contract.
 *
 * The reconcile/hydration *algorithm* is proved against this fake; [SqlDelightItemLocalStore] proves
 * the SQL translation separately, so neither test has to carry both concerns.
 *
 * **Transaction semantics mirror SQLDelight.** SQLDelight only fires its query listeners *after* a
 * transaction commits, so a multi-mutation reconcile re-emits the observed list exactly once. The fake
 * reproduces that: inside [transaction] the mutations are staged on a working copy and the backing
 * [MutableStateFlow] is published a single time at commit — otherwise the fake would leak intermediate
 * per-[upsert] emissions the real store never produces.
 */
class FakeItemLocalStore(
    initial: Map<String, CachedItem> = emptyMap(),
) : ItemLocalStore {

    private val rows = MutableStateFlow(initial)

    /** True while a [transaction] is staging mutations; suspends per-mutation publishing until commit. */
    private var inTransaction = false
    private var staged: MutableMap<String, CachedItem> = mutableMapOf()

    /** Direct read of the committed backing map (tombstones included) for assertions. */
    val all: Map<String, CachedItem> get() = rows.value

    override fun observeActive(): Flow<List<CachedItem>> = rows.map { snapshot ->
        snapshot.values.active()
    }

    override fun observeActive(kind: ItemKind): Flow<List<CachedItem>> = rows.map { snapshot ->
        snapshot.values.filter { it.kind == kind }.active()
    }

    override fun observe(id: String): Flow<CachedItem?> = rows.map { it[id] }

    override suspend fun allIds(): Set<String> = current().keys

    override suspend fun get(id: String): CachedItem? = current()[id]

    override suspend fun upsert(row: CachedItem) {
        mutate { it[row.id] = row }
    }

    override suspend fun delete(id: String) {
        mutate { it.remove(id) }
    }

    override suspend fun transaction(block: suspend (ItemLocalStore) -> Unit) {
        check(!inTransaction) { "nested transactions are not supported by the fake" }
        inTransaction = true
        staged = rows.value.toMutableMap()
        try {
            block(this)
            rows.value = staged.toMap() // single commit-time emission
        } finally {
            inTransaction = false
        }
    }

    /**
     * The live rows in one `sequence` order across every kind, matching `selectAllActive`. A null
     * `sequence` — a row created offline and not yet acknowledged — sorts first, as it does in SQL.
     */
    private fun Collection<CachedItem>.active(): List<CachedItem> =
        filterNot { it.item.core.isDeleted }.sortedBy { it.item.core.sequence }

    /** The live view a read/mutation sees — the staged copy mid-transaction, else the committed map. */
    private fun current(): Map<String, CachedItem> = if (inTransaction) staged else rows.value

    private fun mutate(edit: (MutableMap<String, CachedItem>) -> Unit) {
        if (inTransaction) {
            edit(staged)
        } else {
            rows.value = rows.value.toMutableMap().also(edit)
        }
    }
}
