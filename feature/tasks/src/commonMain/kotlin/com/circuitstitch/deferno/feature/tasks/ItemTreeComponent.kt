package com.circuitstitch.deferno.feature.tasks

import com.arkivanov.decompose.ComponentContext
import com.circuitstitch.deferno.core.common.componentScope
import com.circuitstitch.deferno.core.common.log.Logger
import com.circuitstitch.deferno.core.data.item.InMemoryShakeToUndoPreference
import com.circuitstitch.deferno.core.data.item.ItemFoldStore
import com.circuitstitch.deferno.core.data.item.ItemRepository
import com.circuitstitch.deferno.core.data.item.ShakeToUndoPreference
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
    // The last undoable Move, or null when nothing is undoable (ADR-0034 decision 8, #230). Drives the
    // top "Moved · Undo" snackbar (only when [MoveUndo.structural]) and gates the menu's Undo entry.
    val lastMove: MoveUndo? = null,
    // The readiness axis (#290): false (the resting default — ready-only) prunes `blocked` items and their
    // subtrees from [rows]; the View's "show blocked" affordance flips it true to reveal them (still marked).
    val showBlocked: Boolean = false,
)

/**
 * The View's projection of the [LastUndoable] register (ADR-0034 decision 8, #230). [structural] gates the
 * top **"Moved · Undo"** snackbar — shown on reparent / indent / outdent, **not** a plain same-level reorder;
 * [operation] feeds the shake confirm ("Undo [operation]?"); [id] is a monotonic token that re-keys the
 * single-shot snackbar effect across successive moves (two indents in a row still each raise the snackbar).
 */
data class MoveUndo(val id: Int, val operation: String, val structural: Boolean)

/**
 * What a device shake should do (ADR-0034 decision 8, #230): raise the [Confirm] prompt for [operation]
 * ("Undo [operation]?"), or do [Nothing] — the toggle is off, or nothing is undoable (the latter having
 * already emitted its tracking event). The View renders the confirm and, on accept, calls [ItemTreeComponent.undoLastMove].
 */
sealed interface ShakeOutcome {
    data class Confirm(val operation: String) : ShakeOutcome
    data object Nothing : ShakeOutcome
}

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

    /**
     * Flip the readiness axis (#290) — reveal ([show] = true) or re-hide `blocked` items. Off by default
     * (ready-only): a blocked parent hides its whole subtree at the flatten point. The View's "show blocked"
     * affordance calls this; the revealed blocked rows render their distinct blocked marking.
     */
    fun onSetShowBlocked(show: Boolean)

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

    // --- Undo (ADR-0034 decision 8, #230): single-level Move undo, three interchangeable triggers ---

    /**
     * Revert the last Move (single-level), via the **same command path** ([MoveEditor]). The top snackbar's
     * Undo action, the long-press menu's Undo entry, and the shake confirm all route here. A no-op when
     * nothing is undoable; the entry is consumed so it can't be replayed twice.
     */
    fun undoLastMove()

    /**
     * Handle a device shake (#230). When shake-to-undo is on and a Move is undoable, returns
     * [ShakeOutcome.Confirm] so the View can raise the "Undo [operation]?" prompt (the accidental-fire
     * safety); a shake with **nothing to undo** emits a tracking event (logger stub until the telemetry seam
     * lands) and returns [ShakeOutcome.Nothing]. A no-op ([ShakeOutcome.Nothing], no event) when the toggle
     * is off — shake is never the only undo path, so the snackbar + menu still work.
     */
    fun onShake(): ShakeOutcome

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
    // The device-local shake-to-undo App setting (#230), sourced from AppScope. Defaulted to an in-memory
    // (on) preference so existing tests build without supplying it (like the [moveEditor] no-op).
    private val shakeToUndoPreference: ShakeToUndoPreference = InMemoryShakeToUndoPreference(),
    // The tracking-event sink (#230): stubbed to the kmp-logger until the telemetry seam lands (ADR-0034).
    // A seam (not a direct log call) so a shake-with-nothing-to-undo is assertable in commonTest.
    private val trackEvent: (String) -> Unit = ::logTrackingEvent,
    coroutineContext: CoroutineContext = Dispatchers.Default,
) : ItemTreeComponent, ComponentContext by componentContext {

    private val scope = componentScope(coroutineContext)
    private val refreshing = MutableStateFlow(false)

    // Which item is lifted in modal move mode, or null when not in move mode (#228).
    private val liftedId = MutableStateFlow<String?>(null)

    // The readiness axis (#290): false = ready-only (blocked items pruned). Ephemeral view preference.
    private val showBlocked = MutableStateFlow(false)

    // The single-level last-undoable register (ADR-0034 decision 8, #230): each move records its inverse here.
    private val lastUndoable = LastUndoable()

    override val state: StateFlow<ItemTreeState> =
        combine(
            combine(itemRepository.observeItems(), foldStore.overrides, refreshing, liftedId, showBlocked) { items, ov, isRefreshing, lifted, showBlockedNow ->
                ItemTreeState(
                    rows = buildItemTree(items, ov, showBlocked = showBlockedNow),
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
                    showBlocked = showBlockedNow,
                )
            },
            lastUndoable.current,
        ) { core, undoable ->
            core.copy(lastMove = undoable?.let { MoveUndo(it.id, it.operation, it.structural) })
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

    override fun onSetShowBlocked(show: Boolean) {
        showBlocked.value = show
    }

    override fun onEnterMoveMode(id: String) {
        liftedId.value = id
    }

    override fun onExitMoveMode() {
        liftedId.value = null
    }

    override fun onMoveUp() = applyMove("reorder") { it.up }

    override fun onMoveDown() = applyMove("reorder") { it.down }

    override fun onIndent() = applyMove("indent") { it.indent }

    override fun onOutdent() = applyMove("outdent") { it.outdent }

    /**
     * Issues the [select]ed relative move for the lifted item as one independently-undoable Move (#228):
     * recomputes [moveOptions] over the current cache so the target matches what the View greyed, then
     * dispatches through [moveEditor]. A no-op when not in move mode or when that direction is illegal —
     * the item stays lifted so the user can keep moving it ("live per press").
     *
     * Before dispatching, records the inverse in [lastUndoable] (ADR-0034 decision 8, #230): the item's
     * pre-move slot ([currentSlot]) is the exact `(newParentId, position)` an undo moves it back to, through
     * the **same** [moveEditor]. [structural] (a parent change — indent / outdent / reparent) drives the
     * snackbar; a same-level reorder records too (shake-undoable) but shows no snackbar. [operation] labels
     * the shake confirm.
     */
    private fun applyMove(operation: String, select: (MoveOptions) -> MoveTarget?) {
        val id = liftedId.value ?: return
        scope.launch {
            val items = itemRepository.observeItems().first()
            val target = select(moveOptions(items, id)) ?: return@launch
            val from = currentSlot(items, id) ?: return@launch
            lastUndoable.record(operation, structural = target.newParentId != from.newParentId) {
                moveEditor.move(id, from.newParentId, from.position)
            }
            moveEditor.move(id, target.newParentId, target.position)
        }
    }

    override fun undoLastMove() {
        scope.launch { lastUndoable.undo() }
    }

    override fun onShake(): ShakeOutcome {
        if (!shakeToUndoPreference.enabled()) return ShakeOutcome.Nothing
        val entry = lastUndoable.current.value
            ?: return ShakeOutcome.Nothing.also { trackEvent("shake_undo_no_target") }
        return ShakeOutcome.Confirm(entry.operation)
    }
}

// Keep the upstream alive briefly across config changes / brief detachment, then stop to save work.
internal const val STOP_TIMEOUT_MS = 5_000L

// The default tracking-event sink (#230): a kmp-logger stub until the telemetry seam lands (ADR-0034). A
// top-level fn so it's a valid constructor default (a member `logger` isn't in scope for default args).
private fun logTrackingEvent(event: String) {
    Logger("ItemTreeUndo").i { "tracking_event: $event" }
}
