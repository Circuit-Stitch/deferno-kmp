package com.circuitstitch.deferno.feature.tasks.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.circuitstitch.deferno.core.designsystem.theme.defernoColors
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.feature.tasks.ItemRow

// The Tasks Item-tree renderer (ADR-0034, #227): the cross-kind forest flattened to depth-indented rows
// in one LazyColumn, shared by the Android (TaskListScreen) and desktop (TasksDesktopScreen) primary pane.
// Stateless and platform-neutral; the component (DefaultItemTreeComponent) holds all logic — these only
// render the [ItemRow]s and forward taps. Handlers take their args from the ROW, never from a state
// snapshot (the component's StateFlow is WhileSubscribed — empty without a live subscriber).

/** Per-depth leading indent; the chevron gutter keeps a [chevronGutter] column so titles align. */
private val IndentPerDepth = 16.dp
private val chevronGutter = MinTouchTarget

/**
 * The Tasks Item tree: a header (with Refresh) over a `LazyColumn` of [rows]. Each parent row toggles its
 * fold on a chevron/body tap; a childless leaf's body is inert; the trailing `›` opens detail (ADR-0034
 * decision 7). Empty/refreshing states mirror the calm copy of the other Tasks panes.
 */
@Composable
internal fun ItemTreeContent(
    rows: List<ItemRow>,
    isRefreshing: Boolean,
    onToggleExpand: (id: String, currentlyExpanded: Boolean) -> Unit,
    onOpenDetail: (id: String, kind: ItemKind) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
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
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(rows, key = { it.item.id }) { row ->
                    ItemTreeRow(row = row, onToggleExpand = onToggleExpand, onOpenDetail = onOpenDetail)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

/**
 * One depth-indented tree row. A parent's chevron + body tap toggle its fold; the trailing `›` (a fixed,
 * always-present target) opens detail. A collapsed parent with server-computed subtree counts shows a
 * `done/total` badge; a terminal (Done/Dropped/Archived) item is de-emphasized.
 */
@Composable
private fun ItemTreeRow(
    row: ItemRow,
    onToggleExpand: (String, Boolean) -> Unit,
    onOpenDetail: (String, ItemKind) -> Unit,
) {
    val item = row.item
    val titleColor =
        if (item.isTerminal) MaterialTheme.defernoColors.inkMuted else MaterialTheme.colorScheme.onSurface
    // Only a parent row toggles; a childless leaf's body is inert (ADR-0034 decision 7). The trailing `›`
    // has its own clickable inside this one, so a tap there opens detail rather than toggling.
    val bodyToggle =
        if (row.hasChildren) {
            Modifier.clickable(
                onClickLabel = if (row.isExpanded) "Collapse ${item.title}" else "Expand ${item.title}",
            ) { onToggleExpand(item.id, row.isExpanded) }
        } else {
            Modifier
        }

    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .then(bodyToggle)
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
            // An icon-only control, so it carries its own contentDescription for TalkBack; the glyph's own
            // semantics are cleared so it isn't read twice.
            Box(
                modifier = Modifier
                    .size(chevronGutter)
                    .clickable { onOpenDetail(item.id, item.kind) }
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
    }
}
