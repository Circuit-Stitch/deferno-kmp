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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * Observable state for the Tasks Item-tree pane. The View renders the flattened [rows] and holds no logic.
 * [moveMode] is non-null while an item is lifted in modal move mode (ADR-0034 decision 6, #228) — the View
 * highlights the lifted row, calms the rest, and greys out the illegal directions.
 */
data class ItemTreeState(
    val rows: List<ItemRow> = emptyList(),
    val isRefreshing: Boolean = false,
    val moveMode: MoveMode? = null,
)

/**
 * The lifted item + which of the four relative moves are legal right now (ADR-0034 #228). The booleans
 * mirror [MoveOptions]'s non-null arms — the client-side "illegal targets greyed out" guard the View renders
 * onto the **↑ ↓ ‹ ›** controls (and the keyboard ignores a disabled direction).
 */
data class MoveMode(
    val liftedId: String,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
    val canIndent: Boolean,
    val canOutdent: Boolean,
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

    // --- Modal move mode (ADR-0034 decision 6, #228): a single lifted item moved live, one press at a time ---

    /** Enter move mode with [id] lifted — the long-press "Move" entry (or a keyboard shortcut). */
    fun onEnterMoveMode(id: String)

    /** Leave move mode — the "Done" affordance. */
    fun onExitMoveMode()

    /** Reorder the lifted item up among its siblings (↑ / Alt+↑). A no-op when illegal ([MoveMode.canMoveUp]). */
    fun onMoveUp()

    /** Reorder the lifted item down among its siblings (↓ / Alt+↓). A no-op when illegal. */
    fun onMoveDown()

    /** Indent the lifted item — nest under its preceding sibling (› / Tab). A no-op when illegal. */
    fun onIndent()

    /** Outdent the lifted item — hop out to its parent's level (‹ / Shift-Tab). A no-op when illegal. */
    fun onOutdent()

    sealed interface Output {
        data class ItemSelected(val id: TaskId) : Output
    }
}

class DefaultItemTreeComponent(
    componentContext: ComponentContext,
    private val itemRepository: ItemRepository,
    private val foldStore: ItemFoldStore,
    private val output: (ItemTreeComponent.Output) -> Unit,
    // The cross-kind move write seam (#228), threaded from the shell over CommandExecutor. Defaults to a
    // no-op so the read/fold/navigation-only tests construct the component without supplying it.
    private val moveEditor: MoveEditor = MoveEditor.NONE,
    coroutineContext: CoroutineContext = Dispatchers.Default,
) : ItemTreeComponent, ComponentContext by componentContext {

    private val scope = componentScope(coroutineContext)
    private val refreshing = MutableStateFlow(false)

    // Which item is lifted in modal move mode, or null when not in move mode (#228).
    private val liftedId = MutableStateFlow<String?>(null)

    override val state: StateFlow<ItemTreeState> =
        combine(itemRepository.observeItems(), foldStore.overrides, refreshing, liftedId) { items, ov, isRefreshing, lifted ->
            ItemTreeState(
                rows = buildItemTree(items, ov),
                isRefreshing = isRefreshing,
                // Re-derive the legal directions whenever the tree changes — an applied move re-emits items,
                // so the ↑↓‹› greying tracks the lifted item's new position live, with no extra state.
                moveMode = lifted?.let { id ->
                    val options = moveOptions(items, id)
                    MoveMode(
                        liftedId = id,
                        canMoveUp = options.up != null,
                        canMoveDown = options.down != null,
                        canIndent = options.indent != null,
                        canOutdent = options.outdent != null,
                    )
                },
            )
        }.stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ItemTreeState())

    // Persisting through the shared store re-flattens this tree AND the detail subtask outline live —
    // both combine the one [ItemFoldStore.overrides] flow (ADR-0034 decision 4).
    override fun onToggleExpand(id: String, currentlyExpanded: Boolean) {
        foldStore.setOverride(id, !currentlyExpanded)
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

    override fun onEnterMoveMode(id: String) {
        liftedId.value = id
    }

    override fun onExitMoveMode() {
        liftedId.value = null
    }

    override fun onMoveUp() = applyMove { it.up }

    override fun onMoveDown() = applyMove { it.down }

    override fun onIndent() = applyMove { it.indent }

    override fun onOutdent() = applyMove { it.outdent }

    /**
     * Issues the [select]ed relative move for the lifted item as one independently-undoable Move (#228):
     * recomputes [moveOptions] over the current cache so the target matches what the View greyed, then
     * dispatches through [moveEditor]. A no-op when not in move mode or when that direction is illegal —
     * the item stays lifted so the user can keep moving it ("live per press").
     */
    private fun applyMove(select: (MoveOptions) -> MoveTarget?) {
        val id = liftedId.value ?: return
        scope.launch {
            val target = select(moveOptions(itemRepository.observeItems().first(), id)) ?: return@launch
            moveEditor.move(id, target.newParentId, target.position)
        }
    }
}

// Keep the upstream alive briefly across config changes / brief detachment, then stop to save work.
internal const val STOP_TIMEOUT_MS = 5_000L
