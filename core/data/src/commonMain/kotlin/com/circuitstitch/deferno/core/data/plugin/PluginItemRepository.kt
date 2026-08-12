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
 * The plugin-shaped **read** of what this device holds (ADR-0055, #421). It builds two records through
 * a [KindRecipe]: an [Item] definition, and one dated [Occurrence] of it.
 *
 * It reads alongside `core.model.Item` and `RecurringDefinition`, not instead of them. Surfaces move
 * across one at a time in Phase 4. `PluginReadParityTest` asserts the two readings agree.
 *
 * **Nothing writes through it, and no table moves.** The drop-and-recreate is Phase 3's (ADR-0055),
 * and a refresh goes through the repository that already owns one, so there is no `hydrate` here.
 *
 * **The kind stops here.** Every method takes a raw id, and none takes an `ItemRef`. Which of the four
 * tables holds a row is storage bookkeeping until the wire drops kinds (ADR-0056), so this seam
 * resolves it rather than every caller. The cost is a fan-out over four primary-key reads.
 *
 * **Both reads are total.** An absent plugin reads as its family's degenerate value, so no caller
 * handles "absent". A date with nothing on record is an [Occurrence] carrying no `Outcome` — the
 * absence of a row rather than an empty one (ADR-0056, #436) — which keeps the Scheduled-versus-Missed
 * reading derived. [observe] is the one nullable read: this device may genuinely not hold the row.
 */
interface PluginItemRepository {

    /**
     * Every live row this device holds, across all four kinds, as one plugin-shaped list. Tombstone-free
     * and `sequence`-ordered within each kind, concatenated in the order
     * `OfflineItemRepository.observeItems` uses, so both readings list the same rows in the same
     * places. Re-emits when any kind's store changes (ADR-0001).
     */
    fun observeItems(): Flow<List<Item>>

    /**
     * One row by its raw UUID, from whichever of the four tables holds it. `null` when this device
     * holds none, which is a normal cold-start answer rather than an error.
     *
     * A tombstone is emitted rather than filtered, and `Core.isDeleted` says so — the per-kind stores'
     * own contract for a single-row observe.
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
 * The offline-first [PluginItemRepository] (ADR-0001). The local stores are the source of truth, reads
 * are their `Flow`s, and nothing here writes or reaches the network.
 *
 * [recipe] is a constructor seam because ADR-0056 puts two recipes behind one interface. The target
 * recipe lands later, one Family at a time, and swaps in here.
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
            // At most one arm can be non-null: the four tables partition one UUID space, and a row
            // changing kind is rewritten under the same server id. First-non-null rather than an
            // assertion, because throwing on an inconsistent cache would lose a renderable row.
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
