package com.circuitstitch.deferno.core.data.item

import com.circuitstitch.deferno.core.data.chore.ChoreLocalStore
import com.circuitstitch.deferno.core.data.event.EventLocalStore
import com.circuitstitch.deferno.core.data.habit.HabitLocalStore
import com.circuitstitch.deferno.core.data.task.TaskLocalStore
import com.circuitstitch.deferno.core.model.Chore
import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.Event
import com.circuitstitch.deferno.core.model.Habit
import com.circuitstitch.deferno.core.model.Item
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.Task
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * The unified, cross-kind **read** of the Item store (ADR-0034, #226) — the read half that completes
 * the Task→Item generalization. The four kinds persist in four per-kind stores, each independently
 * observable; this merges them into one [Item] list so the Tasks [Item tree] (#227) can render the
 * whole catalog as a single `parent_id` forest. The sync/write side stays in [ItemSync].
 */
interface ItemRepository {

    /**
     * The whole windowed Item set across all four kinds, as one list. Each kind's store already
     * excludes tombstones and orders by `sequence`; this just concatenates them — the consumer (the
     * tree) builds the cross-kind `parent_id` forest and orders siblings. Re-emits whenever any kind's
     * store changes (offline-first: reads are local `Flow`s, ADR-0001).
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
 * Offline-first [ItemRepository] (ADR-0001): the four per-kind local stores are the source of truth,
 * reads are their `Flow`s, and [refresh] only ever writes through them via [ItemSync].
 */
class OfflineItemRepository(
    private val taskStore: TaskLocalStore,
    private val habitStore: HabitLocalStore,
    private val choreStore: ChoreLocalStore,
    private val eventStore: EventLocalStore,
    private val itemSync: ItemSync,
) : ItemRepository {

    override fun observeItems(): Flow<List<Item>> =
        combine(
            taskStore.observeActive(),
            habitStore.observeActive(),
            choreStore.observeActive(),
            eventStore.observeActive(),
        ) { tasks, habits, chores, events ->
            buildList(tasks.size + habits.size + chores.size + events.size) {
                tasks.forEach { add(it.toItem()) }
                habits.forEach { add(it.toItem()) }
                chores.forEach { add(it.toItem()) }
                events.forEach { add(it.toItem()) }
            }
        }

    override suspend fun refresh() = itemSync.refresh()
}

// --- kind -> Item projection. parentId/id unwrap the wire UUID to the string the forest compares on. ---

private fun Task.toItem() = Item(
    id = id.value,
    kind = ItemKind.Task,
    title = title,
    parentId = parentId?.value,
    sequence = sequence,
    isTerminal = workingState.isTerminal,
    descendantDone = descendantDone,
    descendantTotal = descendantTotal,
)

// ponytail: a recurring definition is "terminal" (de-emphasized) when Archived — the recurring analog
// of a Done/Dropped Task. Recurring kinds carry no subtree counts (the /items snapshot computes them on
// Tasks only), so the badge fields stay null.
private fun Habit.toItem() = recurringItem(id.value, ItemKind.Habit, title, parentId?.value, sequence, definitionState)
private fun Chore.toItem() = recurringItem(id.value, ItemKind.Chore, title, parentId?.value, sequence, definitionState)
private fun Event.toItem() = recurringItem(id.value, ItemKind.Event, title, parentId?.value, sequence, definitionState)

private fun recurringItem(id: String, kind: ItemKind, title: String, parentId: String?, sequence: Long?, state: DefinitionState) =
    Item(id = id, kind = kind, title = title, parentId = parentId, sequence = sequence, isTerminal = state == DefinitionState.Archived)
