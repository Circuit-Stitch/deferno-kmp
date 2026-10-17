package com.circuitstitch.deferno.core.data.item

import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.plugin.Item
import com.circuitstitch.deferno.core.model.recipe.KindRecipe
import com.circuitstitch.deferno.core.model.recipe.KindRow
import com.circuitstitch.deferno.core.model.recipe.ParityRecipe
import kotlinx.coroutines.flow.Flow

/**
 * One cached row: the plugin-shaped record, plus which of the four endpoints it round-trips to.
 *
 * **The kind is sync bookkeeping and not part of the record.** ADR-0055 says the client domain model
 * names no kinds, and [item] holds none. The wire still speaks four of them (ADR-0056), so the cache
 * has to remember which one a row came from, in the same way it remembers a hydration state. This pair
 * is where that ends: `PluginItemRepository` projects out [item] and never looks at [kind].
 *
 * It is a stored column rather than a derivation, because a plugin set does not determine a kind. A
 * Task and a Habit both load `Anchor.Deadline`. A Habit and a Chore differ only by a `cadenceMode` a
 * Habit never carries. An Item with no plugins answers to every kind at once.
 */
data class CachedItem(val item: Item, val kind: ItemKind) {

    /** The row's stable UUID — the reconcile key, and what every method here takes. */
    val id: String get() = item.core.id
}

/**
 * This row as the wire row it round-trips to, for the mappers and the exporter that still need one
 * (ADR-0056).
 *
 * It is recomputed rather than carried beside [CachedItem.item], which would be the second writable
 * representation ADR-0055 rejects. Recomputing is safe because it is exactly the direction the
 * round-trip gate proves is an identity, and it is a pure function over data already in memory.
 */
internal fun CachedItem.asKindRow(recipe: KindRecipe = ParityRecipe): KindRow = recipe.write(item, kind)

/** This row as a [Task], or `null` when it is not one — for a surface that reads Task-only fields. */
internal fun CachedItem.asTaskOrNull(recipe: KindRecipe = ParityRecipe): Task? =
    if (kind == ItemKind.Task) recipe.writeTask(item) else null

/**
 * The local source-of-truth port for items (ADR-0001, ADR-0055, #422). It replaces `TaskLocalStore`,
 * `HabitLocalStore`, `ChoreLocalStore` and `EventLocalStore`, which held one table each and one copy
 * each of the same reconcile contract.
 *
 * The repository talks to this, never the network. UI-facing reads are [observeActive] and [observe]
 * database `Flow`s, and a refresh reconciles through the suspend mutators. Extracting the persistence
 * behind a port keeps the reconcile and hydration *algorithm* pure and unit-testable against an
 * in-memory fake on the JVM-fast path (ADR-0006), while [SqlDelightItemLocalStore] proves the real
 * SQLite path in its own integration test.
 *
 * **Every method takes a raw id, never a kind-typed one.** Which endpoint a row belongs to is answered
 * by the [CachedItem] the store hands back, not asked of the caller. That is the seam #421 established
 * on the read side and this phase extends to writes.
 *
 * **The series expansion inputs ride along.** They live in their own tables because they are unbounded
 * lists, and the store stitches them onto every read and replaces them alongside every write, in one
 * transaction. A row that leaves this store therefore always carries whatever grid its cache can
 * reproduce.
 */
interface ItemLocalStore {

    /**
     * The live (non-tombstoned) rows in `sequence` order — the UI-facing list (ADR-0001
     * observe-via-Flow-only). Re-emits whenever the cache changes.
     *
     * One order across every kind, where four stores each ordered their own rows and the reader
     * concatenated them. `sequence` is unique per org across kinds, so this is the coherent order the
     * concatenation was approximating. Nothing rendered depended on the old one: the item tree sorts
     * siblings itself and the daily plan takes its order from the plan rows.
     */
    fun observeActive(): Flow<List<CachedItem>>

    /** The same list narrowed to one kind, filtered in SQL — for a caller that genuinely wants one. */
    fun observeActive(kind: ItemKind): Flow<List<CachedItem>>

    /**
     * The single row by [id], or `null` when this device holds none — the detail-screen stream that
     * re-emits when a hydrate upgrades the row from summary to full.
     *
     * A tombstoned row is emitted rather than filtered. It is not absent, and the detail screen decides
     * how to render a server-deleted item.
     */
    fun observe(id: String): Flow<CachedItem?>

    /** Every cached id, including tombstones — the reconcile diffs this against a fresh snapshot. */
    suspend fun allIds(): Set<String>

    /** The current row for [id] (tombstone included), or `null` — read before an upsert to merge. */
    suspend fun get(id: String): CachedItem?

    /** Inserts or replaces [row] by its id. */
    suspend fun upsert(row: CachedItem)

    /** Hard-deletes the row [id] — the reconcile's "absent from the snapshot" purge. */
    suspend fun delete(id: String)

    /**
     * Runs [block] as one atomic unit, so a full-snapshot reconcile — a batch of upserts and deletes —
     * commits or rolls back together and never leaves the cache half-reconciled (ADR-0001). The [block]
     * receives the same store to issue its mutations through.
     */
    suspend fun transaction(block: suspend (ItemLocalStore) -> Unit)
}
