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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.circuitstitch.deferno.core.designsystem.component.DashedAddButton
import com.circuitstitch.deferno.core.designsystem.component.DefernoIcons
import com.circuitstitch.deferno.core.designsystem.component.MonoMeta
import com.circuitstitch.deferno.core.designsystem.component.ProgressBarThin
import com.circuitstitch.deferno.core.designsystem.component.SearchBarDisplay
import com.circuitstitch.deferno.core.designsystem.component.SegmentedFilter
import com.circuitstitch.deferno.core.designsystem.theme.defernoColors
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.ItemSource
import com.circuitstitch.deferno.feature.tasks.ItemRow
import com.circuitstitch.deferno.feature.tasks.MoveMode

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

/** The calm in-list filter segments — local view state, not a component intent. "Active" hides terminals. */
private val TreeFilters = listOf("In today", "Active", "All")

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
                        )
                    }
                } else {
                    item(key = "everything-search") {
                        EverythingSearchFilter(
                            onSearch = onSearch,
                            showSearch = searchInList,
                            filterIndex = filterIndex,
                            onFilterSelect = { filterIndex = it },
                        )
                    }
                }
            }
            if (isRefreshing) {
                item(key = "refreshing") { LoadingStrip(label = "Refreshing…") }
            }
            if (visibleRows.isEmpty() && !isRefreshing) {
                item(key = "empty") {
                    EmptyState(
                        title = if (rows.isEmpty()) "No trees yet" else "Nothing to show here",
                        body = if (rows.isEmpty()) {
                            "When you add a tree, it shows up here. One small step at a time."
                        } else {
                            "Everything here is done. Switch to “All” to see it again."
                        },
                    )
                }
            } else {
                items(visibleRows, key = { it.item.id }) { row ->
                    ItemTreeRow(
                        row = row,
                        inMoveMode = moveMode != null,
                        isLifted = moveMode?.liftedId == row.item.id,
                        onToggleExpand = onToggleExpand,
                        onOpenDetail = onOpenDetail,
                        onEnterMoveMode = onEnterMoveMode,
                        canUndo = canUndo,
                        onUndoMove = onUndoMove,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            // "Add a tree" foot (#231) — only outside move mode (the list is calm during a move).
            if (moveMode == null) {
                item(key = "add-tree") {
                    DashedAddButton(
                        text = "Add a tree",
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
                    text = "Everything",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() },
                )
                MonoMeta(text = if (treeCount == 1) "1 tree" else "$treeCount trees")
            }
            TextButton(
                onClick = onRefresh,
                enabled = !isRefreshing,
                modifier = Modifier.heightIn(min = MinTouchTarget),
            ) { Text("Refresh") }
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
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Hosts with a top-bar search (Android) omit the inline bar; desktop keeps it (no top-bar dock).
            if (showSearch) SearchBarDisplay(placeholder = "Search all your trees…", onClick = onSearch)
            SegmentedFilter(options = TreeFilters, selectedIndex = filterIndex, onSelect = onFilterSelect)
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
    onToggleExpand: (String, Boolean) -> Unit,
    onOpenDetail: (String, ItemKind) -> Unit,
    onEnterMoveMode: (String) -> Unit,
    canUndo: Boolean,
    onUndoMove: () -> Unit,
) {
    val item = row.item
    val titleColor =
        if (item.isTerminal) MaterialTheme.defernoColors.inkMuted else MaterialTheme.colorScheme.onSurface
    var menuOpen by remember { mutableStateOf(false) }

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
                    row.isExpanded -> "Collapse ${item.title}"
                    else -> "Expand ${item.title}"
                },
                onLongClickLabel = "Move ${item.title}",
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
                        text = item.title,
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
                        MonoMeta(
                            text = "$done of $total",
                            modifier = Modifier.clearAndSetSemantics { contentDescription = "$done of $total done" },
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
                // A small external-provenance mark when the item was synced/created from GitHub or
                // Google Calendar (else absent — a native Deferno item). It rides ahead of the chevron,
                // inheriting the row's dim in move mode (the Row carries the alpha).
                item.source?.let { source ->
                    SourceIndicator(source, modifier = Modifier.padding(horizontal = 4.dp))
                }
                // The lone open-detail affordance: a fixed target, immune to title length (ADR-0034 dec. 7).
                // Inert in move mode (the list is calm). An icon-only control, so it carries its own
                // contentDescription for TalkBack; the glyph's own semantics are cleared so it isn't read twice.
                Box(
                    modifier = Modifier
                        .size(chevronGutter)
                        .then(if (inMoveMode) Modifier else Modifier.clickable { onOpenDetail(item.id, item.kind) })
                        .semantics { contentDescription = "Open ${item.title}" },
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

            // The minimal long-press menu (#228): "Move", plus "Undo move" when a Move is undoable (#230) —
            // the persistent, non-shake undo path (snackbar is transient, shake is optional/off-able). The
            // full kind-aware menu (Open · Pin · Move to… · Add to plan) is a deferred ADR-0034 fast-follow.
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Move") },
                    onClick = {
                        menuOpen = false
                        onEnterMoveMode(item.id)
                    },
                )
                if (canUndo) {
                    DropdownMenuItem(
                        text = { Text("Undo move") },
                        onClick = {
                            menuOpen = false
                            onUndoMove()
                        },
                    )
                }
            }
        }
    }
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
                contentDescription = "From GitHub",
                tint = MaterialTheme.defernoColors.inkMuted,
                modifier = modifier.size(SourceMarkSize),
            )
        ItemSource.GoogleCalendar ->
            Image(
                painter = painter,
                contentDescription = "From Google Calendar",
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
            MoveControl(glyph = "↑", description = "Move up", enabled = move.canMoveUp, onClick = onMoveUp)
            MoveControl(glyph = "↓", description = "Move down", enabled = move.canMoveDown, onClick = onMoveDown)
            MoveControl(glyph = "‹", description = "Outdent", enabled = move.canOutdent, onClick = onOutdent)
            MoveControl(glyph = "›", description = "Indent", enabled = move.canIndent, onClick = onIndent)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDone, modifier = Modifier.heightIn(min = MinTouchTarget)) { Text("Done") }
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
