package com.circuitstitch.deferno.core.data.item

import com.circuitstitch.deferno.core.model.Chore
import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.Event
import com.circuitstitch.deferno.core.model.Habit
import com.circuitstitch.deferno.core.model.Item
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.Recurrence
import com.circuitstitch.deferno.core.model.SeriesInputs
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.recipe.KindRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Instant

/**
 * The unified, cross-kind **read** of the Item store (ADR-0049, #226) — the read half that completes
 * the Task→Item generalization. It projects the cache into one [Item] list so the Tasks [Item tree]
 * (#227) can render the whole catalog as a single `parent_id` forest. The sync and write side stays in
 * [ItemSync].
 *
 * It merged four independently-observable per-kind stores until #422 flipped the cache onto plugins.
 * There is one store now, so the merge is a projection.
 */
interface ItemRepository {

    /**
     * The whole windowed Item set, as one list. The store already excludes tombstones and orders by
     * `sequence`; the consumer (the tree) builds the `parent_id` forest and orders siblings. Re-emits
     * whenever the cache changes (offline-first: reads are local `Flow`s, ADR-0001).
     */
    fun observeItems(): Flow<List<Item>>

    /**
     * Trigger the cross-kind `GET /items` cold sync (delegates to [ItemSync]). Offline-first — an
     * unreachable server leaves every cache intact; the server-windowed snapshot honors the
     * done-visibility window with no client-side math.
     */
    suspend fun refresh()
}

/**
 * Offline-first [ItemRepository] (ADR-0001): the local item store is the source of truth, reads are its
 * `Flow`s, and [refresh] only ever writes through it via [ItemSync].
 */
class OfflineItemRepository(
    private val store: ItemLocalStore,
    private val itemSync: ItemSync,
) : ItemRepository {

    override fun observeItems(): Flow<List<Item>> =
        store.observeActive().map { rows -> rows.map { it.toItem() } }

    override suspend fun refresh() = itemSync.refresh()
}

/**
 * A cached row as the shipped cross-kind projection.
 *
 * It goes through the recipe's write direction rather than being built from the plugin set directly,
 * and that is this phase's line. Both are possible — #421's sufficiency gate proved the plugin read
 * carries everything both shipped projections need — but one field is not yet separable: a Habit's
 * [[Recurrence cursor]] and a Task's deadline are the same `Anchor.Deadline` with no field between
 * them, so only the kind tells them apart (#439). Reading through the kind row keeps that distinction
 * exactly as sharp as it is today. Building the projection from plugins alone is Phase 4's, after #439
 * puts the cursor on `Repeats`.
 */
internal fun CachedItem.toItem(): Item = when (val row = asKindRow()) {
    is KindRow.OfTask -> row.task.toItem()
    is KindRow.OfHabit -> row.habit.toItem()
    is KindRow.OfChore -> row.chore.toItem()
    is KindRow.OfEvent -> row.event.toItem()
}

// --- kind -> Item projection. parentId/id unwrap the wire UUID to the string the forest compares on. ---
//
// `internal`, not private: this is the ONE kind -> Item projection in the module. The daily Plan
// resolves its ordering over the same store (#385) and needs the identical mapping, so it consumes
// these rather than growing a second copy that would drift — the same reasoning that gave
// `RecurrenceReading` a single home.

internal fun Task.toItem() = Item(
    id = id.value,
    kind = ItemKind.Task,
    title = title,
    parentId = parentId?.value,
    sequence = sequence,
    isTerminal = workingState.isTerminal,
    descendantDone = descendantDone,
    descendantTotal = descendantTotal,
    blocked = blocked,
    isBlocker = isBlocker,
    // The ordered edge list itself (#291): the tree's "Blocked by…" picker + dependents scan read it.
    blockedBy = blockedBy,
    // External provenance for the tree-row mark + `[GitHub#N]` ref prefix; null = native item.
    source = external?.source,
    externalRef = external?.id,
)

// ponytail: a recurring definition is "terminal" (de-emphasized) when Archived — the recurring analog
// of a Done/Dropped Task. Recurring kinds carry no subtree counts (the /items snapshot computes them on
// Tasks only), so the badge fields stay null. The blocked/isBlocker flags are server-derived per item
// (a recurring row inherits `blocked` from a blocked ancestor), so they project through unchanged (#289).
// All three recurring kinds also carry the recurrence PAIR (#384) — the rule and its cursor — because a
// row cannot read the series without both: on a definition `completeBy` is the moving cursor (backend
// ADR `2026-06-02-recurrence-anchor-and-bound`), so a cleared cursor means "exhausted" only when a rule
// is there to say this is a series at all. It lands on the projection as `recurrenceCursorAt`, not
// `completeBy`, precisely because the Task arm above deliberately projects NEITHER: a Task's `completeBy`
// is a plain deadline, and forwarding it here would make every dated Task read as a series.
// The pair became a TRIPLE at #410: the rule says how often, the cursor says how far along, and only
// `series` says which wall times. Projecting it here is what lets a tree row — and the Plan, which
// consumes this same mapping — hand `expandOccurrenceGrid` real inputs and get firing dates back with
// no detail fetch and no network. The wire ships the block on every `/items` row precisely so a client
// can do that; a detail-only projection would have left the tree unable to reach a grid at all.
internal fun Habit.toItem() = recurringItem(id.value, ItemKind.Habit, title, parentId?.value, sequence, definitionState, blocked, isBlocker, recurrence, completeBy, seriesId, series)
internal fun Chore.toItem() = recurringItem(id.value, ItemKind.Chore, title, parentId?.value, sequence, definitionState, blocked, isBlocker, recurrence, completeBy, seriesId, series)
internal fun Event.toItem() = recurringItem(id.value, ItemKind.Event, title, parentId?.value, sequence, definitionState, blocked, isBlocker, recurrence, completeBy, seriesId, series)

private fun recurringItem(
    id: String,
    kind: ItemKind,
    title: String,
    parentId: String?,
    sequence: Long?,
    state: DefinitionState,
    blocked: Boolean,
    isBlocker: Boolean,
    recurrence: Recurrence?,
    recurrenceCursorAt: Instant?,
    seriesId: String?,
    series: SeriesInputs?,
) = Item(
    id = id,
    kind = kind,
    title = title,
    parentId = parentId,
    sequence = sequence,
    isTerminal = state == DefinitionState.Archived,
    blocked = blocked,
    isBlocker = isBlocker,
    // Carry the full "light switch" through (#299) so the tree's command menu can set it — Archived stays
    // the de-emphasis signal ([isTerminal]) AND the value is here for Archive/Restore. Null for a Task above.
    definitionState = state,
    // The rule + its cursor, verbatim (#384). Deliberately NOT condensed into a reading here: the
    // reading is relative to *today*, and this Flow re-emits on a DB write, not on a clock tick — see
    // [recurrenceCursor]. Carry the facts; let the View derive the phrase.
    recurrence = recurrence,
    recurrenceCursorAt = recurrenceCursorAt,
    // The expansion inputs, verbatim and uncondensed for the same reason: an [[Occurrence grid]] is
    // computed for a *window*, and this Flow does not know which one the caller wants.
    seriesId = seriesId,
    series = series,
)
