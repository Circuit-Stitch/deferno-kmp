package com.circuitstitch.deferno.feature.tasks.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.circuitstitch.deferno.core.designsystem.theme.defernoColors
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.feature.tasks.ItemRow
import com.circuitstitch.deferno.feature.tasks.MoveMode

// The Tasks Item-tree renderer (ADR-0034, #227/#228): the cross-kind forest flattened to depth-indented
// rows in one LazyColumn, shared by the Android (TaskListScreen) and desktop (TasksDesktopScreen) primary
// pane. Stateless and platform-neutral; the component (DefaultItemTreeComponent) holds all logic — these
// only render the [ItemRow]s, forward taps, and drive the modal move mode. Handlers take their args from
// the ROW, never from a state snapshot (the component's StateFlow is WhileSubscribed — empty without a
// live subscriber).

/** Per-depth leading indent; the chevron gutter keeps a [chevronGutter] column so titles align. */
private val IndentPerDepth = 16.dp
private val chevronGutter = MinTouchTarget

/**
 * The Tasks Item tree: a header (with Refresh) over a `LazyColumn` of [rows]. Each parent row toggles its
 * fold on a chevron/body tap; a childless leaf's body is inert; the trailing `›` opens detail (ADR-0034
 * decision 7). A **long-press** lifts the row into [moveMode] (decision 6, #228): the lifted row is
 * highlighted, the rest calmed, and a bottom bar offers **↑ ↓ ‹ ›** (illegal directions greyed) + Done —
 * mirrored on the keyboard (Alt+↑/↓ reorder, Tab / Shift-Tab indent / outdent). Empty/refreshing states
 * mirror the calm copy of the other Tasks panes.
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

    Column(
        modifier = modifier
            .fillMaxSize()
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
                        else -> false
                    }
                }
            },
    ) {
        PaneHeader(
            title = "Tasks",
            actions = {
                TextButton(
                    onClick = onRefresh,
                    enabled = !isRefreshing,
                    modifier = Modifier.heightIn(min = MinTouchTarget),
                ) { Text("Refresh") }
            },
        )
        if (isRefreshing) {
            LoadingStrip(label = "Refreshing…")
        }
        if (rows.isEmpty() && !isRefreshing) {
            EmptyState(
                title = "No tasks yet",
                body = "When you add a task, it shows up here. One small step at a time.",
            )
        } else {
            LazyColumn(
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
                items(rows, key = { it.item.id }) { row ->
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
 * One depth-indented tree row. Outside move mode a parent's chevron + body tap toggle its fold, the
 * trailing `›` (a fixed, always-present target) opens detail, and a **long-press** opens a minimal menu
 * whose "Move" entry lifts the row into move mode (#228). In move mode taps are inert (the list goes
 * calm): the lifted row is highlighted, the rest dimmed. A collapsed parent with subtree counts shows a
 * `done/total` badge; a terminal (Done/Dropped/Archived) item is de-emphasized.
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
                    .padding(PaddingValues(start = IndentPerDepth * row.depth)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Chevron gutter: ▾/▸ for a parent, blank for a leaf so titles still align.
                Box(Modifier.size(chevronGutter), contentAlignment = Alignment.Center) {
                    if (row.hasChildren) {
                        Text(
                            text = if (row.isExpanded) "▾" else "▸",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = titleColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                )
                // Collapsed parent with server-computed counts → a done/total progress badge.
                if (row.hasChildren && !row.isExpanded && item.descendantTotal != null) {
                    val done = item.descendantDone ?: 0
                    val total = item.descendantTotal
                    Text(
                        text = "$done/$total",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                            .clearAndSetSemantics { contentDescription = "$done of $total done" },
                    )
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
                    Text(
                        text = "›",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clearAndSetSemantics {},
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
