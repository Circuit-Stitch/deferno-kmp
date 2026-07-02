package com.circuitstitch.deferno.feature.tasks.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.circuitstitch.deferno.core.designsystem.component.DefernoIcons
import com.circuitstitch.deferno.core.designsystem.component.MonoMeta
import com.circuitstitch.deferno.core.designsystem.component.ProgressBarThin
import com.circuitstitch.deferno.core.designsystem.component.TreeChip
import com.circuitstitch.deferno.core.designsystem.resources.Res
import com.circuitstitch.deferno.core.designsystem.resources.common_cancel
import com.circuitstitch.deferno.core.designsystem.resources.common_cannot_be_undone
import com.circuitstitch.deferno.core.designsystem.resources.common_delete
import com.circuitstitch.deferno.core.designsystem.resources.common_due
import com.circuitstitch.deferno.core.designsystem.resources.common_kind_task
import com.circuitstitch.deferno.core.designsystem.resources.common_status_done
import com.circuitstitch.deferno.core.designsystem.resources.common_status_in_progress
import com.circuitstitch.deferno.core.designsystem.resources.common_status_in_review
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_delete_confirm_title
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_loading
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_more_actions
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_no_description
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_not_found_body
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_not_found_title
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_set_working_state_a11y
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_subtask_progress_a11y
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_title
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_working_state_current_a11y
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_working_state_heading
import com.circuitstitch.deferno.core.designsystem.resources.tasks_menu_add_subtask
import com.circuitstitch.deferno.core.designsystem.resources.tasks_menu_add_to_plan
import com.circuitstitch.deferno.core.designsystem.resources.tasks_menu_break_this_down
import com.circuitstitch.deferno.core.designsystem.resources.tasks_progress_done
import com.circuitstitch.deferno.core.designsystem.resources.tasks_set_aside
import com.circuitstitch.deferno.core.designsystem.resources.tasks_working_state_open
import com.circuitstitch.deferno.core.designsystem.theme.defernoColors
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.WorkingState
import com.circuitstitch.deferno.feature.tasks.OnDeviceAttachment
import com.circuitstitch.deferno.feature.tasks.TaskDetailState
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.stringResource

/**
 * The Task detail body (#27/#231) — platform-neutral Compose (Android + desktop), the shared half of the
 * Task detail. A thin, stateless renderer of [TaskDetailState] + the [com.circuitstitch.deferno.feature.tasks.TaskDetailComponent]
 * write seams: the title block (kind chip · headline · ref/due · subtask progress), the working-state
 * editor, the description, add-to-plan, and the four web-parity sections (Properties · Subtasks ·
 * Attachments · Activity/Comments — see [TaskDetailSections]).
 *
 * The per-platform `TaskDetailScreen` wrappers own only what differs: the file picker that produces an
 * [com.circuitstitch.deferno.core.data.task.AttachmentUpload] (Android SAF / desktop AWT) and any
 * on-device-recording playback (Android only). Everything visual lives here so the two platforms render
 * identically (ADR-0004 #27).
 */
@Composable
internal fun TaskDetailContent(
    state: TaskDetailState,
    onClose: () -> Unit,
    onDelete: () -> Unit = {},
    onAddToPlan: () -> Unit,
    onSetWorkingState: (WorkingState) -> Unit,
    onSetDeadline: (LocalDate?) -> Unit,
    onSetLabels: (List<String>) -> Unit,
    onToggleSubtask: (Task) -> Unit,
    onToggleSubtaskExpand: (id: String, currentlyExpanded: Boolean) -> Unit = { _, _ -> },
    onOpenSubtask: (Task) -> Unit,
    onAddSubtask: (String) -> Unit,
    onPostComment: (String) -> Unit,
    onEditComment: (String, String) -> Unit,
    onDeleteComment: (String) -> Unit,
    onAddAttachment: () -> Unit,
    onDeleteAttachment: (String) -> Unit,
    onSetAttachmentCaption: (String, String?) -> Unit,
    onDeleteOnDeviceAttachment: (String) -> Unit = {},
    onPlayOnDeviceAttachment: (OnDeviceAttachment) -> Unit = {},
    // "Break this down" (Deferno#525): start the on-device impediment flow over this Task. Optional — only
    // the platforms that render the Breakdown surface (Android) pass it; desktop leaves it null (no engine,
    // so the kebab hides the item rather than opening an empty overlay).
    onBreakdown: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val task = state.task
    // Opaque background: this screen renders as an overlay above the Plan (#51), so it must paint a
    // full surface and consume taps — otherwise the Plan behind bleeds through (Surface does both).
    // The Surface fills edge-to-edge (cream under the translucent bars); the content insets past the
    // system bars so the header clears the status-bar clock and the body clears the nav bar (empty
    // insets on desktop, where the window owns its frame).
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
            // "See the trees" (#231): the prominent title now lives in the body title block (kind chip +
            // headline), so the header is the calm "Details" label — matching the design and avoiding a
            // duplicate title node. In single-pane the leading control returns to the list ("Back").
            PaneHeader(title = stringResource(Res.string.tasks_detail_title), onBack = onClose)
            if (state.isHydrating) {
                LoadingStrip(label = stringResource(Res.string.tasks_detail_loading))
            }
            when {
                task == null && !state.isHydrating -> EmptyState(
                    title = stringResource(Res.string.tasks_detail_not_found_title),
                    body = stringResource(Res.string.tasks_detail_not_found_body),
                )
                task == null -> Unit // brief hydrating gap before the row is observed; the bar above shows it
                else -> TaskBody(
                    task = task,
                    state = state,
                    onDelete = onDelete,
                    onAddToPlan = onAddToPlan,
                    onSetWorkingState = onSetWorkingState,
                    onSetDeadline = onSetDeadline,
                    onSetLabels = onSetLabels,
                    onToggleSubtask = onToggleSubtask,
                    onToggleSubtaskExpand = onToggleSubtaskExpand,
                    onOpenSubtask = onOpenSubtask,
                    onAddSubtask = onAddSubtask,
                    onPostComment = onPostComment,
                    onEditComment = onEditComment,
                    onDeleteComment = onDeleteComment,
                    onAddAttachment = onAddAttachment,
                    onDeleteAttachment = onDeleteAttachment,
                    onSetAttachmentCaption = onSetAttachmentCaption,
                    onDeleteOnDeviceAttachment = onDeleteOnDeviceAttachment,
                    onPlayOnDeviceAttachment = onPlayOnDeviceAttachment,
                    onBreakdown = onBreakdown,
                )
            }
        }
    }
}

@Composable
private fun TaskBody(
    task: Task,
    state: TaskDetailState,
    onDelete: () -> Unit,
    onAddToPlan: () -> Unit,
    onSetWorkingState: (WorkingState) -> Unit,
    onSetDeadline: (LocalDate?) -> Unit,
    onSetLabels: (List<String>) -> Unit,
    onToggleSubtask: (Task) -> Unit,
    onToggleSubtaskExpand: (id: String, currentlyExpanded: Boolean) -> Unit,
    onOpenSubtask: (Task) -> Unit,
    onAddSubtask: (String) -> Unit,
    onPostComment: (String) -> Unit,
    onEditComment: (String, String) -> Unit,
    onDeleteComment: (String) -> Unit,
    onAddAttachment: () -> Unit,
    onDeleteAttachment: (String) -> Unit,
    onSetAttachmentCaption: (String, String?) -> Unit,
    onDeleteOnDeviceAttachment: (String) -> Unit,
    onPlayOnDeviceAttachment: (OnDeviceAttachment) -> Unit,
    onBreakdown: (() -> Unit)?,
) {
    // Reset the scroll to the top when drilling into a different task (key by id) — the detail composable
    // is reused across the parent→subtask navigation, so an unkeyed scroll state would carry the parent's
    // scroll position into the child and open it past its title (#231).
    val scrollState = remember(task.id) { ScrollState(0) }
    // The add-subtask field lives far down in the body; the kebab's "Add subtask" requests focus on it,
    // which auto-scrolls it into view and pops the keyboard (it's always composed — verticalScroll is eager).
    val addSubtaskFocus = remember(task.id) { FocusRequester() }
    var confirmDelete by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // The "Everything in one place" title block (#231): a kind chip, the title, then a mono meta line
        // (ref · due · subtask count). The kind here is always Task — this detail hydrates a Task — but it
        // still wears the kind marker so the four kinds read consistently across the app. The ⋮ kebab rides
        // top-right of the block (body-level, so it works for both the Tasks pane and the Plan drill, #262).
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.weight(1f)) {
                DetailTitleBlock(task = task, subtaskDone = state.subtaskDone, subtaskTotal = state.subtaskTotal)
            }
            TaskOverflowMenu(
                onAddSubtask = { addSubtaskFocus.requestFocus() },
                onDrop = { onSetWorkingState(WorkingState.Dropped) },
                onDelete = { confirmDelete = true },
                onBreakdown = onBreakdown,
            )
        }
        WorkingStateEditor(current = task.workingState, onSetWorkingState = onSetWorkingState)

        val description = task.description
        when {
            !description.isNullOrBlank() -> Text(description, style = MaterialTheme.typography.bodyLarge)
            !state.isHydrating -> Text(
                text = stringResource(Res.string.tasks_detail_no_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.defernoColors.inkMuted,
            )
        }

        Button(
            onClick = onAddToPlan,
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget),
        ) { Text(stringResource(Res.string.tasks_menu_add_to_plan)) }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        PropertiesSection(
            task = task,
            onSetDeadline = onSetDeadline,
            onSetLabels = onSetLabels,
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SubtasksSection(
            rows = state.subtaskRows,
            done = state.subtaskDone,
            total = state.subtaskTotal,
            onToggleDone = onToggleSubtask,
            onToggleExpand = onToggleSubtaskExpand,
            onOpen = onOpenSubtask,
            onAddSubtask = onAddSubtask,
            addSubtaskFocus = addSubtaskFocus,
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        AttachmentsSection(
            attachments = state.attachments,
            isUploading = state.isUploadingAttachment,
            onAddClick = onAddAttachment,
            onDelete = onDeleteAttachment,
            onSetCaption = onSetAttachmentCaption,
            onDeviceAttachments = state.onDeviceAttachments,
            onDeleteOnDevice = onDeleteOnDeviceAttachment,
            onPlayOnDevice = onPlayOnDeviceAttachment,
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        CommentsSection(
            comments = state.comments,
            currentUserId = state.currentUserId,
            loading = state.commentsLoading,
            error = state.commentsError,
            isPosting = state.isPostingComment,
            onPost = onPostComment,
            onEdit = onEditComment,
            onDelete = onDeleteComment,
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(Res.string.tasks_detail_delete_confirm_title)) },
            text = { Text(stringResource(Res.string.common_cannot_be_undone)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text(stringResource(Res.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(Res.string.common_cancel)) }
            },
        )
    }
}

/**
 * The detail's ⋮ more-actions kebab (#262): Add subtask (focuses the always-present add field), Set aside
 * (Dropped), and the destructive Delete (the caller gates it behind a confirm). Icon-only trigger, so it
 * carries its own contentDescription for TalkBack.
 */
@Composable
private fun TaskOverflowMenu(
    onAddSubtask: () -> Unit,
    onDrop: () -> Unit,
    onDelete: () -> Unit,
    onBreakdown: (() -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(imageVector = DefernoIcons.MoreVert, contentDescription = stringResource(Res.string.tasks_detail_more_actions))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.tasks_menu_add_subtask)) },
                onClick = { expanded = false; onAddSubtask() },
            )
            // "Break this down" (Deferno#525) — the on-device impediment flow. Only where a host wired it
            // (Android); desktop has no engine, so the item is absent rather than opening an empty overlay.
            if (onBreakdown != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.tasks_menu_break_this_down)) },
                    onClick = { expanded = false; onBreakdown() },
                )
            }
            // The app's vocabulary for WorkingState.Dropped is "Set aside" (the chip below + SearchScreen),
            // so the kebab uses the same word rather than web's "Drop" — one term per concept.
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.tasks_set_aside)) },
                onClick = { expanded = false; onDrop() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.common_delete)) },
                onClick = { expanded = false; onDelete() },
            )
        }
    }
}

/**
 * The "Everything in one place" title block (#231): the kind as a [TreeChip] (filled, in the kind's
 * colour), the Task title at headline rank, a mono meta line (human ref + due day), and — when the Task
 * parents subtasks — an **overall-status** progress bar with a `{done} of {total} done` label right
 * under the title (#231), so the whole's completion is legible above the fold without scrolling to the
 * Subtasks section. Calm, low-overwhelm; the ref keeps IBM Plex Mono.
 */
@Composable
private fun DetailTitleBlock(task: Task, subtaskDone: Int, subtaskTotal: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TreeChip(
            text = kindLabel(ItemKind.Task),
            filled = true,
            container = kindColor(ItemKind.Task),
            content = MaterialTheme.colorScheme.onPrimary,
            semanticLabel = stringResource(Res.string.common_kind_task),
        )
        Text(
            // A dimmed `[GitHub#N]` ref prefix for a GitHub-imported issue; the tracker owns the title
            // itself, so the prefix is derived client-side, not stored.
            text = titleWithExternalRef(
                title = task.title,
                source = task.external?.source,
                externalId = task.external?.id,
                prefixColor = MaterialTheme.defernoColors.inkMuted,
            ),
            style = MaterialTheme.typography.headlineSmall,
        )
        val meta = buildList {
            task.ref?.let { add(it) }
            task.completeBy?.let { add(stringResource(Res.string.common_due, it.toDisplayDate())) }
        }
        if (meta.isNotEmpty()) {
            MonoMeta(text = meta.joinToString("  ·  "))
        }
        if (subtaskTotal > 0) {
            // A "{done} of {total} done" label over a full-width bar — the same proven pattern as the
            // Subtasks section (a bare weighted Row mis-measured the title block here).
            val subtaskProgressA11y =
                stringResource(Res.string.tasks_detail_subtask_progress_a11y, subtaskDone, subtaskTotal)
            MonoMeta(
                text = stringResource(Res.string.tasks_progress_done, subtaskDone, subtaskTotal),
                modifier = Modifier.padding(top = 2.dp).clearAndSetSemantics {
                    contentDescription = subtaskProgressA11y
                },
            )
            ProgressBarThin(
                fraction = subtaskDone.toFloat() / subtaskTotal,
                modifier = Modifier.fillMaxWidth().clearAndSetSemantics {},
            )
        }
    }
}

/**
 * The interactive working-state control on the Tasks detail (#73): a selectable chip per
 * [WorkingState] with the [current] one selected, so the user can move the Task across all five states.
 * Tapping a chip forwards [onSetWorkingState]; the component issues the one lifecycle Command that
 * reaches that state and the change applies optimistically + offline-first (ADR-0001/0007). Re-tapping
 * the current state is a clean no-op (the executor's pre-flight gate rejects it before any write).
 *
 * Plain labels, no jargon, large touch targets, and a self-describing TalkBack semantic per chip
 * (design-principles.md); colour is reinforcement, never the sole signal.
 */
@Composable
internal fun WorkingStateEditor(
    current: WorkingState,
    onSetWorkingState: (WorkingState) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(Res.string.tasks_detail_working_state_heading),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.defernoColors.inkMuted,
            modifier = Modifier.semantics { heading() },
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WorkingState.entries.forEach { state ->
                val label = workingStateLabel(state)
                val selected = state == current
                val chipA11y = if (selected) {
                    stringResource(Res.string.tasks_detail_working_state_current_a11y, label)
                } else {
                    stringResource(Res.string.tasks_detail_set_working_state_a11y, label)
                }
                FilterChip(
                    selected = selected,
                    onClick = { onSetWorkingState(state) },
                    label = { Text(label) },
                    modifier = Modifier.semantics {
                        contentDescription = chipA11y
                    },
                )
            }
        }
    }
}

/** The plain, non-shaming label for each [WorkingState] (design-principles.md: no jargon, no shaming). */
@Composable
internal fun workingStateLabel(state: WorkingState): String = stringResource(
    when (state) {
        WorkingState.Open -> Res.string.tasks_working_state_open
        WorkingState.InProgress -> Res.string.common_status_in_progress
        WorkingState.InReview -> Res.string.common_status_in_review
        WorkingState.Done -> Res.string.common_status_done
        WorkingState.Dropped -> Res.string.tasks_set_aside
    },
)
