package com.circuitstitch.deferno.feature.tasks.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.circuitstitch.deferno.core.designsystem.component.DefernoIcons
import com.circuitstitch.deferno.core.designsystem.component.MonoMeta
import com.circuitstitch.deferno.core.designsystem.component.ProgressBarThin
import com.circuitstitch.deferno.core.designsystem.resources.Res
import com.circuitstitch.deferno.core.designsystem.resources.common_cancel
import com.circuitstitch.deferno.core.designsystem.resources.common_cannot_be_undone
import com.circuitstitch.deferno.core.designsystem.resources.common_delete
import com.circuitstitch.deferno.core.designsystem.resources.common_due
import com.circuitstitch.deferno.core.designsystem.resources.common_open_named_cd
import com.circuitstitch.deferno.core.designsystem.resources.common_status_done
import com.circuitstitch.deferno.core.designsystem.resources.common_status_in_progress
import com.circuitstitch.deferno.core.designsystem.resources.common_status_in_review
import com.circuitstitch.deferno.core.designsystem.resources.new_notes_label
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_delete_confirm_title
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_loading
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_more_actions
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_no_description
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_not_found_body
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_not_found_title
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_subtask_progress_a11y
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_tab_comments
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_tab_history
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_tab_info
import com.circuitstitch.deferno.core.designsystem.resources.tasks_menu_add_subtask
import com.circuitstitch.deferno.core.designsystem.resources.tasks_menu_add_to_plan
import com.circuitstitch.deferno.core.designsystem.resources.tasks_menu_break_this_down
import com.circuitstitch.deferno.core.designsystem.resources.tasks_progress_done
import com.circuitstitch.deferno.core.designsystem.resources.tasks_working_state_open
import com.circuitstitch.deferno.core.designsystem.resources.tasks_set_aside
import com.circuitstitch.deferno.core.designsystem.theme.defernoColors
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.WorkingState
import com.circuitstitch.deferno.feature.tasks.ActivityItem
import com.circuitstitch.deferno.feature.tasks.OnDeviceAttachment
import com.circuitstitch.deferno.feature.tasks.ParentSummary
import com.circuitstitch.deferno.feature.tasks.TaskDetailState
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.stringResource

/**
 * The Task detail body (#27/#231/ADR-0044) — platform-neutral Compose (Android + desktop), the shared half
 * of the Task detail. A thin, stateless renderer of [TaskDetailState] + the
 * [com.circuitstitch.deferno.feature.tasks.TaskDetailComponent] write seams. Since ADR-0044 the screen has a
 * **single heading** — the [ConnectedParentHeader] (the immediate parent node drawn thread-connected above
 * the current item's title block) — and the body is **three tabs**: `Info` · `Comments` · `History`. The
 * redundant `PaneHeader("Details")` is gone; back is owned by the shell (compact) / the two-pane close
 * affordance (a later slice), not this body.
 *
 * The per-platform `TaskDetailScreen` wrappers own only what differs: the file picker that produces an
 * [com.circuitstitch.deferno.core.data.task.AttachmentUpload] (Android SAF / desktop AWT) and any
 * on-device-recording playback (Android only). Everything visual lives here so the two platforms render
 * identically (ADR-0004 #27).
 */
@Composable
internal fun TaskDetailContent(
    state: TaskDetailState,
    onDelete: () -> Unit = {},
    onAddToPlan: () -> Unit,
    onSetWorkingState: (WorkingState) -> Unit,
    onSetDeadline: (LocalDate?) -> Unit,
    onSetLabels: (List<String>) -> Unit,
    onToggleSubtask: (Task) -> Unit,
    onToggleSubtaskExpand: (id: String, currentlyExpanded: Boolean) -> Unit = { _, _ -> },
    onOpenSubtask: (Task) -> Unit,
    onAddSubtask: (String) -> Unit,
    onSetHideDoneSubtasks: (Boolean) -> Unit = {},
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
    // Push the immediate parent's detail (ADR-0044): the connected-parent node's tap. The wrapper reuses
    // the component's `onSubtaskClicked(parent.id)` — back returns. No-op default for callers without a parent.
    onOpenParent: () -> Unit = {},
    // ADR-0044: whether the body's connected-header renders its own ⋮ overflow. False on the compact
    // single-pane Tasks detail, where the shell's drilled bar owns the overflow (else it doubles).
    showHeaderOverflow: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val task = state.task
    // Opaque background: this screen renders as an overlay above the Plan (#51), so it must paint a full
    // surface and consume taps — otherwise the Plan behind bleeds through (Surface does both). The Surface
    // fills edge-to-edge (cream under the translucent bars); the content insets past the system bars.
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        // Only the bottom nav-bar inset here — the shell top bar (compact drilled / two-pane) and the desktop
        // host already sit below the status bar, so padding systemBars again opened a large gap above the
        // connected-parent header (ADR-0044). The bottom inset still keeps scroll content clear of the nav bar.
        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars)) {
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
                    onSetHideDoneSubtasks = onSetHideDoneSubtasks,
                    onPostComment = onPostComment,
                    onEditComment = onEditComment,
                    onDeleteComment = onDeleteComment,
                    onAddAttachment = onAddAttachment,
                    onDeleteAttachment = onDeleteAttachment,
                    onSetAttachmentCaption = onSetAttachmentCaption,
                    onDeleteOnDeviceAttachment = onDeleteOnDeviceAttachment,
                    onPlayOnDeviceAttachment = onPlayOnDeviceAttachment,
                    onBreakdown = onBreakdown,
                    onOpenParent = onOpenParent,
                    showHeaderOverflow = showHeaderOverflow,
                )
            }
        }
    }
}

/** The Task detail's three body tabs (ADR-0044): Info (default) · Comments · History. */
private enum class DetailTab { Info, Comments, History }

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
    onSetHideDoneSubtasks: (Boolean) -> Unit,
    onPostComment: (String) -> Unit,
    onEditComment: (String, String) -> Unit,
    onDeleteComment: (String) -> Unit,
    onAddAttachment: () -> Unit,
    onDeleteAttachment: (String) -> Unit,
    onSetAttachmentCaption: (String, String?) -> Unit,
    onDeleteOnDeviceAttachment: (String) -> Unit,
    onPlayOnDeviceAttachment: (OnDeviceAttachment) -> Unit,
    onBreakdown: (() -> Unit)?,
    onOpenParent: () -> Unit,
    showHeaderOverflow: Boolean,
) {
    // Reset the scroll to the top when drilling into a different task (key by id) — the detail composable
    // is reused across the parent→subtask navigation, so an unkeyed scroll state would carry the parent's
    // scroll position into the child and open it past its title (#231).
    val scrollState = remember(task.id) { ScrollState(0) }
    // The add-subtask field lives far down in the Info tab; the kebab's "Add subtask" (and the drilled
    // overflow's reveal token) request focus on it, which auto-scrolls it into view and pops the keyboard.
    val addSubtaskFocus = remember(task.id) { FocusRequester() }
    var confirmDelete by remember { mutableStateOf(false) }
    var tab by remember(task.id) { mutableStateOf(DetailTab.Info) }
    var showStatusPicker by remember { mutableStateOf(false) }
    // A local reveal counter for the body kebab's "Add subtask" (the drilled overflow drives the same via
    // [TaskDetailState.revealAddSubtaskComposer]). Both switch to the Info tab and focus the add field.
    var localAddSubtaskReveal by remember(task.id) { mutableStateOf(0) }

    // The component-driven reveal (drilled overflow → onAddSubtaskRequested bumps the token): switch to Info
    // so the always-composed add-subtask field is present for the focus request below (skip the initial 0).
    LaunchedEffect(state.revealAddSubtaskComposer) {
        if (state.revealAddSubtaskComposer > 0) tab = DetailTab.Info
    }

    Column(Modifier.fillMaxSize()) {
        // The single heading (ADR-0044): the immediate parent node (when any) drawn thread-connected above
        // the current item's title block, with the ⋮ overflow riding top-right.
        ConnectedParentHeader(
            parent = state.parent,
            task = task,
            subtaskDone = state.subtaskDone,
            subtaskTotal = state.subtaskTotal,
            onOpenParent = onOpenParent,
            overflow = if (showHeaderOverflow) {
                {
                    TaskOverflowMenu(
                        onAddSubtask = { tab = DetailTab.Info; localAddSubtaskReveal++ },
                        onDelete = { confirmDelete = true },
                        onBreakdown = onBreakdown,
                    )
                }
            } else {
                null
            },
        )
        val tabs = listOf(
            DetailTab.Info to stringResource(Res.string.tasks_detail_tab_info),
            DetailTab.Comments to stringResource(Res.string.tasks_detail_tab_comments),
            DetailTab.History to stringResource(Res.string.tasks_detail_tab_history),
        )
        TabRow(selectedTabIndex = tab.ordinal) {
            tabs.forEach { (t, label) ->
                Tab(selected = tab == t, onClick = { tab = t }, text = { Text(label) })
            }
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (tab) {
                DetailTab.Info -> {
                    // Reveal the inline add-subtask field on request (kebab / drilled overflow). This effect
                    // lives inside the Info branch so the field is composed when the focus is requested; the
                    // both-zero initial state is skipped so opening the tab never pops the keyboard.
                    LaunchedEffect(state.revealAddSubtaskComposer, localAddSubtaskReveal) {
                        if (state.revealAddSubtaskComposer > 0 || localAddSubtaskReveal > 0) {
                            addSubtaskFocus.requestFocus()
                        }
                    }
                    // NOTES: a caps section header over the description, or a muted "no description" once
                    // hydration settles. The header hides only during the brief pre-hydration gap.
                    val description = task.description
                    if (!description.isNullOrBlank() || !state.isHydrating) {
                        SectionHeader(stringResource(Res.string.new_notes_label))
                    }
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

                    PropertiesSection(
                        task = task,
                        onSetDeadline = onSetDeadline,
                        onSetLabels = onSetLabels,
                        onStatusRowClick = { showStatusPicker = true },
                        ownerGroupCount = state.ownerGroupCount,
                        attachments = state.attachments,
                        isUploadingAttachment = state.isUploadingAttachment,
                        onAddAttachment = onAddAttachment,
                        onDeleteAttachment = onDeleteAttachment,
                        onSetAttachmentCaption = onSetAttachmentCaption,
                        onDeviceAttachments = state.onDeviceAttachments,
                        onDeleteOnDeviceAttachment = onDeleteOnDeviceAttachment,
                        onPlayOnDeviceAttachment = onPlayOnDeviceAttachment,
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
                        hideDone = state.hideDoneSubtasks,
                        onSetHideDone = onSetHideDoneSubtasks,
                        addSubtaskFocus = addSubtaskFocus,
                    )
                }
                DetailTab.Comments -> CommentsSection(
                    comments = state.activity.filterIsInstance<ActivityItem.Comment>(),
                    currentUserId = state.currentUserId,
                    loading = state.commentsLoading,
                    isPosting = state.isPostingComment,
                    onPost = onPostComment,
                    onEdit = onEditComment,
                    onDelete = onDeleteComment,
                )
                DetailTab.History -> HistorySection(
                    history = state.activity.filterIsInstance<ActivityItem.HistoryEvent>(),
                    loading = state.commentsLoading,
                )
            }
        }
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

    // The read-only journey indicator is info-only; state changes move to this picker sheet, opened by
    // tapping the STATUS row (ADR-0044). Selecting a state forwards the one lifecycle Command + dismisses.
    if (showStatusPicker) {
        StatusPickerSheet(
            current = task.workingState,
            onSelect = { onSetWorkingState(it); showStatusPicker = false },
            onDismiss = { showStatusPicker = false },
        )
    }
}

/**
 * The connected-parent header (ADR-0044) — the screen's single heading. When [parent] is non-null it draws a
 * muted, tappable parent node threaded by a **curved amber branch** down into the current item's node + title
 * block (a calm git-graph connector, replacing the old "TASK" kind chips); tapping the parent row pushes its
 * detail via [onOpenParent] (Back returns). With no parent the item stands alone. The ⋮ [overflow] rides
 * top-right of the block.
 */
@Composable
internal fun ConnectedParentHeader(
    parent: ParentSummary?,
    task: Task,
    subtaskDone: Int,
    subtaskTotal: Int,
    onOpenParent: () -> Unit,
    overflow: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            if (parent != null) {
                // The parent node + its title (muted, tappable) — the branch's upper node descends from here.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .heightIn(min = MinTouchTarget)
                        .clickable(onClickLabel = stringResource(Res.string.common_open_named_cd, parent.title)) { onOpenParent() },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BranchCell(current = false)
                    Text(
                        text = parent.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.defernoColors.inkMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    shortRef(parent.ref)?.let { MonoMeta(text = it) }
                }
                // The current item's node (the branch's lower node) beside its title block.
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.Top) {
                    BranchCell(current = true)
                    DetailTitleBlock(task, subtaskDone, subtaskTotal, Modifier.weight(1f))
                }
            } else {
                DetailTitleBlock(task, subtaskDone, subtaskTotal)
            }
        }
        overflow?.invoke()
    }
}

/**
 * One column of the connected-parent branch (ADR-0044). The **parent** cell draws the upper node and a line
 * descending to the row's bottom; the **current** cell continues that line from the top and lands a rounded
 * elbow in the slightly-indented lower node — a calm git-graph thread in the Task accent. Both cells share the
 * same width so the parent and current titles stay left-aligned.
 */
@Composable
private fun BranchCell(current: Boolean) {
    val color = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .width(BranchGutterWidth)
            .fillMaxHeight()
            .drawBehind {
                val parentX = BranchParentCx.toPx()
                val currentX = BranchCurrentCx.toPx()
                val r = BranchNodeRadius.toPx()
                val stroke = BranchStroke.toPx()
                if (current) {
                    val cy = BranchCurrentCy.toPx()
                    val elbow = BranchElbowRadius.toPx()
                    drawPath(
                        Path().apply {
                            moveTo(parentX, 0f)
                            lineTo(parentX, cy - elbow)
                            quadraticTo(parentX, cy, parentX + elbow, cy)
                            lineTo(currentX, cy)
                        },
                        color,
                        style = Stroke(width = stroke),
                    )
                    drawCircle(color, r, Offset(currentX, cy))
                } else {
                    val cy = size.height / 2f
                    drawLine(color, Offset(parentX, cy), Offset(parentX, size.height), stroke)
                    drawCircle(color, r, Offset(parentX, cy))
                }
            },
    )
}

private val BranchGutterWidth = 26.dp
private val BranchParentCx = 9.dp
private val BranchCurrentCx = 18.dp
private val BranchNodeRadius = 5.dp
private val BranchStroke = 1.6.dp
private val BranchElbowRadius = 7.dp

// Aligns the current node with the headline-title's first line (≈ its line-height / 2 from the row top).
private val BranchCurrentCy = 16.dp

/**
 * The short human ref (`#123`) from a full `{org_slug}-{sequence}` ref (ADR-0044) — the trailing numeric
 * sequence only, since the org slug is implied on a single-item screen. `null` when there is no ref (a
 * just-created row) or the tail isn't the expected numeric sequence.
 */
private fun shortRef(ref: String?): String? =
    ref?.substringAfterLast('-')?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.let { "#$it" }

/**
 * The detail's ⋮ more-actions kebab (#262/ADR-0044): Add subtask (reveals the inline add field) and the
 * destructive Delete (the caller gates it behind a confirm), plus "Break this down" where a host wired it.
 * "Set aside" is gone — Dropped is now reachable only through the status picker sheet. Icon-only trigger, so
 * it carries its own contentDescription for TalkBack.
 */
@Composable
private fun TaskOverflowMenu(
    onAddSubtask: () -> Unit,
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
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.common_delete)) },
                onClick = { expanded = false; onDelete() },
            )
        }
    }
}

/**
 * The "Everything in one place" title block (#231): the Task title at headline rank, a mono meta line (the
 * short `#N` ref + due day), and — when the Task parents subtasks — an **overall-status** progress bar with a
 * `{done} of {total} done` label right under the title (#231), so the whole's completion is legible above the
 * fold without scrolling to the Subtasks section. The kind is now carried by the connected-branch node
 * (ADR-0044), not a chip. Calm, low-overwhelm; the ref keeps IBM Plex Mono.
 */
@Composable
private fun DetailTitleBlock(task: Task, subtaskDone: Int, subtaskTotal: Int, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
            shortRef(task.ref)?.let { add(it) }
            task.completeBy?.let { add(stringResource(Res.string.common_due, it.toDisplayDate())) }
        }
        if (meta.isNotEmpty()) {
            MonoMeta(text = meta.joinToString("  ·  "))
        }
        if (subtaskTotal > 0) {
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
