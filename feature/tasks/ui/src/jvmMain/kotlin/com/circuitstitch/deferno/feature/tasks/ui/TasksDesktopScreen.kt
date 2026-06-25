package com.circuitstitch.deferno.feature.tasks.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.circuitstitch.deferno.core.data.task.AttachmentUpload
import com.circuitstitch.deferno.feature.tasks.ItemTreeComponent
import com.circuitstitch.deferno.feature.tasks.TaskDetailComponent
import com.circuitstitch.deferno.feature.tasks.TasksComponent
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.nio.file.Files

/**
 * The Tasks screen, desktop edition — the desktop counterpart of the adaptive Android `TasksScreen` (#29).
 * Both render the component's panes as **1 or 2 panes by window size class** (ADR-0007 tier-2): Android via
 * M3 `ListDetailPaneScaffold`, desktop via `BoxWithConstraints` here. Since ADR-0034 the primary pane is the
 * nested **Item tree** ([TasksComponent.tree]) — the flat list + one-level drill pane are subsumed — and the
 * secondary pane is the lone Task [TasksComponent.detail].
 *
 * It is adaptive off the continuous available width (ADR-0008 G1 — never a device-type check): at
 * [TasksTwoPaneMinWidth]+ it shows two panes (tree + detail); narrower, it collapses to a single pane. The
 * component owns all state, so resizing across the breakpoint never drops what's open (G5). It reuses the
 * slice's shared commonMain atoms ([ItemTreeContent], [PaneHeader], the shared [TaskDetailContent], …).
 */
@Composable
fun TasksDesktopScreen(
    component: TasksComponent,
    modifier: Modifier = Modifier,
    // The in-tree "Search all your trees…" bar opens the global Search overlay. Desktop has no top-bar
    // search pill (that's the Android shell's Files-style chrome), so the inline bar IS the in-context
    // search entry — the shell threads the real openOverlay(Search) intent here (no-op default for tests).
    onSearch: () -> Unit = {},
) {
    val detailSlot by component.detail.subscribeAsState()
    val detail = detailSlot.child?.instance

    BoxWithConstraints(modifier.fillMaxSize()) {
        if (maxWidth >= TasksTwoPaneMinWidth) {
            // Two panes: the tree is always present on the left; the right pane is the detail when one is
            // open, else a "pick a task" placeholder.
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(TasksListPaneWidth).fillMaxHeight()) {
                    TreePane(component.tree, onSearch = onSearch)
                }
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    SecondaryPane(detail)
                }
            }
        } else {
            // One pane: render the detail when open, else the tree (the home state when nothing is open).
            if (detail != null) TaskDetailScreen(detail) else TreePane(component.tree, onSearch = onSearch)
        }
    }
}

/** Below this available width the desktop Tasks screen collapses to a single pane (ADR-0007 tier-2). */
internal val TasksTwoPaneMinWidth = 720.dp

/** Fixed width of the tree pane in the two-pane layout; the secondary pane takes the rest. */
private val TasksListPaneWidth = 360.dp

/** The right-hand pane in the two-pane layout: the Task detail when one is open, else a gentle placeholder. */
@Composable
private fun SecondaryPane(detail: TaskDetailComponent?) {
    if (detail != null) {
        TaskDetailScreen(detail)
    } else {
        EmptyState(
            title = "Nothing open",
            body = "Pick a task on the left to see its details here.",
        )
    }
}

/**
 * The Item-tree pane — a thin renderer of [ItemTreeComponent], reusing the shared [ItemTreeContent].
 *
 * Move undo (ADR-0034 decision 8, #230): the persistent "Undo move" long-press/keyboard menu entry
 * ([canUndo]/[onUndoMove]) plus a **top-anchored** "Moved · Undo" snackbar on every structural move —
 * the same two non-shake undo paths the Android [TaskListScreen] surfaces (shake-to-undo is omitted:
 * no accelerometer on desktop). Both revert through [ItemTreeComponent.undoLastMove].
 */
@Composable
private fun TreePane(
    component: ItemTreeComponent,
    modifier: Modifier = Modifier,
    onSearch: () -> Unit = {},
) {
    val state by component.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // One "Moved · Undo" snackbar per *structural* move (keyed on the move token so two indents in a row
    // each raise it); a plain reorder records an undoable but shows no snackbar (#230, parity with Android).
    LaunchedEffect(state.lastMove?.takeIf { it.structural }?.id) {
        if (state.lastMove?.structural != true) return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "Moved",
            actionLabel = "Undo",
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) component.undoLastMove()
    }

    Box(modifier.fillMaxSize()) {
        ItemTreeContent(
            rows = state.rows,
            isRefreshing = state.isRefreshing,
            onToggleExpand = component::onToggleExpand,
            onOpenDetail = component::onOpenDetail,
            onRefresh = component::onRefresh,
            onSearch = onSearch,
            moveMode = state.moveMode,
            onEnterMoveMode = component::onEnterMoveMode,
            onMoveUp = component::onMoveUp,
            onMoveDown = component::onMoveDown,
            onIndent = component::onIndent,
            onOutdent = component::onOutdent,
            onExitMoveMode = component::onExitMoveMode,
            canUndo = state.lastMove != null,
            onUndoMove = component::undoLastMove,
        )
        // Top-anchored, out of the way (ADR-0034 decision 8: the Material default is bottom, so align top).
        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.TopCenter))
    }
}

/**
 * The Task detail pane, desktop edition — a thin wrapper over the shared [TaskDetailContent] (title block,
 * working-state editor, and the four web-parity sections). It owns only the desktop-specific glue: an AWT
 * [FileDialog] attachment picker (file → bytes → [AttachmentUpload], the desktop twin of Android's SAF
 * picker — cf. FeedbackDesktopScreen). On-device-recording playback is Android-only (no on-device capture
 * on desktop, ADR-0027), so that section stays empty and its callbacks keep their no-op defaults.
 *
 * Public so the shell can also render it as a Plan-tap overlay (#51), not just inside the Tasks pane.
 */
@Composable
fun TaskDetailScreen(component: TaskDetailComponent, modifier: Modifier = Modifier) {
    val state by component.state.collectAsState()
    TaskDetailContent(
        state = state,
        modifier = modifier,
        onClose = component::onCloseClicked,
        onDelete = component::onDelete,
        onAddToPlan = component::onAddToPlanClicked,
        onSetWorkingState = component::onSetWorkingState,
        onSetDeadline = component::onSetDeadline,
        onSetLabels = component::onSetLabels,
        onToggleSubtask = component::onToggleSubtaskDone,
        onToggleSubtaskExpand = component::onToggleSubtaskExpand,
        onOpenSubtask = { component.onSubtaskClicked(it.id) },
        onAddSubtask = component::onAddSubtask,
        onPostComment = component::onPostComment,
        onEditComment = component::onEditComment,
        onDeleteComment = component::onDeleteComment,
        onAddAttachment = { pickAttachments()?.let(component::onAddAttachments) },
        onDeleteAttachment = component::onDeleteAttachment,
        onSetAttachmentCaption = component::onSetAttachmentCaption,
    )
}

// The web's attachment limits: at most 5 files per add, 25 MB each (parity with the Android picker).
private const val MaxAttachments = 5
private const val MaxAttachmentBytes = 25 * 1024 * 1024

/**
 * Open the modal AWT file picker and read the chosen files into [AttachmentUpload]s (name, probed MIME,
 * bytes), capped at [MaxAttachments] / [MaxAttachmentBytes] each. Returns null when nothing is picked (or
 * everything is filtered out) so the caller skips the empty add. Blocks the UI thread while the dialog is
 * open — the accepted desktop pattern (cf. FeedbackDesktopScreen).
 */
private fun pickAttachments(): List<AttachmentUpload>? {
    val dialog = FileDialog(null as Frame?, "Attach files", FileDialog.LOAD).apply {
        isMultipleMode = true
        isVisible = true // modal — blocks until the user picks or cancels
    }
    val uploads = dialog.files.orEmpty()
        .take(MaxAttachments)
        .mapNotNull(::readAttachmentUpload)
        .filter { it.bytes.size <= MaxAttachmentBytes }
    return uploads.ifEmpty { null }
}

/** Read a chosen [file] into an [AttachmentUpload] — name, probed MIME type, and bytes. */
private fun readAttachmentUpload(file: File): AttachmentUpload? {
    if (!file.isFile) return null
    val mime = runCatching { Files.probeContentType(file.toPath()) }.getOrNull() ?: "application/octet-stream"
    return AttachmentUpload(filename = file.name, contentType = mime, bytes = file.readBytes())
}
