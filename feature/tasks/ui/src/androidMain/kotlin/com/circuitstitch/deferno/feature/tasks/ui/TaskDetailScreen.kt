package com.circuitstitch.deferno.feature.tasks.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.circuitstitch.deferno.core.data.task.AttachmentUpload
import com.circuitstitch.deferno.core.designsystem.theme.defernoColors
import com.circuitstitch.deferno.core.designsystem.theme.plexMono
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.WorkingState
import com.circuitstitch.deferno.feature.tasks.TaskDetailComponent
import com.circuitstitch.deferno.feature.tasks.TaskDetailState
import kotlinx.datetime.LocalDate

/**
 * The Task detail pane (#27). Thin renderer of [TaskDetailComponent]: observes the hydrating row and
 * forwards the close / add-to-plan / working-state intents plus the web-parity section intents
 * (subtask toggle/add, comment post/edit/delete). The component hydrates + loads comments/attachments
 * on creation; this View just reflects [TaskDetailComponent.state].
 */
@Composable
fun TaskDetailScreen(component: TaskDetailComponent, modifier: Modifier = Modifier) {
    val state by component.state.collectAsState()
    val context = LocalContext.current
    // The Storage Access Framework picker owns the file → bytes glue (cf. the feedback FeedbackScreen);
    // it reads each picked file through the ContentResolver and hands them to the component.
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        val files = uris.take(MaxAttachments)
            .mapNotNull { readAttachmentUpload(context, it) }
            .filter { it.bytes.size <= MaxAttachmentBytes }
        if (files.isNotEmpty()) component.onAddAttachments(files)
    }
    TaskDetailContent(
        state = state,
        onClose = component::onCloseClicked,
        onAddToPlan = component::onAddToPlanClicked,
        onSetWorkingState = component::onSetWorkingState,
        onSetDeadline = component::onSetDeadline,
        onSetLabels = component::onSetLabels,
        onToggleSubtask = component::onToggleSubtaskDone,
        onOpenSubtask = { component.onSubtaskClicked(it.id) },
        onAddSubtask = component::onAddSubtask,
        onPostComment = component::onPostComment,
        onEditComment = component::onEditComment,
        onDeleteComment = component::onDeleteComment,
        onAddAttachment = { pickFiles.launch("*/*") },
        onDeleteAttachment = component::onDeleteAttachment,
        onSetAttachmentCaption = component::onSetAttachmentCaption,
        modifier = modifier,
    )
}

// The web's attachment limits: at most 5 files per add, 25 MB each.
private const val MaxAttachments = 5
private const val MaxAttachmentBytes = 25 * 1024 * 1024

/** Resolve a picked [uri] into an [AttachmentUpload] — its display name, MIME type, and bytes. */
private fun readAttachmentUpload(context: Context, uri: Uri): AttachmentUpload? {
    val resolver = context.contentResolver
    val mime = resolver.getType(uri) ?: "application/octet-stream"
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    } ?: uri.lastPathSegment ?: "attachment"
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    return AttachmentUpload(filename = name, contentType = mime, bytes = bytes)
}

/** Stateless body — rendered directly by screenshot/UI tests with fixed inputs. */
@Composable
internal fun TaskDetailContent(
    state: TaskDetailState,
    onClose: () -> Unit,
    onAddToPlan: () -> Unit,
    onSetWorkingState: (WorkingState) -> Unit,
    onSetDeadline: (LocalDate?) -> Unit,
    onSetLabels: (List<String>) -> Unit,
    onToggleSubtask: (Task) -> Unit,
    onOpenSubtask: (Task) -> Unit,
    onAddSubtask: (String) -> Unit,
    onPostComment: (String) -> Unit,
    onEditComment: (String, String) -> Unit,
    onDeleteComment: (String) -> Unit,
    onAddAttachment: () -> Unit,
    onDeleteAttachment: (String) -> Unit,
    onSetAttachmentCaption: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val task = state.task
    Column(modifier = modifier.fillMaxSize()) {
        // In single-pane the leading control returns to the list, so it reads as "Back".
        PaneHeader(title = task?.title ?: "Task", onBack = onClose)
        if (state.isHydrating) {
            LoadingStrip(label = "Loading details…")
        }
        when {
            task == null && !state.isHydrating -> EmptyState(
                title = "Task not found",
                body = "This task may have been removed. Head back to your list.",
            )
            task == null -> Unit // brief hydrating gap before the row is observed; the bar above shows it
            else -> TaskBody(
                task = task,
                state = state,
                onAddToPlan = onAddToPlan,
                onSetWorkingState = onSetWorkingState,
                onSetDeadline = onSetDeadline,
                onSetLabels = onSetLabels,
                onToggleSubtask = onToggleSubtask,
                onOpenSubtask = onOpenSubtask,
                onAddSubtask = onAddSubtask,
                onPostComment = onPostComment,
                onEditComment = onEditComment,
                onDeleteComment = onDeleteComment,
                onAddAttachment = onAddAttachment,
                onDeleteAttachment = onDeleteAttachment,
                onSetAttachmentCaption = onSetAttachmentCaption,
            )
        }
    }
}

@Composable
private fun TaskBody(
    task: Task,
    state: TaskDetailState,
    onAddToPlan: () -> Unit,
    onSetWorkingState: (WorkingState) -> Unit,
    onSetDeadline: (LocalDate?) -> Unit,
    onSetLabels: (List<String>) -> Unit,
    onToggleSubtask: (Task) -> Unit,
    onOpenSubtask: (Task) -> Unit,
    onAddSubtask: (String) -> Unit,
    onPostComment: (String) -> Unit,
    onEditComment: (String, String) -> Unit,
    onDeleteComment: (String) -> Unit,
    onAddAttachment: () -> Unit,
    onDeleteAttachment: (String) -> Unit,
    onSetAttachmentCaption: (String, String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        task.ref?.let { ref ->
            Text(
                text = ref,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = plexMono(),
                color = MaterialTheme.defernoColors.inkMuted,
            )
        }
        WorkingStateEditor(current = task.workingState, onSetWorkingState = onSetWorkingState)

        val description = task.description
        when {
            !description.isNullOrBlank() -> Text(description, style = MaterialTheme.typography.bodyLarge)
            !state.isHydrating -> Text(
                text = "No description yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.defernoColors.inkMuted,
            )
        }

        Button(
            onClick = onAddToPlan,
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget),
        ) { Text("Add to today's plan") }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        PropertiesSection(
            task = task,
            onSetDeadline = onSetDeadline,
            onSetLabels = onSetLabels,
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SubtasksSection(
            nodes = state.subtasks,
            done = state.subtaskDone,
            total = state.subtaskTotal,
            onToggle = onToggleSubtask,
            onOpen = onOpenSubtask,
            onAddSubtask = onAddSubtask,
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        AttachmentsSection(
            attachments = state.attachments,
            isUploading = state.isUploadingAttachment,
            onAddClick = onAddAttachment,
            onDelete = onDeleteAttachment,
            onSetCaption = onSetAttachmentCaption,
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
            text = "Working state",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.defernoColors.inkMuted,
            modifier = Modifier.semantics { heading() },
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WorkingState.entries.forEach { state ->
                val label = workingStateLabel(state)
                val selected = state == current
                FilterChip(
                    selected = selected,
                    onClick = { onSetWorkingState(state) },
                    label = { Text(label) },
                    modifier = Modifier.semantics {
                        contentDescription = if (selected) "$label, current working state" else "Set to $label"
                    },
                )
            }
        }
    }
}

/** The plain, non-shaming label for each [WorkingState] (matches [WorkingStateBadge]). */
internal fun workingStateLabel(state: WorkingState): String = when (state) {
    WorkingState.Open -> "Open"
    WorkingState.InProgress -> "In progress"
    WorkingState.InReview -> "In review"
    WorkingState.Done -> "Done"
    WorkingState.Dropped -> "Set aside"
}
