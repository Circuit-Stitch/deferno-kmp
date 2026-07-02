package com.circuitstitch.deferno.feature.tasks.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.circuitstitch.deferno.core.designsystem.component.BlockedChip
import com.circuitstitch.deferno.core.designsystem.component.DashedAddButton
import com.circuitstitch.deferno.core.designsystem.component.DefernoIcons
import com.circuitstitch.deferno.core.designsystem.component.MonoMeta
import com.circuitstitch.deferno.core.designsystem.component.ProgressBarThin
import com.circuitstitch.deferno.core.designsystem.component.SearchBarDisplay
import com.circuitstitch.deferno.core.designsystem.component.SegmentedFilter
import com.circuitstitch.deferno.core.designsystem.component.TreeChip
import com.circuitstitch.deferno.core.designsystem.resources.Res
import com.circuitstitch.deferno.core.designsystem.resources.common_add
import com.circuitstitch.deferno.core.designsystem.resources.common_cancel
import com.circuitstitch.deferno.core.designsystem.resources.common_cannot_be_undone
import com.circuitstitch.deferno.core.designsystem.resources.common_collapse_named_cd
import com.circuitstitch.deferno.core.designsystem.resources.common_delete
import com.circuitstitch.deferno.core.designsystem.resources.common_done
import com.circuitstitch.deferno.core.designsystem.resources.common_expand_named_cd
import com.circuitstitch.deferno.core.designsystem.resources.common_open_named_cd
import com.circuitstitch.deferno.core.designsystem.resources.common_refresh
import com.circuitstitch.deferno.core.designsystem.resources.search_placeholder_trees
import com.circuitstitch.deferno.core.designsystem.resources.tasks_add_subtask_dialog_title
import com.circuitstitch.deferno.core.designsystem.resources.tasks_add_subtask_placeholder
import com.circuitstitch.deferno.core.designsystem.resources.tasks_badge_blocker
import com.circuitstitch.deferno.core.designsystem.resources.tasks_badge_blocker_a11y
import com.circuitstitch.deferno.core.designsystem.resources.tasks_delete_item_confirm_title
import com.circuitstitch.deferno.core.designsystem.resources.tasks_filter_active
import com.circuitstitch.deferno.core.designsystem.resources.tasks_filter_all
import com.circuitstitch.deferno.core.designsystem.resources.tasks_filter_in_today
import com.circuitstitch.deferno.core.designsystem.resources.tasks_menu_activate
import com.circuitstitch.deferno.core.designsystem.resources.tasks_menu_add_subtask
import com.circuitstitch.deferno.core.designsystem.resources.tasks_menu_add_to_plan
import com.circuitstitch.deferno.core.designsystem.resources.tasks_menu_archive
import com.circuitstitch.deferno.core.designsystem.resources.tasks_menu_delete_permanent
import com.circuitstitch.deferno.core.designsystem.resources.tasks_menu_mark_done
import com.circuitstitch.deferno.core.designsystem.resources.tasks_menu_move
import com.circuitstitch.deferno.core.designsystem.resources.tasks_menu_open
import com.circuitstitch.deferno.core.designsystem.resources.tasks_menu_pin
import com.circuitstitch.deferno.core.designsystem.resources.tasks_menu_remove_from_plan
import com.circuitstitch.deferno.core.designsystem.resources.tasks_menu_start_working
import com.circuitstitch.deferno.core.designsystem.resources.tasks_menu_undo_move
import com.circuitstitch.deferno.core.designsystem.resources.tasks_menu_unpin
import com.circuitstitch.deferno.core.designsystem.resources.tasks_move_down
import com.circuitstitch.deferno.core.designsystem.resources.tasks_move_indent
import com.circuitstitch.deferno.core.designsystem.resources.tasks_move_outdent
import com.circuitstitch.deferno.core.designsystem.resources.tasks_move_up
import com.circuitstitch.deferno.core.designsystem.resources.tasks_progress_done
import com.circuitstitch.deferno.core.designsystem.resources.tasks_progress_fraction
import com.circuitstitch.deferno.core.designsystem.resources.tasks_set_aside
import com.circuitstitch.deferno.core.designsystem.resources.tasks_source_from_github
import com.circuitstitch.deferno.core.designsystem.resources.tasks_source_from_google_calendar
import com.circuitstitch.deferno.core.designsystem.resources.tasks_tree_actions_for_item
import com.circuitstitch.deferno.core.designsystem.resources.tasks_tree_add_tree
import com.circuitstitch.deferno.core.designsystem.resources.tasks_tree_count
import com.circuitstitch.deferno.core.designsystem.resources.tasks_tree_empty_body
import com.circuitstitch.deferno.core.designsystem.resources.tasks_tree_empty_title
import com.circuitstitch.deferno.core.designsystem.resources.tasks_tree_filtered_empty_body
import com.circuitstitch.deferno.core.designsystem.resources.tasks_tree_filtered_empty_title
import com.circuitstitch.deferno.core.designsystem.resources.tasks_tree_refreshing
import com.circuitstitch.deferno.core.designsystem.resources.tasks_tree_show_blocked
import com.circuitstitch.deferno.core.designsystem.resources.tasks_tree_title
import com.circuitstitch.deferno.core.designsystem.theme.defernoColors
import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.ItemSource
import com.circuitstitch.deferno.core.model.WorkingState
import com.circuitstitch.deferno.feature.tasks.ItemRow
import com.circuitstitch.deferno.feature.tasks.MoveMode
import com.circuitstitch.deferno.feature.tasks.TaskMenuState
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

// The Tasks Item-tree renderer (ADR-0034, #227/#228) restyled to the "See the trees" direction (#231):
// the cross-kind forest ("Everything") flattened to depth-indented rows in one LazyColumn, shared by the
// Android (TaskListScreen) and desktop (TasksDesktopScreen) primary pane. Stateless and platform-neutral;
// the component (DefaultItemTreeComponent) holds all logic — these only render the [ItemRow]s, forward
// taps, and drive the modal move mode. Handlers take their args from the ROW, never from a state snapshot
// (the component's StateFlow is WhileSubscribed — empty without a live subscriber).

/** The trailing open-detail control keeps a full [MinTouchTarget]; the leading fold affordance is the
 *  connected [TreeNode] glyph (the row body stays a fold target too). */
private val chevronGutter = MinTouchTarget

/** Test tag on the tree Column — the move-mode focus + key-event target (see ItemTreeKeyboardTest). */
internal const val ItemTreeTag = "itemTree"

/** Breathing room between the tree filigree (the left-most rail line / depth-0 dot) and the screen edge. */
private val TreeRowStartInset = 12.dp

// The calm in-list filter segments ("In today" / "Active" / "All") are local view state, not a component
// intent — resolved from string resources inside [EverythingSearchFilter]. "Active" hides terminals.

/**
 * The Tasks Item tree ("Everything"): a calm header band (title + count + a read-only search bar + a
 * local segmented filter) over a `LazyColumn` of [rows], capped by an "Add a tree" affordance. Each parent
 * row toggles its fold on a chevron/body tap; a childless leaf's body is inert; the trailing `›` opens
 * detail (ADR-0034 decision 7). A **long-press** lifts the row into [moveMode] (decision 6, #228): the
 * lifted row is highlighted, the rest calmed, and a bottom bar offers **↑ ↓ ‹ ›** (illegal directions
 * greyed) + Done — mirrored on the keyboard (Alt+↑/↓ reorder, Tab / Shift-Tab indent / outdent, Esc =
 * Done). Empty/refreshing states mirror the calm copy of the other Tasks panes.
 *
 * [onSearch] opens the global Search overlay (no-op default; the integrator wires it). [onAdd] starts a new
 * tree (no-op default). The segmented filter is **local view state**: it filters the displayed rows only —
 * "Active" hides terminal items, "All" shows everything; "In today" shows all for now (plan membership
 * isn't on [com.circuitstitch.deferno.core.model.Item] yet).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ItemTreeContent(
    rows: List<ItemRow>,
    isRefreshing: Boolean,
    onToggleExpand: (id: String, currentlyExpanded: Boolean) -> Unit,
    onOpenDetail: (id: String, kind: ItemKind) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    // The "See the trees" header affordances (#231). Defaulted no-ops so read-only callers / tests render
    // without wiring them; the integrator threads the real Search / new-tree intents.
    onSearch: () -> Unit = {},
    onAdd: () -> Unit = {},
    // The readiness axis (#290): [showBlocked] is the component's resting-off (ready-only) flag — the rows
    // arrive already pruned of `blocked` items; this only drives the "Show blocked" chip's selected state.
    // [onSetShowBlocked] flips it. Defaulted so read-only callers / tests render without wiring readiness.
    showBlocked: Boolean = false,
    onSetShowBlocked: (Boolean) -> Unit = {},
    // Hoisted so the host can read the scroll position (Android docks a compact search into the shell top
    // bar once the inline header scrolls off — see MainShell). [pinSearch] keeps the inline search/filter
    // band pinned (a stickyHeader) for hosts with no dock (desktop); Android sets it false so the band
    // scrolls away and the docked bar search takes over.
    listState: LazyListState = rememberLazyListState(),
    pinSearch: Boolean = true,
    // Whether to render the inline search bar in the header band. Android sets this false — the shell hosts
    // search as the native top bar (the Files-style pill) — so the band shows only the local filter there.
    searchInList: Boolean = true,
    // Modal move mode (#228). Defaulted so the read-only callers / tests render without wiring it.
    moveMode: MoveMode? = null,
    onEnterMoveMode: (id: String) -> Unit = {},
    onMoveUp: () -> Unit = {},
    onMoveDown: () -> Unit = {},
    onIndent: () -> Unit = {},
    onOutdent: () -> Unit = {},
    onExitMoveMode: () -> Unit = {},
    // Undo (ADR-0034 decision 8, #230): a persistent "Undo move" entry in the long-press menu, the non-shake,
    // non-transient path. [canUndo] gates it; defaulted off so read-only callers (desktop/tests) omit it.
    canUndo: Boolean = false,
    onUndoMove: () -> Unit = {},
    // The kind-aware command menu (ADR-0034 decision 7, #231). [menuStates] carries each Task row's
    // working-state/pinned/in-plan (keyed by item id) so the menu labels Pin↔Unpin / Add↔Remove and swaps
    // the status block; only Task rows have an entry, but the View reads kind from the row (not from this
    // map's presence), so a non-Task row gets the cross-kind subset (Add subtask · Move) on kind alone.
    // All defaulted inert so read-only callers / tests render without wiring the menu writes.
    menuStates: Map<String, TaskMenuState> = emptyMap(),
    onAddSubtask: (parentId: String, title: String) -> Unit = { _, _ -> },
    onSetPinned: (id: String, pinned: Boolean) -> Unit = { _, _ -> },
    onSetInPlan: (id: String, inPlan: Boolean) -> Unit = { _, _ -> },
    onSetWorkingState: (id: String, target: WorkingState) -> Unit = { _, _ -> },
    // The non-Task status block (#299): a Habit/Chore/Event row's Archive ↔ Activate definition light-switch.
    // Defaulted inert so read-only callers / tests render without wiring it; the component resolves the kind.
    onSetDefinitionState: (id: String, target: DefinitionState) -> Unit = { _, _ -> },
    onDelete: (id: String) -> Unit = {},
) {
    // Route the move keystrokes (Alt+↑/↓, Tab/Shift-Tab) to the column while an item is lifted: focus it on
    // entry so its onPreviewKeyEvent sees them before focus traversal would consume Tab (#228). The column is
    // made focusable ONLY in move mode, so the tree adds no empty TalkBack focus stop in its normal use.
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(moveMode != null) {
        if (moveMode != null) runCatching { focusRequester.requestFocus() }
    }
    val moveFocus = if (moveMode != null) Modifier.focusRequester(focusRequester).focusable() else Modifier

    // Local in-list filter (#231): "Active" hides terminal items; "All" shows everything. Defaults to "All"
    // so the tree's existing behaviour is unchanged (terminal rows still show, de-emphasized) — the filter
    // is an opt-in narrowing, calm and non-destructive. ponytail: "In today" shows all for now — plan
    // membership isn't on the Item projection yet, so there's nothing to narrow on without a new field.
    var filterIndex by remember { mutableIntStateOf(2) } // default to "All" — preserve existing behaviour
    val visibleRows = remember(rows, filterIndex) {
        when (filterIndex) {
            1 -> rows.filterNot { it.item.isTerminal } // Active
            else -> rows // In today (for now) + All
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(ItemTreeTag)
            .then(moveFocus)
            .onPreviewKeyEvent { event ->
                if (moveMode == null || event.type != KeyEventType.KeyDown) {
                    false
                } else {
                    when {
                        event.key == Key.DirectionUp && event.isAltPressed -> { onMoveUp(); true }
                        event.key == Key.DirectionDown && event.isAltPressed -> { onMoveDown(); true }
                        event.key == Key.Tab && event.isShiftPressed -> { onOutdent(); true }
                        event.key == Key.Tab -> { onIndent(); true }
                        // Escape exits — the keyboard's "Done". Without it a keyboard-only user is trapped:
                        // Tab is consumed for indent here, so focus can never traverse to the Done button.
                        event.key == Key.Escape -> { onExitMoveMode(); true }
                        else -> false
                    }
                }
            },
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            // Edge-to-edge (ADR-0035 #2): the list draws under the nav bar but pads its last row clear of
            // it — only when no move bar is shown (in move mode the bar owns that inset, so the list above
            // it must not double-pad). Empty inset on desktop.
            contentPadding = if (moveMode == null) {
                WindowInsets.systemBars.only(WindowInsetsSides.Bottom).asPaddingValues()
            } else {
                PaddingValues()
            },
        ) {
            // The "Everything" title scrolls away with the list to free room for the forest (#260 restyle);
            // the search + filter band below it PINS (stickyHeader), so search/narrow stays reachable once
            // you're deep in the list. Both hidden in move mode (the lifted-row focus owns the surface).
            if (moveMode == null) {
                item(key = "everything-title") {
                    EverythingTitle(
                        treeCount = rows.count { it.depth == 0 },
                        isRefreshing = isRefreshing,
                        onRefresh = onRefresh,
                    )
                }
                if (pinSearch) {
                    stickyHeader(key = "everything-search") {
                        EverythingSearchFilter(
                            onSearch = onSearch,
                            showSearch = searchInList,
                            filterIndex = filterIndex,
                            onFilterSelect = { filterIndex = it },
                            showBlocked = showBlocked,
                            onSetShowBlocked = onSetShowBlocked,
                        )
                    }
                } else {
                    item(key = "everything-search") {
                        EverythingSearchFilter(
                            onSearch = onSearch,
                            showSearch = searchInList,
                            filterIndex = filterIndex,
                            onFilterSelect = { filterIndex = it },
                            showBlocked = showBlocked,
                            onSetShowBlocked = onSetShowBlocked,
                        )
                    }
                }
            }
            if (isRefreshing) {
                item(key = "refreshing") { LoadingStrip(label = stringResource(Res.string.tasks_tree_refreshing)) }
            }
            if (visibleRows.isEmpty() && !isRefreshing) {
                item(key = "empty") {
                    EmptyState(
                        title = if (rows.isEmpty()) {
                            stringResource(Res.string.tasks_tree_empty_title)
                        } else {
                            stringResource(Res.string.tasks_tree_filtered_empty_title)
                        },
                        body = if (rows.isEmpty()) {
                            stringResource(Res.string.tasks_tree_empty_body)
                        } else {
                            stringResource(Res.string.tasks_tree_filtered_empty_body)
                        },
                    )
                }
            } else {
                items(visibleRows, key = { it.item.id }) { row ->
                    ItemTreeRow(
                        row = row,
                        inMoveMode = moveMode != null,
                        isLifted = moveMode?.liftedId == row.item.id,
                        menuState = menuStates[row.item.id],
                        onToggleExpand = onToggleExpand,
                        onOpenDetail = onOpenDetail,
                        onEnterMoveMode = onEnterMoveMode,
                        canUndo = canUndo,
                        onUndoMove = onUndoMove,
                        onAddSubtask = onAddSubtask,
                        onSetPinned = onSetPinned,
                        onSetInPlan = onSetInPlan,
                        onSetWorkingState = onSetWorkingState,
                        onSetDefinitionState = onSetDefinitionState,
                        onDelete = onDelete,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            // "Add a tree" foot (#231) — only outside move mode (the list is calm during a move).
            if (moveMode == null) {
                item(key = "add-tree") {
                    DashedAddButton(
                        text = stringResource(Res.string.tasks_tree_add_tree),
                        onClick = onAdd,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }
        if (moveMode != null) {
            MoveModeBar(
                move = moveMode,
                onMoveUp = onMoveUp,
                onMoveDown = onMoveDown,
                onIndent = onIndent,
                onOutdent = onOutdent,
                onDone = onExitMoveMode,
            )
        }
    }
}

/**
 * The "Everything" title band — the pane title + a `{n} trees` count + the Refresh action. Rendered as
 * the first (scrolling) list item, so it slides away to free room for the forest as you scroll (#260).
 */
@Composable
private fun EverythingTitle(
    treeCount: Int,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(Res.string.tasks_tree_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() },
                )
                MonoMeta(text = pluralStringResource(Res.plurals.tasks_tree_count, treeCount, treeCount))
            }
            TextButton(
                onClick = onRefresh,
                enabled = !isRefreshing,
                modifier = Modifier.heightIn(min = MinTouchTarget),
            ) { Text(stringResource(Res.string.common_refresh)) }
        }
    }
}

/**
 * The read-only [SearchBarDisplay] + the local [SegmentedFilter] — the part that PINS (a `stickyHeader`)
 * so search/narrow stays reachable after the title scrolls off. An opaque `surface` so scrolling rows
 * pass cleanly beneath it.
 */
@Composable
private fun EverythingSearchFilter(
    onSearch: () -> Unit,
    showSearch: Boolean,
    filterIndex: Int,
    onFilterSelect: (Int) -> Unit,
    showBlocked: Boolean,
    onSetShowBlocked: (Boolean) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Hosts with a top-bar search (Android) omit the inline bar; desktop keeps it (no top-bar dock).
            if (showSearch) {
                SearchBarDisplay(placeholder = stringResource(Res.string.search_placeholder_trees), onClick = onSearch)
            }
            val treeFilters = listOf(
                stringResource(Res.string.tasks_filter_in_today),
                stringResource(Res.string.tasks_filter_active),
                stringResource(Res.string.tasks_filter_all),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SegmentedFilter(options = treeFilters, selectedIndex = filterIndex, onSelect = onFilterSelect)
                Spacer(Modifier.weight(1f))
                // The readiness axis (#290), distinct from the In-today/Active/All segment: ready-only by
                // default (off → blocked items + their subtrees pruned), toggled on to reveal them. A
                // FilterChip carries its own selected/role semantics for TalkBack.
                FilterChip(
                    selected = showBlocked,
                    onClick = { onSetShowBlocked(!showBlocked) },
                    label = { Text(stringResource(Res.string.tasks_tree_show_blocked)) },
                )
            }
        }
    }
}

/**
 * One depth-indented tree row, restyled (#231). A leading [KindDot] marks the Item's kind; the chevron
 * gutter shows ▾/▸ for a parent. Outside move mode a parent's chevron + body tap toggle its fold, the
 * trailing `›` (a fixed, always-present target) opens detail, and a **long-press** opens a minimal menu
 * whose "Move" entry lifts the row into move mode (#228). In move mode taps are inert (the list goes
 * calm): the lifted row is highlighted, the rest dimmed. A collapsed parent with subtree counts shows a
 * `{done} of {total}` MonoMeta + a thin progress bar; a terminal (Done/Dropped/Archived) item is
 * de-emphasized (muted + strikethrough title).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ItemTreeRow(
    row: ItemRow,
    inMoveMode: Boolean,
    isLifted: Boolean,
    // A Task row's joined working-state/pinned/in-plan (#231) — or null for a non-Task row OR a Task whose
    // state hasn't joined yet. The menu reads kind from [item.kind], never from this; this only labels the
    // toggles + swaps the status block, so the value-dependent entries render once it's present.
    menuState: TaskMenuState?,
    onToggleExpand: (String, Boolean) -> Unit,
    onOpenDetail: (String, ItemKind) -> Unit,
    onEnterMoveMode: (String) -> Unit,
    canUndo: Boolean,
    onUndoMove: () -> Unit,
    onAddSubtask: (parentId: String, title: String) -> Unit,
    onSetPinned: (id: String, pinned: Boolean) -> Unit,
    onSetInPlan: (id: String, inPlan: Boolean) -> Unit,
    onSetWorkingState: (id: String, target: WorkingState) -> Unit,
    onSetDefinitionState: (id: String, target: DefinitionState) -> Unit,
    onDelete: (id: String) -> Unit,
) {
    val item = row.item
    // Three distinct row states (#290): a terminal (Done/Dropped/Archived) item strikes + mutes the title;
    // a `blocked` item mutes it too but WITHOUT the strike — a distinct "blocked, not finished" read —
    // and wears a "Blocked" pill; an `isBlocker` item gates others and wears a "Blocker" badge. Blocked
    // rows only reach here when "show blocked" is on (else they're pruned at the flatten, ItemTree.kt).
    val titleColor =
        if (item.isTerminal || item.blocked) MaterialTheme.defernoColors.inkMuted else MaterialTheme.colorScheme.onSurface
    var menuOpen by remember { mutableStateOf(false) }
    // The two menu-spawned dialogs (#231): the destructive-Delete confirm and the Add-subtask title prompt.
    var confirmDelete by remember { mutableStateOf(false) }
    var addSubtaskOpen by remember { mutableStateOf(false) }

    // The lifted row is highlighted; the rest of the list calms (dimmed) while a move is in progress.
    val rowColor =
        if (isLifted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    val dim = if (inMoveMode && !isLifted) 0.38f else 1f

    // Outside move mode: body tap toggles a parent's fold (leaf body inert), long-press opens the Move menu.
    // In move mode the body is inert so a scroll/tap can never masquerade as a move (ADR-0034 decision 6).
    val bodyModifier =
        if (inMoveMode) {
            Modifier
        } else {
            Modifier.combinedClickable(
                onClickLabel = when {
                    !row.hasChildren -> null
                    row.isExpanded -> stringResource(Res.string.common_collapse_named_cd, item.title)
                    else -> stringResource(Res.string.common_expand_named_cd, item.title)
                },
                onLongClickLabel = stringResource(Res.string.tasks_tree_actions_for_item, item.title),
                onLongClick = { menuOpen = true },
                onClick = { if (row.hasChildren) onToggleExpand(item.id, row.isExpanded) },
            )
        }

    Surface(color = rowColor) {
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .then(bodyModifier)
                    .alpha(dim)
                    .padding(start = TreeRowStartInset)
                    // The curvy connecting rail (#231) replaces a flat depth indent: a continuous spine
                    // hangs each child off its parent in a calm tint of the row's accent (matching the
                    // node). It lands its elbow in the kind dot (connectToDot) and, for an expanded parent,
                    // drops a line to its subtree (descendToChildren). Also adds the per-depth indent.
                    .treeRail(
                        row.spine,
                        kindColor(item.kind).copy(alpha = RailTintAlpha),
                        connectToDot = true,
                        descendToChildren = row.hasChildren && row.isExpanded,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The connected tree node (#231 follow-up): the rail elbow lands at its left edge and the
                // glyph carries the line into the kind dot — merged with the fold chevron for a parent (a
                // real, keyboard-focusable Button). Colour is reinforcement, never the sole signal (a
                // terminal item also strikes the title + mutes the text); a leaf's node is decorative.
                TreeNode(
                    hasChildren = row.hasChildren,
                    isExpanded = row.isExpanded,
                    title = item.title,
                    accent = kindColor(item.kind),
                    ringColor = rowColor,
                    inMoveMode = inMoveMode,
                    onToggle = { onToggleExpand(item.id, row.isExpanded) },
                )
                Column(modifier = Modifier.weight(1f).padding(vertical = 10.dp)) {
                    Text(
                        // A dimmed `[GitHub#N]` ref prefix for a synced/imported item, alongside the
                        // SourceIndicator mark; null provenance renders the bare title unchanged.
                        text = titleWithExternalRef(
                            title = item.title,
                            source = item.source,
                            externalId = item.externalRef,
                            prefixColor = MaterialTheme.defernoColors.inkMuted,
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        color = titleColor,
                        textDecoration = if (item.isTerminal) TextDecoration.LineThrough else TextDecoration.None,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // Collapsed parent with server-computed counts → a done/total meta + thin progress bar.
                    val total = item.descendantTotal
                    if (row.hasChildren && !row.isExpanded && total != null) {
                        val done = item.descendantDone ?: 0
                        Spacer(Modifier.size(4.dp))
                        val progressA11y = stringResource(Res.string.tasks_progress_done, done, total)
                        MonoMeta(
                            text = stringResource(Res.string.tasks_progress_fraction, done, total),
                            modifier = Modifier.clearAndSetSemantics { contentDescription = progressA11y },
                        )
                        if (total > 0) {
                            Spacer(Modifier.size(2.dp))
                            ProgressBarThin(
                                fraction = done.toFloat() / total,
                                modifier = Modifier.fillMaxWidth().clearAndSetSemantics {},
                            )
                        }
                    }
                }
                // Dependency badges (#290), within the row's existing trailing badge budget. "Blocked" is a
                // quiet outlined pill (the de-emphasis state's at-a-glance + TalkBack carrier); "Blocker" is
                // an amber accent badge marking a row that gates ≥1 other. Each clears its own semantics so
                // TalkBack reads one label, not the inner text twice.
                if (item.blocked) {
                    BlockedChip(modifier = Modifier.padding(horizontal = 4.dp))
                }
                if (item.isBlocker) {
                    TreeChip(
                        text = stringResource(Res.string.tasks_badge_blocker),
                        semanticLabel = stringResource(Res.string.tasks_badge_blocker_a11y),
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
                // A small external-provenance mark when the item was synced/created from GitHub or
                // Google Calendar (else absent — a native Deferno item). It rides ahead of the chevron,
                // inheriting the row's dim in move mode (the Row carries the alpha).
                item.source?.let { source ->
                    SourceIndicator(source, modifier = Modifier.padding(horizontal = 4.dp))
                }
                // The lone open-detail affordance: a fixed target, immune to title length (ADR-0034 dec. 7).
                // Inert in move mode (the list is calm). An icon-only control, so it carries its own
                // contentDescription for TalkBack; the glyph's own semantics are cleared so it isn't read twice.
                val openItemCd = stringResource(Res.string.common_open_named_cd, item.title)
                Box(
                    modifier = Modifier
                        .size(chevronGutter)
                        .then(if (inMoveMode) Modifier else Modifier.clickable { onOpenDetail(item.id, item.kind) })
                        .semantics { contentDescription = openItemCd },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = DefernoIcons.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.defernoColors.inkMuted,
                        modifier = Modifier.size(20.dp).clearAndSetSemantics {},
                    )
                }
            }

            // The kind-aware long-press command menu (ADR-0034 decision 7, #231) — mirrors the web submenu
            // plus the native Move action. Kind is read straight off the row ([item.kind]), never inferred
            // from whether the per-row Task state has joined: a Task whose [menuState] hasn't loaded yet (the
            // tree rows come from the Item repo, [menuState] from the Task+plan repos — independent Flows) is
            // still a Task. The status block, Pin, Add-to-plan and Delete are Task-only writes (the native
            // command layer is Task-centric — MoveItem is the lone cross-kind write), so a non-Task row gets
            // only the cross-kind subset: Add subtask · Move (Open routes to the Task-only detail). Pin/plan/
            // status need the joined values, so they render once [menuState] is present; Open/Delete need only
            // the id, so they gate on kind alone. "Set aside"/"Delete" are destructive (error-tinted; the word,
            // not just colour, carries the signal — a11y). The arbitrary-parent "Move to…" entry + picker land
            // together in #229; the menu opens by long-press (a TalkBack custom action) — keyboard open is #300.
            val isTask = item.kind == ItemKind.Task
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (isTask) {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.tasks_menu_open)) },
                        onClick = { menuOpen = false; onOpenDetail(item.id, item.kind) },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.tasks_menu_add_subtask)) },
                    onClick = { menuOpen = false; addSubtaskOpen = true },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.tasks_menu_move)) },
                    onClick = { menuOpen = false; onEnterMoveMode(item.id) },
                )
                if (canUndo) {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.tasks_menu_undo_move)) },
                        onClick = { menuOpen = false; onUndoMove() },
                    )
                }
                if (isTask) {
                    // Pin/plan/status need the joined per-row state (label direction + which verb to hide), so
                    // they appear once it's present; Delete needs only the id, so it rides the kind gate alone.
                    if (menuState != null) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (menuState.pinned) {
                                        stringResource(Res.string.tasks_menu_unpin)
                                    } else {
                                        stringResource(Res.string.tasks_menu_pin)
                                    },
                                )
                            },
                            onClick = { menuOpen = false; onSetPinned(item.id, !menuState.pinned) },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (menuState.inPlan) {
                                        stringResource(Res.string.tasks_menu_remove_from_plan)
                                    } else {
                                        stringResource(Res.string.tasks_menu_add_to_plan)
                                    },
                                )
                            },
                            onClick = { menuOpen = false; onSetInPlan(item.id, !menuState.inPlan) },
                        )
                        HorizontalDivider()
                        // Status block: the Task working-state verbs, hiding the one it's already in so no
                        // redundant transition is offered (Habit/Chore/Event status verbs await their seam, #299).
                        if (menuState.workingState != WorkingState.InProgress) {
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.tasks_menu_start_working)) },
                                onClick = { menuOpen = false; onSetWorkingState(item.id, WorkingState.InProgress) },
                            )
                        }
                        if (menuState.workingState != WorkingState.Done) {
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.tasks_menu_mark_done)) },
                                onClick = { menuOpen = false; onSetWorkingState(item.id, WorkingState.Done) },
                            )
                        }
                        if (menuState.workingState != WorkingState.Dropped) {
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.tasks_set_aside), color = MaterialTheme.colorScheme.error) },
                                onClick = { menuOpen = false; onSetWorkingState(item.id, WorkingState.Dropped) },
                            )
                        }
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.tasks_menu_delete_permanent), color = MaterialTheme.colorScheme.error) },
                        onClick = { menuOpen = false; confirmDelete = true },
                    )
                }
                if (!isTask) {
                    // Kind-aware status block for a non-Task definition (ADR-0034 decision 7, #299): the
                    // recurring "light switch" — Archive an active Habit/Chore/Event, or Activate an archived
                    // one. A non-Task row carries no working state, so [item.isTerminal] IS its archived bit
                    // (ItemRepository maps DefinitionState.Archived → terminal); the verb needs no joined state.
                    // Archive is reversible (via Activate), so it isn't error-tinted; the component resolves kind.
                    HorizontalDivider()
                    if (item.isTerminal) {
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.tasks_menu_activate)) },
                            onClick = { menuOpen = false; onSetDefinitionState(item.id, DefinitionState.Active) },
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.tasks_menu_archive)) },
                            onClick = { menuOpen = false; onSetDefinitionState(item.id, DefinitionState.Archived) },
                        )
                    }
                }
            }
        }
    }

    // Delete confirm (destructive, #231) — mirrors the Task-detail kebab's confirm (TaskDetailContent).
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(Res.string.tasks_delete_item_confirm_title, item.title)) },
            text = { Text(stringResource(Res.string.common_cannot_be_undone)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete(item.id) }) {
                    Text(stringResource(Res.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(Res.string.common_cancel)) }
            },
        )
    }

    // Add subtask (#231): a title prompt — the tree has no inline add field (that's the detail's). A blank
    // title is gated out (Add disabled). The child is always a Task (only Tasks carry a parent).
    if (addSubtaskOpen) {
        AddSubtaskDialog(
            parentTitle = item.title,
            onAdd = { title -> addSubtaskOpen = false; onAddSubtask(item.id, title) },
            onDismiss = { addSubtaskOpen = false },
        )
    }
}

/**
 * The menu's "Add subtask" title prompt (#231): a single-line field + Add/Cancel. IME "Done" or the Add
 * button submits a non-blank, trimmed title; the field is the sole input, so it carries no extra label.
 */
@Composable
private fun AddSubtaskDialog(
    parentTitle: String,
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    fun submit() {
        val trimmed = text.trim()
        if (trimmed.isNotEmpty()) onAdd(trimmed)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.tasks_add_subtask_dialog_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text(stringResource(Res.string.tasks_add_subtask_placeholder, parentTitle)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )
        },
        confirmButton = {
            TextButton(onClick = ::submit, enabled = text.isNotBlank()) { Text(stringResource(Res.string.common_add)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) }
        },
    )
}

/** ~16dp glyph for a small source mark. */
private val SourceMarkSize = 16.dp

/**
 * The small external-provenance mark for a tree row: GitHub or Google Calendar. GitHub's monochrome
 * Invertocat is tinted to the calm row ink (it reads as filigree, like the other glyphs); Google's "G"
 * keeps its four brand colours (rendered untinted via [Image] — the colour *is* the signal). It carries
 * its own TalkBack label since it's the sole cue of where the item came from. The brand drawable is
 * resolved per platform by [sourceMarkPainter].
 */
@Composable
internal fun SourceIndicator(source: ItemSource, modifier: Modifier = Modifier) {
    val painter = sourceMarkPainter(source)
    when (source) {
        ItemSource.GitHub ->
            Icon(
                painter = painter,
                contentDescription = stringResource(Res.string.tasks_source_from_github),
                tint = MaterialTheme.defernoColors.inkMuted,
                modifier = modifier.size(SourceMarkSize),
            )
        ItemSource.GoogleCalendar ->
            Image(
                painter = painter,
                contentDescription = stringResource(Res.string.tasks_source_from_google_calendar),
                modifier = modifier.size(SourceMarkSize),
            )
    }
}

/**
 * The brand drawable for a source mark, resolved from each platform's native resource system: Android
 * loads its vector drawable via `R.drawable` (the Robolectric screenshot harness isn't served a
 * dependency module's Compose resources), desktop/JVM loads the design-system Compose resource via
 * `Res.drawable`. Same artwork, packaged once per target (this module's `androidMain/res` and
 * `core:designsystem`'s `composeResources`).
 */
@Composable
internal expect fun sourceMarkPainter(source: ItemSource): Painter

/**
 * The contextual move-mode control (ADR-0034 decision 6, #228): **↑ ↓** reorder among siblings and
 * **‹ ›** outdent / indent, each acting live per press, plus **Done** to exit. An illegal direction is
 * greyed (the client-side "illegal targets prevented" guard, driven by [MoveMode]'s flags).
 */
@Composable
private fun MoveModeBar(
    move: MoveMode,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onIndent: () -> Unit,
    onOutdent: () -> Unit,
    onDone: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        // Edge-to-edge (ADR-0035 #3): a pinned bar consumes the bottom + horizontal system-bar inset itself,
        // mirroring Material 3's BottomAppBar — so Done + ↑↓‹› clear the nav bar (and a landscape cutout). The
        // Surface tonal background still fills behind the bar; only its content is inset. Empty inset on desktop.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MoveControl(glyph = "↑", description = stringResource(Res.string.tasks_move_up), enabled = move.canMoveUp, onClick = onMoveUp)
            MoveControl(glyph = "↓", description = stringResource(Res.string.tasks_move_down), enabled = move.canMoveDown, onClick = onMoveDown)
            MoveControl(glyph = "‹", description = stringResource(Res.string.tasks_move_outdent), enabled = move.canOutdent, onClick = onOutdent)
            MoveControl(glyph = "›", description = stringResource(Res.string.tasks_move_indent), enabled = move.canIndent, onClick = onIndent)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDone, modifier = Modifier.heightIn(min = MinTouchTarget)) { Text(stringResource(Res.string.common_done)) }
        }
    }
}

/** One direction control in the [MoveModeBar]: a glyph button labelled for TalkBack, greyed when illegal. */
@Composable
private fun MoveControl(glyph: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .heightIn(min = MinTouchTarget)
            .semantics {
                contentDescription = description
                if (!enabled) disabled()
            },
    ) {
        Text(text = glyph, style = MaterialTheme.typography.titleLarge, modifier = Modifier.clearAndSetSemantics {})
    }
}
