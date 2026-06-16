package com.circuitstitch.deferno.feature.tasks

import com.arkivanov.decompose.ComponentContext
import com.circuitstitch.deferno.core.common.componentScope
import com.circuitstitch.deferno.core.data.item.ItemFoldStore
import com.circuitstitch.deferno.core.data.item.ItemRepository
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.TaskId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/** Observable state for the Tasks Item-tree pane. The View renders the flattened [rows] and holds no logic. */
data class ItemTreeState(
    val rows: List<ItemRow> = emptyList(),
    val isRefreshing: Boolean = false,
)

/**
 * The Tasks Destination as the nested, collapsible **Item tree** (ADR-0034, #227) — the cross-kind forest
 * the old flat list + one-level drill pane are subsumed into. It observes the windowed `/items` set across
 * all four kinds ([ItemRepository.observeItems]) and the device-local fold overrides ([ItemFoldStore]),
 * and exposes the flattened, depth-indented [ItemRow]s a single `LazyColumn` renders.
 *
 * **Per-row interaction (ADR-0034 decision 7).** The leading chevron *and* a body tap toggle a parent's
 * fold ([onToggleExpand]); a childless leaf's body is inert. The trailing `›` opens detail ([onOpenDetail]).
 * Navigation is intent-driven: [onOpenDetail] emits an [Output.ItemSelected]; the parent owns the slots.
 */
interface ItemTreeComponent {
    val state: StateFlow<ItemTreeState>

    /**
     * Toggle a parent row's expand/collapse — the leading chevron or a body tap. [currentlyExpanded] is
     * the row's present fold (the View has it on the [ItemRow]); this persists the flipped value as an
     * explicit override (device-local, shared with the detail subtask outline). The View calls this only
     * for a parent row — a childless leaf's body is inert (ADR-0034 decision 7).
     */
    fun onToggleExpand(id: String, currentlyExpanded: Boolean)

    /**
     * Open a row's detail — the trailing `›`. Only **Task** rows have a detail surface today (ADR-0034);
     * a tap on another [kind] is a no-op until per-kind detail lands (a deferred fast-follow).
     */
    fun onOpenDetail(id: String, kind: ItemKind)

    /** Trigger an explicit network pull of the `/items` cold snapshot (offline-first: reads stay local). */
    fun onRefresh()

    sealed interface Output {
        data class ItemSelected(val id: TaskId) : Output
    }
}

class DefaultItemTreeComponent(
    componentContext: ComponentContext,
    private val itemRepository: ItemRepository,
    private val foldStore: ItemFoldStore,
    private val output: (ItemTreeComponent.Output) -> Unit,
    coroutineContext: CoroutineContext = Dispatchers.Default,
) : ItemTreeComponent, ComponentContext by componentContext {

    private val scope = componentScope(coroutineContext)
    private val refreshing = MutableStateFlow(false)
    // The live fold overrides: seeded once from the persisted store, then updated on each toggle (and
    // written through to the store). Recombining into [state] re-flattens the forest with the new fold.
    private val overrides = MutableStateFlow(foldStore.allOverrides())

    override val state: StateFlow<ItemTreeState> =
        combine(itemRepository.observeItems(), overrides, refreshing) { items, ov, isRefreshing ->
            ItemTreeState(rows = buildItemTree(items, ov), isRefreshing = isRefreshing)
        }.stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ItemTreeState())

    override fun onToggleExpand(id: String, currentlyExpanded: Boolean) {
        val expanded = !currentlyExpanded
        foldStore.setOverride(id, expanded)
        overrides.update { it + (id to expanded) }
    }

    override fun onOpenDetail(id: String, kind: ItemKind) {
        if (kind == ItemKind.Task) output(ItemTreeComponent.Output.ItemSelected(TaskId(id)))
    }

    override fun onRefresh() {
        scope.launch {
            refreshing.value = true
            try {
                itemRepository.refresh()
            } finally {
                refreshing.value = false
            }
        }
    }
}

// Keep the upstream alive briefly across config changes / brief detachment, then stop to save work.
internal const val STOP_TIMEOUT_MS = 5_000L
