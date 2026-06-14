package com.circuitstitch.deferno.feature.tasks.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.circuitstitch.deferno.core.designsystem.theme.defernoColors
import com.circuitstitch.deferno.core.model.Attachment
import com.circuitstitch.deferno.core.model.Comment
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.UserId
import com.circuitstitch.deferno.core.model.WorkingState
import com.circuitstitch.deferno.feature.tasks.SubtaskNode

// The web-parity Task detail sections (Subtasks tree · Attachments · Activity/Comments). Platform-
// neutral Compose (Android + desktop) — thin, stateless renderers driven by [TaskDetailComponent]
// state + callbacks. Design-principles.md: calm flat lists, large touch targets, plain labels,
// self-describing TalkBack semantics, colour never the sole signal.

private const val MaxCommentLength = 5000

/** A calm section header: a heading title with an optional trailing count (e.g. "0/3", "2"). */
@Composable
internal fun SectionHeader(title: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.defernoColors.inkMuted,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (trailing != null) {
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.defernoColors.inkMuted,
            )
        }
    }
}

// --- Subtasks ---

/**
 * The recursive subtask tree (web parity): a done/total count + progress bar, the nested checkboxes,
 * and an "add subtask" field that creates a direct child. Toggling a checkbox flips that node between
 * Done and Open through the working-state write seam (offline-first); adding creates online.
 */
@Composable
internal fun SubtasksSection(
    nodes: List<SubtaskNode>,
    done: Int,
    total: Int,
    onToggle: (Task) -> Unit,
    onOpen: (Task) -> Unit,
    onAddSubtask: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        SectionHeader("Subtasks", trailing = if (total > 0) "$done/$total" else null)
        if (total > 0) {
            LinearProgressIndicator(
                progress = { done.toFloat() / total },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
        }
        nodes.forEach { node -> SubtaskNodeRows(node, depth = 0, onToggle = onToggle, onOpen = onOpen) }
        AddSubtaskField(onAddSubtask)
    }
}

/** Renders one tree node then, indented, its children — the recursion that draws the whole subtree. */
@Composable
private fun SubtaskNodeRows(node: SubtaskNode, depth: Int, onToggle: (Task) -> Unit, onOpen: (Task) -> Unit) {
    SubtaskRow(node.task, depth, onToggle, onOpen)
    node.children.forEach { child -> SubtaskNodeRows(child, depth + 1, onToggle, onOpen) }
}

@Composable
private fun SubtaskRow(task: Task, depth: Int, onToggle: (Task) -> Unit, onOpen: (Task) -> Unit) {
    val done = task.workingState == WorkingState.Done
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .padding(start = (depth * 20).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = done,
            onCheckedChange = { onToggle(task) },
            modifier = Modifier.semantics {
                contentDescription = if (done) "Mark “${task.title}” not done" else "Mark “${task.title}” done"
            },
        )
        // Tapping the title (not the checkbox) drills into that subtask's own detail — web's chevron.
        Text(
            text = task.title,
            style = MaterialTheme.typography.bodyLarge,
            textDecoration = if (done) TextDecoration.LineThrough else TextDecoration.None,
            color = if (done) MaterialTheme.defernoColors.inkMuted else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .weight(1f)
                .clickable(onClickLabel = "Open ${task.title}") { onOpen(task) },
        )
        Text(
            text = "›",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.defernoColors.inkMuted,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

@Composable
private fun AddSubtaskField(onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    fun submit() {
        if (text.isNotBlank()) {
            onAdd(text)
            text = ""
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        placeholder = { Text("Add a subtask…") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() }),
        trailingIcon = {
            TextButton(onClick = ::submit, enabled = text.isNotBlank()) { Text("Add") }
        },
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    )
}

// --- Attachments ---

/**
 * The attachment list: filename + size/type, tapping a row opens the signed URL in the platform. An
 * "Add file" affordance launches the platform file picker ([onAddClick]; the picker + byte read are the
 * host's androidMain glue), and each row offers Delete. [isUploading] disables Add while a PUT is in
 * flight.
 */
@Composable
internal fun AttachmentsSection(
    attachments: List<Attachment>,
    isUploading: Boolean,
    onAddClick: () -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        SectionHeader("Attachments", trailing = attachments.size.toString())
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onAddClick, enabled = !isUploading) {
                Text(if (isUploading) "Uploading…" else "Add file")
            }
        }
        if (attachments.isEmpty()) {
            Text(
                "No attachments.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.defernoColors.inkMuted,
            )
        } else {
            val uriHandler = LocalUriHandler.current
            attachments.forEach { a ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = MinTouchTarget)
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable(onClickLabel = "Open ${a.filename}") { uriHandler.openUri(a.url) },
                    ) {
                        Text(a.filename, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                        Text(
                            text = "${formatBytes(a.size)} · ${a.mime}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.defernoColors.inkMuted,
                            maxLines = 1,
                        )
                    }
                    TextButton(
                        onClick = { onDelete(a.id) },
                        modifier = Modifier.semantics { contentDescription = "Delete ${a.filename}" },
                    ) { Text("Delete") }
                }
            }
        }
    }
}

// --- Activity / Comments ---

/**
 * The Activity thread (web parity, comments only): a composer to post, then the comment list. The
 * current user's own comments offer inline Edit / Delete (the server enforces the real authorization).
 */
@Composable
internal fun CommentsSection(
    comments: List<Comment>,
    currentUserId: UserId?,
    loading: Boolean,
    error: Boolean,
    isPosting: Boolean,
    onPost: (String) -> Unit,
    onEdit: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionHeader("Activity", trailing = comments.size.toString())
        CommentComposer(isPosting = isPosting, onPost = onPost)
        when {
            loading && comments.isEmpty() -> MutedLine("Loading…")
            error && comments.isEmpty() -> MutedLine("Couldn't load comments. Pull to refresh later.")
            comments.isEmpty() -> MutedLine("No comments yet.")
            else -> comments.forEach { c ->
                CommentRow(c, isMine = currentUserId != null && c.createdBy == currentUserId, onEdit, onDelete)
            }
        }
    }
}

@Composable
private fun CommentComposer(isPosting: Boolean, onPost: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = text,
            onValueChange = { if (it.length <= MaxCommentLength) text = it },
            placeholder = { Text("Add a comment…") },
            enabled = !isPosting,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Button(
                onClick = {
                    if (text.isNotBlank()) {
                        onPost(text)
                        text = ""
                    }
                },
                enabled = !isPosting && text.isNotBlank(),
                modifier = Modifier.heightIn(min = MinTouchTarget),
            ) { Text(if (isPosting) "Posting…" else "Post") }
        }
    }
}

@Composable
private fun CommentRow(
    comment: Comment,
    isMine: Boolean,
    onEdit: (String, String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var editing by remember(comment.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (isMine) "You" else "Member",
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = comment.createdAt.toDisplayDate() + if (comment.editedAt != null) " (edited)" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.defernoColors.inkMuted,
            )
        }
        if (editing) {
            var draft by remember(comment.id) { mutableStateOf(comment.body.orEmpty()) }
            OutlinedTextField(
                value = draft,
                onValueChange = { if (it.length <= MaxCommentLength) draft = it },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { editing = false }) { Text("Cancel") }
                Button(
                    onClick = {
                        if (draft.isNotBlank()) onEdit(comment.id, draft)
                        editing = false
                    },
                    enabled = draft.isNotBlank(),
                ) { Text("Save") }
            }
        } else {
            Text(
                text = comment.body ?: "🔒 Encrypted comment",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (isMine) {
                Row {
                    TextButton(onClick = { editing = true }) { Text("Edit") }
                    TextButton(onClick = { onDelete(comment.id) }) { Text("Delete") }
                }
            }
        }
    }
}

@Composable
private fun MutedLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.defernoColors.inkMuted,
    )
}

/** Bytes as a friendly size, e.g. 12345 → "12.1 KB". */
internal fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${(bytes * 10 / 1024) / 10.0} KB"
    else -> "${(bytes * 10 / (1024 * 1024)) / 10.0} MB"
}

/**
 * The date portion of a comment's timestamp (e.g. "2026-04-17"). Sliced straight from the RFC3339
 * string the [kotlin.time.Instant] round-trips — a zero-dependency display (ponytail: no timezone
 * library pulled into the UI module for a single label); promote to a localized, zoned format when the
 * detail earns richer time display.
 */
internal fun kotlin.time.Instant.toDisplayDate(): String = toString().substringBefore('T')
