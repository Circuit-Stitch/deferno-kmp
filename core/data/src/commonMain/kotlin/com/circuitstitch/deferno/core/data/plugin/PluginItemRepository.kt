package com.circuitstitch.deferno.core.data.plugin

import com.circuitstitch.deferno.core.data.item.ItemLocalStore
import com.circuitstitch.deferno.core.data.occurrence.OccurrenceFactLocalStore
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.plugin.Item
import com.circuitstitch.deferno.core.model.plugin.Occurrence
import com.circuitstitch.deferno.core.model.recipe.Clamp
import com.circuitstitch.deferno.core.model.recipe.KindRecipe
import com.circuitstitch.deferno.core.model.recipe.ParityRecipe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate

/**
 * The plugin-shaped **read** of what this device holds (ADR-0055, #421). It builds two records through
 * a [KindRecipe]: an [Item] definition, and one dated [Occurrence] of it.
 *
 * It reads alongside `core.model.Item` and `RecurringDefinition`, not instead of them. Surfaces move
 * across one at a time in Phase 4. `PluginReadParityTest` asserts the two readings agree.
 *
 * **Nothing writes through it.** A refresh goes through the repository that already owns one, so there
 * is no `hydrate` here. Writes go through `ItemLocalStore`, which is plugin-shaped since #422.
 *
 * **The kind stops here.** Every method takes a raw id, and none takes an `ItemRef`. Which endpoint a
 * row round-trips to is storage bookkeeping until the wire drops kinds (ADR-0056), and the store is
 * where that ends.
 *
 * **Both reads are total.** An absent plugin reads as its family's degenerate value, so no caller
 * handles "absent". A date with nothing on record is an [Occurrence] carrying no `Outcome` — the
 * absence of a row rather than an empty one (ADR-0056, #436) — which keeps the Scheduled-versus-Missed
 * reading derived. [observe] is the one nullable read: this device may genuinely not hold the row.
 */
interface PluginItemRepository {

    /**
     * Every live row this device holds, as one plugin-shaped list. Tombstone-free and `sequence`-ordered,
     * which is the order `OfflineItemRepository.observeItems` also lists, so both readings put the same
     * rows in the same places. Re-emits when the cache changes (ADR-0001).
     */
    fun observeItems(): Flow<List<Item>>

    /**
     * One row by its raw UUID. `null` when this device holds none, which is a normal cold-start answer
     * rather than an error.
     *
     * A tombstone is emitted rather than filtered, and `Core.isDeleted` says so — the store's own
     * contract for a single-row observe.
     */
    fun observe(id: String): Flow<Item?>

    /**
     * What is on record for one dated firing of [itemId]. Never `null`: an [Occurrence] is a key plus
     * facts, the key always exists, and a date with nothing on record carries no plugins. Whether the
     * *item* exists is [observe]'s question.
     */
    fun observeFiring(itemId: String, date: LocalDate): Flow<Occurrence>
}

/**
 * The offline-first [PluginItemRepository] (ADR-0001). The local store is the source of truth, reads are
 * its `Flow`s, and nothing here writes or reaches the network.
 *
 * **This is what the flip left of it.** #421 built the definition reads by fanning out over four
 * per-kind stores and translating each row through the recipe. The cache is plugin-shaped since #422,
 * so the store already hands back the record and both reads are a projection off it — the seam earned
 * its keep by absorbing that change entirely.
 *
 * The firing read still fans out, because `occurrenceFactEntity` is still keyed on the kind. It loses
 * that key when the reading over it stops dispatching on the kind, which is Phase 4.
 *
 * [recipe] is a constructor seam because ADR-0056 puts two recipes behind one interface. The target
 * recipe lands later, one Family at a time, and swaps in here.
 */
class OfflinePluginItemRepository(
    private val items: ItemLocalStore,
    private val facts: OccurrenceFactLocalStore,
    private val recipe: KindRecipe = ParityRecipe,
) : PluginItemRepository {

    override fun observeItems(): Flow<List<Item>> =
        items.observeActive().map { rows -> rows.map { it.item } }

    override fun observe(id: String): Flow<Item?> = items.observe(id).map { it?.item }

    override fun observeFiring(itemId: String, date: LocalDate): Flow<Occurrence> =
        combine(FIRING_KINDS.map { facts.observe(it, itemId, date) }) { rows ->
            rows.firstNotNullOfOrNull { it }
                ?.let { recipe.read(it) }
                // Nothing on record is an Occurrence carrying nothing, not an absent one. It is what
                // the derived Scheduled-versus-Missed reading is computed from. See the interface KDoc.
                ?: Occurrence(itemId = itemId, date = date)
        }

    private companion object {

        /**
         * The kinds whose firings a server row can hold, read off [Clamp.storedResolutions] rather
         * than written out as the three recurring kinds. A Task stores none today, and ADR-0055
         * expects that to change, so deriving the fan-out keeps it to one edit.
         */
        val FIRING_KINDS: List<ItemKind> =
            ItemKind.entries.filter { Clamp.storedResolutions(it).isNotEmpty() }
    }
}
