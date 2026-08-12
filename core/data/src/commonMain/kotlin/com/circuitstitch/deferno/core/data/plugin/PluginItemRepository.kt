package com.circuitstitch.deferno.core.data.plugin

import com.circuitstitch.deferno.core.data.chore.ChoreLocalStore
import com.circuitstitch.deferno.core.data.event.EventLocalStore
import com.circuitstitch.deferno.core.data.habit.HabitLocalStore
import com.circuitstitch.deferno.core.data.occurrence.OccurrenceFactLocalStore
import com.circuitstitch.deferno.core.data.task.TaskLocalStore
import com.circuitstitch.deferno.core.model.ChoreId
import com.circuitstitch.deferno.core.model.EventId
import com.circuitstitch.deferno.core.model.HabitId
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.plugin.Item
import com.circuitstitch.deferno.core.model.plugin.Occurrence
import com.circuitstitch.deferno.core.model.recipe.Clamp
import com.circuitstitch.deferno.core.model.recipe.KindRecipe
import com.circuitstitch.deferno.core.model.recipe.ParityRecipe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.datetime.LocalDate

/**
 * The plugin-shaped **read** of what this device holds (ADR-0055, #421) — an [Item] definition and one
 * dated [Occurrence] of it, built from the local stores through a [KindRecipe].
 *
 * It reads **alongside** `core.model.Item` and `RecurringDefinition` rather than replacing either.
 * Those two are the shipped projections and stay exactly as they are; a surface moves across one at a
 * time in Phase 4, and until then both readings are available over the same cached rows. That they
 * *agree* is asserted rather than reviewed: `PluginReadParityTest` reconstructs both projections from
 * this read over a corpus spanning all four kinds.
 *
 * **Nothing writes through it, deliberately.** The four per-kind tables do not move here — reshaping
 * local storage while the wire is still four-kind buys nothing and costs a schema version, so the
 * drop-and-recreate is Phase 3's, where it is actually needed (ADR-0055). There is no `hydrate` for the
 * same reason: a refresh goes through the repository that already owns one, and this seam reads
 * whatever that left behind.
 *
 * ## This is where the kind stops
 *
 * Every method takes a raw id and none takes an `ItemRef`. Which of the four tables holds a row is
 * storage bookkeeping for as long as the wire stays four-kind (ADR-0056), so it is resolved *inside*
 * this seam rather than by every caller — which is the whole point of a facade whose output names no
 * kind. It costs a fan-out over four stores per read; SQLite indexes each by primary key, and the
 * alternative is pushing a `when (kind)` back out into the code the re-cut exists to delete.
 *
 * ## Both reads are total, on the model's own terms
 *
 * An absent plugin reads as its family's degenerate value, so no caller handles "absent". A date with
 * nothing on record is an [Occurrence] carrying no `Outcome` — **the absence of a row rather than an
 * empty one**, the property the ADR-0056 #436 amendment pins — so [observeFiring] is non-null and the
 * Scheduled-versus-Missed reading over it stays derived at render time. [observe] is the one nullable
 * read, because "this device does not hold that row" is a real answer and a normal cold-start one.
 */
interface PluginItemRepository {

    /**
     * Every live row this device holds, across all four kinds, as one plugin-shaped list — the
     * cross-kind read `OfflineItemRepository.observeItems` already performs, in the model ADR-0055
     * moves to. Each kind's store already excludes tombstones and orders by `sequence`; this
     * concatenates them in the same order, so a caller reading both projections sees the same rows in
     * the same places. Re-emits whenever any kind's store changes (ADR-0001 observe-via-Flow).
     */
    fun observeItems(): Flow<List<Item>>

    /**
     * One row by its raw UUID, whichever of the four tables holds it, or `null` when this device holds
     * none. Cold start is the common way to get `null`, and it is not an error.
     *
     * Unlike [observeItems] a **tombstone is emitted** rather than filtered — it is not absent, and
     * `Core.isDeleted` is what says so. That is the per-kind stores' own contract for a single-row
     * observe, carried through unchanged.
     */
    fun observe(id: String): Flow<Item?>

    /**
     * What is on record for one dated firing of [itemId], as the plugin-shaped record that owns it.
     *
     * Total: a date with nothing on record reads as an [Occurrence] with no plugins, never `null`. The
     * key always exists — an [Occurrence] is a key plus facts — and whether the *item* exists is
     * [observe]'s question, not this one's.
     */
    fun observeFiring(itemId: String, date: LocalDate): Flow<Occurrence>
}

/**
 * The offline-first [PluginItemRepository] (ADR-0001): the local stores are the source of truth, reads
 * are their `Flow`s, and nothing here reaches the network or writes anything.
 *
 * [recipe] is a seam rather than a hard-wired `ParityRecipe` because ADR-0056 puts two recipes behind
 * one interface: the parity recipe reproduces today's behaviour exactly and is what the migration is
 * gated on, and the target recipe lands later, one Family at a time. This constructor parameter is
 * where the second one gets swapped in — which is the reason `KindRecipe` is an interface at all
 * rather than the parity functions being top-level.
 */
class OfflinePluginItemRepository(
    private val tasks: TaskLocalStore,
    private val habits: HabitLocalStore,
    private val chores: ChoreLocalStore,
    private val events: EventLocalStore,
    private val facts: OccurrenceFactLocalStore,
    private val recipe: KindRecipe = ParityRecipe,
) : PluginItemRepository {

    override fun observeItems(): Flow<List<Item>> =
        combine(
            tasks.observeActive(),
            habits.observeActive(),
            chores.observeActive(),
            events.observeActive(),
        ) { taskRows, habitRows, choreRows, eventRows ->
            buildList(taskRows.size + habitRows.size + choreRows.size + eventRows.size) {
                taskRows.forEach { add(recipe.read(it)) }
                habitRows.forEach { add(recipe.read(it)) }
                choreRows.forEach { add(recipe.read(it)) }
                eventRows.forEach { add(recipe.read(it)) }
            }
        }

    override fun observe(id: String): Flow<Item?> =
        combine(
            tasks.observe(TaskId(id)),
            habits.observe(HabitId(id)),
            chores.observe(ChoreId(id)),
            events.observe(EventId(id)),
        ) { task, habit, chore, event ->
            // At most one arm can be non-null: the four tables partition one UUID space, because the id
            // is the server's and a row moves between kinds by being rewritten under the same id. First
            // non-null rather than a uniqueness assertion — a facade that threw on a cache the reconcile
            // left inconsistent would take out the read path for a row it could still render.
            when {
                task != null -> recipe.read(task)
                habit != null -> recipe.read(habit)
                chore != null -> recipe.read(chore)
                event != null -> recipe.read(event)
                else -> null
            }
        }

    override fun observeFiring(itemId: String, date: LocalDate): Flow<Occurrence> =
        combine(FIRING_KINDS.map { facts.observe(it, itemId, date) }) { rows ->
            rows.firstNotNullOfOrNull { it }
                ?.let { recipe.read(it) }
                // No fact is the honest record for a date nothing has happened on, and it is what the
                // derived Scheduled-versus-Missed reading is computed from — so it is an Occurrence
                // carrying nothing, not an absent one. See the interface KDoc.
                ?: Occurrence(itemId = itemId, date = date)
        }

    private companion object {

        /**
         * The kinds whose firings a server row can hold at all — read off [Clamp.storedResolutions]
         * rather than written out as the three recurring kinds.
         *
         * A Task stores no firings today, and `Clamp` records that as a decision rather than an
         * omission, since ADR-0055 keys an `Occurrence` on `itemId + date` with no kind and expects
         * Tasks to grow them. Deriving the fan-out from the clamp keeps that one edit in one place;
         * hard-coding three kinds here would make it two, and the second would be silent.
         */
        val FIRING_KINDS: List<ItemKind> =
            ItemKind.entries.filter { Clamp.storedResolutions(it).isNotEmpty() }
    }
}
