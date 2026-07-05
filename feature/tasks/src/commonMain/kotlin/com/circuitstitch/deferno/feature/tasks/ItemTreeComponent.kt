package com.circuitstitch.deferno.feature.tasks

import com.arkivanov.decompose.ComponentContext
import com.circuitstitch.deferno.core.common.componentScope
import com.circuitstitch.deferno.core.common.log.Logger
import com.circuitstitch.deferno.core.data.item.InMemoryShakeToUndoPreference
import com.circuitstitch.deferno.core.data.item.ItemFoldStore
import com.circuitstitch.deferno.core.data.item.ItemRepository
import com.circuitstitch.deferno.core.data.item.ShakeToUndoPreference
import com.circuitstitch.deferno.core.data.task.BlockedByResult
import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
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
    // The kind-aware command menu's per-row Task state (#231), keyed by item id — only **Task** rows have an
    // entry (the write layer is Task-centric). The View reads it to label Pin↔Unpin / Add↔Remove-from-plan
    // and to swap the status block; a non-Task row (no entry) gets the cross-kind subset (Add subtask · Move).
    val menuStates: Map<String, TaskMenuState> = emptyMap(),
    // The "Blocked by…" picker (#291), non-null while open. Component-owned (not View-local like the
    // simple confirms): its candidates come from the UNFILTERED item set (the ready-only rows prune
    // exactly the blocked items a blocker chain needs) with the would-be-cycle ids already excluded.
    val blockedByPicker: BlockedByPickerState? = null,
    // The last blockedBy write failure (#291), typed for locale-aware rendering — non-null until the
    // View acknowledges it. The write is online-only (server-validated), so a verdict can arrive after
    // the picker closed; the data layer already reverted the optimistic apply when this is set.
    val blockedByError: BlockedByEditError? = null,
    // blocker id → its direct dependents' titles (#291), from the cached edge lists over the unfiltered
    // set. The destructive-unblock confirm (Set aside / Delete on an `isBlocker` row) names these.
    val dependents: Map<String, List<String>> = emptyMap(),
)

/**
 * The open "Blocked by…" picker (#291): the edited row ([itemId]/[itemTitle]), its current blocker ids
 * ([current], the pre-checked entries — order preserved from the cached edge list), and the
 * cycle-safe [candidates] (every other cached item minus the edited row and its dependent closure —
 * same org by construction, the `/items` set is the account's own). The View renders a checkbox list
 * and submits the full target set via [ItemTreeComponent.onSetBlockedBy].
 */
data class BlockedByPickerState(
    val itemId: String,
    val itemTitle: String,
    val current: List<String>,
    val candidates: List<BlockedByCandidate>,
)

/** One pickable blocker row (#291): any cached item outside the would-be-cycle set. */
data class BlockedByCandidate(val id: String, val title: String, val kind: ItemKind)

/**
 * Why a blockedBy write snapped back (#291), typed so every platform View localizes the message. The
 * server's raw prose (e.g. "cannot add a blocked_by edge that would form a cycle") is logged, not shown.
 */
enum class BlockedByEditError {
    /** No connectivity — the edge write is online-only (server-validated); nothing was changed. */
    Offline,

    /** The server rejected the edge set (a 400 — dependency cycle or cross-org); the edit was reverted. */
    Rejected,
}

/**
 * The View's projection of the [LastUndoable] register (ADR-0034 decision 8, #230). [structural] gates the
 * top **"Moved · Undo"** snackbar — shown on reparent / indent / outdent, **not** a plain same-level reorder;
 * [operation] feeds the shake confirm ("Undo [operation]?"); [id] is a monotonic token that re-keys the
 * single-shot snackbar effect across successive moves (two indents in a row still each raise the snackbar).
 */
data class MoveUndo(
    val id: Int,
    val structural: Boolean,
    /** Typed for locale-aware rendering — every platform View localizes the confirm from this (#327). */
    val operationKind: MoveOperation,
)

/**
 * What a device shake should do (ADR-0034 decision 8, #230): raise the [Confirm] prompt for [operation]
 * ("Undo [operation]?"), or do [Nothing] — the toggle is off, or nothing is undoable (the latter having
 * already emitted its tracking event). The View renders the confirm and, on accept, calls [ItemTreeComponent.undoLastMove].
 */
sealed interface ShakeOutcome {
    /** Every platform View localizes the confirm ("Undo [operation]?") from the typed [operationKind] (#327). */
    data class Confirm(val operationKind: MoveOperation) : ShakeOutcome
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

    // --- Kind-aware command menu (ADR-0034 decision 7, #231): the long-press row menu's write intents.
    // Each carries the row's current value (the "args from the row" rule — the component's StateFlow is
    // WhileSubscribed). The status/Pin/plan/Delete writes are Task-only (the native command layer is
    // Task-centric); the View only surfaces them for a Task row (one with a [ItemTreeState.menuStates] entry). ---

    /**
     * Create a Task child under [parentId] (any kind — only Tasks carry a parent, so a "subtask" is always a
     * Task) titled [title], through the injected create seam. A blank title is a no-op.
     */
    fun onAddSubtask(parentId: String, title: String)

    /** Set Task [id]'s pinned flag to [pinned] (the menu's Pin ↔ Unpin toggle, #231) — Task-only `SetTaskPinned`. */
    fun onSetPinned(id: String, pinned: Boolean)

    /** Add ([inPlan] = true) or remove Task [id] from today's plan (the menu's Add ↔ Remove toggle, #231). */
    fun onSetInPlan(id: String, inPlan: Boolean)

    /** Move Task [id] to [target] working state — the kind-aware status block (Start working / Mark done / Set aside). */
    fun onSetWorkingState(id: String, target: WorkingState)

    /**
     * Set the recurring definition [id] (a Habit/Chore/Event) to [target] definition state — the non-Task
     * status block (Archive → Archived, Restore → Active, #299). The component resolves the row's
     * [ItemKind] from its current tree state (the writer needs it to route the per-kind PATCH), so the
     * View passes only the id + target. A no-op if the row isn't in the current tree (uncached).
     */
    fun onSetDefinitionState(id: String, target: DefinitionState)

    /** Delete Task [id] permanently (the menu's destructive Delete — the View confirms first, #231). */
    fun onDelete(id: String)

    // --- "Blocked by…" dependency edges (#291): Task-only, online-only (server-validated) writes. ---

    /**
     * Open the "Blocked by…" picker for Task [id] (the menu entry). Computes the picker payload off the
     * current unfiltered item cache — current blockers pre-checked, the edited row + its dependent
     * closure excluded (choosing one of those would close a cycle the server 400s) — and surfaces it as
     * [ItemTreeState.blockedByPicker]. A no-op if the row isn't in the current cache.
     */
    fun onOpenBlockedByPicker(id: String)

    /** Close the picker without writing (the dialog's dismiss). */
    fun onDismissBlockedByPicker()

    /**
     * Replace Task [id]'s blocker list with the raw item ids [blockers] (empty clears every edge) and
     * close the picker. Advisory only — declaring an edge never moves or locks anything. The write is
     * **online-only** (the server alone validates cycles/orgs): it applies optimistically underneath,
     * and a failure verdict both reverts it (data layer) and lands on [ItemTreeState.blockedByError].
     */
    fun onSetBlockedBy(id: String, blockers: List<String>)

    /** Acknowledge the surfaced write failure (the View's snackbar/dialog dismiss). */
    fun onDismissBlockedByError()

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
    // The kind-aware command menu's per-row Task state (#231): the Task working-state/pinned/in-plan join
    // the shell builds off the Task list + today's plan, surfaced on [ItemTreeState.menuStates]. Defaulted
    // empty so the read/move-only tests build without it (like [moveEditor]); a non-Task row has no entry.
    private val menuStates: Flow<Map<String, TaskMenuState>> = flowOf(emptyMap()),
    // The kind-aware menu's Task-only write seams (#231), threaded from the shell over CommandExecutor.
    // All default to no-ops so the existing read/move/navigation tests construct the component without them.
    private val workingStateEditor: WorkingStateEditor = WorkingStateEditor.NONE,
    // The non-Task status seam (#299): set a recurring definition's DefinitionState. Defaults to no-op so the
    // existing read/move/navigation tests construct the component without it (like [workingStateEditor]).
    private val definitionStateEditor: DefinitionStateEditor = DefinitionStateEditor.NONE,
    // The dependency-edge seam (#291): online-only (server-validated), returns the verdict this component
    // surfaces as [ItemTreeState.blockedByError]. Defaults to the always-Applied NONE like its siblings.
    private val blockedByEditor: BlockedByEditor = BlockedByEditor.NONE,
    private val setPinned: suspend (TaskId, Boolean) -> Unit = { _, _ -> },
    // The "Add subtask" create seam: [TaskId] is the parent's raw id (any kind — the child is always a Task).
    private val createSubtask: suspend (TaskId, String) -> Unit = { _, _ -> },
    private val deleteTask: suspend (TaskId) -> Unit = { _ -> },
    private val addToPlan: suspend (TaskId) -> Unit = { _ -> },
    private val removeFromPlan: suspend (TaskId) -> Unit = { _ -> },
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

    // The open "Blocked by…" picker + the last write failure (#291), surfaced on the state.
    private val blockedByPicker = MutableStateFlow<BlockedByPickerState?>(null)
    private val blockedByError = MutableStateFlow<BlockedByEditError?>(null)

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
                    // Over the UNFILTERED items — a dependent is blocked, so pruned rows are the norm (#291).
                    dependents = dependentTitles(items),
                )
            },
            lastUndoable.current,
            menuStates,
            blockedByPicker,
            blockedByError,
        ) { core, undoable, menus, picker, edgeError ->
            core.copy(
                lastMove = undoable?.let { MoveUndo(it.id, it.structural, it.operation) },
                menuStates = menus,
                blockedByPicker = picker,
                blockedByError = edgeError,
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

    override fun onSetShowBlocked(show: Boolean) {
        showBlocked.value = show
    }

    override fun onEnterMoveMode(id: String) {
        liftedId.value = id
    }

    override fun onExitMoveMode() {
        liftedId.value = null
    }

    override fun onMoveUp() = applyMove(MoveOperation.Reorder) { it.up }

    override fun onMoveDown() = applyMove(MoveOperation.Reorder) { it.down }

    override fun onIndent() = applyMove(MoveOperation.Indent) { it.indent }

    override fun onOutdent() = applyMove(MoveOperation.Outdent) { it.outdent }

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
    private fun applyMove(operation: MoveOperation, select: (MoveOptions) -> MoveTarget?) {
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

    // --- Kind-aware command menu writes (ADR-0034 decision 7, #231). Each is a thin dispatch to its
    // Task-only seam; the View gates which entries it shows by kind + the row's [TaskMenuState]. ---

    override fun onAddSubtask(parentId: String, title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        scope.launch { createSubtask(TaskId(parentId), trimmed) }
    }

    override fun onSetPinned(id: String, pinned: Boolean) {
        scope.launch { setPinned(TaskId(id), pinned) }
    }

    override fun onSetInPlan(id: String, inPlan: Boolean) {
        scope.launch { if (inPlan) addToPlan(TaskId(id)) else removeFromPlan(TaskId(id)) }
    }

    override fun onSetWorkingState(id: String, target: WorkingState) {
        // No cached row to gate on (the tree row is the cross-kind Item projection, not a full Task); a null
        // `current` is the executor's "uncached → never blocked" path. The View already hides the verb the
        // Task is in, so no redundant transition is offered.
        scope.launch { workingStateEditor.setWorkingState(TaskId(id), target, current = null) }
    }

    override fun onSetDefinitionState(id: String, target: DefinitionState) {
        // Resolve the row's kind from the current flattened tree (the writer needs it to route the per-kind
        // PATCH). The View that calls this is subscribed to [state], so the rows are populated; a row absent
        // from the current tree (uncached) is a no-op rather than a guessed route.
        val kind = state.value.rows.firstOrNull { it.item.id == id }?.item?.kind ?: return
        scope.launch { definitionStateEditor.setDefinitionState(id, kind, target) }
    }

    override fun onDelete(id: String) {
        scope.launch { deleteTask(TaskId(id)) }
    }

    // --- "Blocked by…" dependency edges (#291) ---

    override fun onOpenBlockedByPicker(id: String) {
        scope.launch {
            val items = itemRepository.observeItems().first()
            val target = items.firstOrNull { it.id == id } ?: return@launch
            // Exclude the edited row + everything that (transitively) depends on it: choosing one of
            // those closes a dependency cycle the server rejects — the Move pattern's greyed-out guard.
            val excluded = dependentClosure(items, id) + id
            blockedByPicker.value = BlockedByPickerState(
                itemId = id,
                itemTitle = target.title,
                current = target.blockedBy.map { it.item },
                candidates = items.filterNot { it.id in excluded }
                    .map { BlockedByCandidate(it.id, it.title, it.kind) },
            )
        }
    }

    override fun onDismissBlockedByPicker() {
        blockedByPicker.value = null
    }

    override fun onSetBlockedBy(id: String, blockers: List<String>) {
        blockedByPicker.value = null
        scope.launch {
            when (val result = blockedByEditor.setBlockedBy(TaskId(id), blockers)) {
                is BlockedByResult.Applied ->
                    // Pull the recomputed derived flags now (the blocker's own `is_blocker` badge, a
                    // dependent chain's inherited `blocked`) — the write just proved we're online.
                    itemRepository.refresh()
                is BlockedByResult.Offline -> blockedByError.value = BlockedByEditError.Offline
                is BlockedByResult.Failed -> {
                    Logger("ItemTreeBlockedBy").i { "blockedBy write rejected: ${result.message}" }
                    blockedByError.value = BlockedByEditError.Rejected
                }
            }
        }
    }

    override fun onDismissBlockedByError() {
        blockedByError.value = null
    }
}

// Keep the upstream alive briefly across config changes / brief detachment, then stop to save work.
internal const val STOP_TIMEOUT_MS = 5_000L

// The default tracking-event sink (#230): a kmp-logger stub until the telemetry seam lands (ADR-0034). A
// top-level fn so it's a valid constructor default (a member `logger` isn't in scope for default args).
private fun logTrackingEvent(event: String) {
    Logger("ItemTreeUndo").i { "tracking_event: $event" }
}
