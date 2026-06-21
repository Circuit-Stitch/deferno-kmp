package com.circuitstitch.deferno.feature.tasks.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.circuitstitch.deferno.core.designsystem.theme.defernoColors
import com.circuitstitch.deferno.core.model.Attachment
import com.circuitstitch.deferno.core.model.Comment
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.UserId
import com.circuitstitch.deferno.core.model.WorkingState
import com.circuitstitch.deferno.feature.tasks.OnDeviceAttachment
import com.circuitstitch.deferno.feature.tasks.SubtaskRow
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

// The web-parity Task detail sections (Subtasks tree · Attachments · Activity/Comments). Platform-
// neutral Compose (Android + desktop) — thin, stateless renderers driven by [TaskDetailComponent]
// state + callbacks. Design-principles.md: calm flat lists, large touch targets, plain labels,
// self-describing TalkBack semantics, colour never the sole signal.

private const val MaxCommentLength = 5000

/**
 * A calm, all-caps section header (#231): "ATTACHMENTS", "SUBTASKS · 3", "ACTIVITY · 2". The [trailing]
 * count, when present, folds into the label after a mid-dot so the header reads as one heading. Rendered
 * through the shared [SectionLabel] atom (a heading for screen readers).
 */
@Composable
internal fun SectionHeader(title: String, modifier: Modifier = Modifier, trailing: String? = null) {
    val label = if (trailing != null) "${title.uppercase()} · $trailing" else title.uppercase()
    SectionLabel(text = label, modifier = modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp))
}

// --- Properties (DUE · TIME · LABELS · OWNER) ---

/**
 * The calm PROPERTIES block on the Task detail: a flat list of rows for the deadline DUE date, its
 * TIME-of-day, the LABELS, and the OWNER org. DUE and LABELS are editable through the [TaskDetailComponent]
 * write seams ([onSetDeadline] / [onSetLabels], optimistic + offline-first); TIME and OWNER are read-only
 * this slice. Renders straight off the hydrated [task] fields — no new component state (#195).
 *
 * Design-principles.md: plain labels, large touch targets, a muted "—" for absent values, and
 * self-describing TalkBack semantics on the interactive controls.
 */
@Composable
internal fun PropertiesSection(
    task: Task,
    onSetDeadline: (LocalDate?) -> Unit,
    onSetLabels: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        SectionHeader("Properties")
        DueRow(completeBy = task.completeBy, onSetDeadline = onSetDeadline)
        // ponytail: editable TIME is deferred — it needs the deadline_time_of_day mutation seam.
        PropertyRow(label = "Time", value = task.deadlineTimeOfDay?.toDisplayTime() ?: "—")
        LabelsRow(labels = task.labels, onSetLabels = onSetLabels)
        PropertyRow(label = "Owner", value = task.ownerOrgId?.value ?: "—")
    }
}

/** A read-only property: a fixed label and its value (or a muted "—" when absent). */
@Composable
private fun PropertyRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.defernoColors.inkMuted,
            modifier = Modifier.width(80.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = if (value == "—") MaterialTheme.defernoColors.inkMuted else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * The editable DUE row: shows the deadline day (or a muted "—"), tapping opens a Material3
 * [DatePickerDialog] seeded from [completeBy]; confirming forwards the picked day, and a Clear
 * affordance forwards `null` to drop the deadline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DueRow(completeBy: Instant?, onSetDeadline: (LocalDate?) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    val display = completeBy?.toDisplayDate() ?: "—"
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Due",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.defernoColors.inkMuted,
            modifier = Modifier.width(80.dp),
        )
        Text(
            text = display,
            style = MaterialTheme.typography.bodyLarge,
            color = if (completeBy == null) MaterialTheme.defernoColors.inkMuted else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = MinTouchTarget)
                .clickable(onClickLabel = "Set due date") { showPicker = true }
                .semantics { contentDescription = "Due date: $display. Tap to change." },
        )
        if (completeBy != null) {
            TextButton(
                onClick = { onSetDeadline(null) },
                modifier = Modifier.semantics { contentDescription = "Clear due date" },
            ) { Text("Clear") }
        }
    }
    if (showPicker) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = completeBy?.toEpochMilliseconds())
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { onSetDeadline(it.toPickedLocalDate()) }
                        showPicker = false
                    },
                ) { Text("Set") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

/**
 * Epoch millis from the Material3 DatePicker → the calendar day the user tapped. Spec (#195): read it
 * back at the device zone; the picker stores the selection as a midnight instant, so projecting it onto
 * [TimeZone.currentSystemDefault] yields that day for the seam to combine with the Task's time-of-day.
 */
private fun Long.toPickedLocalDate(): LocalDate =
    Instant.fromEpochMilliseconds(this)
        .toLocalDateTime(TimeZone.currentSystemDefault())
        .date

/**
 * The editable LABELS row: each label as a removable [InputChip], plus an inline "add label" field. On
 * any add or remove the whole updated list (trimmed, blanks + duplicates dropped) is forwarded through
 * [onSetLabels] — the component replaces the Task's labels wholesale.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LabelsRow(labels: List<String>, onSetLabels: (List<String>) -> Unit) {
    fun normalize(list: List<String>): List<String> =
        list.map { it.trim() }.filter { it.isNotBlank() }.distinct()

    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = "Labels",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.defernoColors.inkMuted,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            labels.forEach { label ->
                InputChip(
                    selected = false,
                    onClick = { onSetLabels(normalize(labels - label)) },
                    label = { Text(label) },
                    trailingIcon = {
                        Text(
                            text = "×",
                            modifier = Modifier.semantics { contentDescription = "Remove label $label" },
                        )
                    },
                )
            }
        }
        AddLabelField(onAdd = { entry -> onSetLabels(normalize(labels + entry)) })
    }
}

@Composable
private fun AddLabelField(onAdd: (String) -> Unit) {
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
        placeholder = { Text("Add a label…") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() }),
        trailingIcon = {
            TextButton(onClick = ::submit, enabled = text.isNotBlank()) { Text("Add") }
        },
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    )
}

// --- Subtasks ---

/**
 * The subtask outline (web parity): a done/total count + progress bar, the depth-indented checkboxes,
 * and an "add subtask" field that creates a direct child. The [rows] are the subtree flattened with the
 * **same fold mechanism as the Tasks Destination tree** (ADR-0034 decision 4) — a parent's chevron
 * toggles its fold through the shared device-local store, so a node folded here stays folded on the tree
 * and across restart. Toggling a checkbox flips that node between Done and Open through the working-state
 * write seam (offline-first); adding creates online.
 */
@Composable
internal fun SubtasksSection(
    rows: List<SubtaskRow>,
    done: Int,
    total: Int,
    onToggleDone: (Task) -> Unit,
    onToggleExpand: (id: String, currentlyExpanded: Boolean) -> Unit,
    onOpen: (Task) -> Unit,
    onAddSubtask: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        SectionHeader("Subtasks", trailing = if (total > 0) "$done of $total" else null)
        if (total > 0) {
            ProgressBarThin(
                fraction = done.toFloat() / total,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
        }
        rows.forEach { row -> SubtaskRowView(row, onToggleDone, onToggleExpand, onOpen) }
        AddSubtaskField(onAddSubtask)
    }
}

/** One depth-indented outline row: a fold chevron (parents only), a done checkbox, a drill-in title. */
@Composable
private fun SubtaskRowView(
    row: SubtaskRow,
    onToggleDone: (Task) -> Unit,
    onToggleExpand: (id: String, currentlyExpanded: Boolean) -> Unit,
    onOpen: (Task) -> Unit,
) {
    val task = row.task
    val done = task.workingState == WorkingState.Done
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .padding(start = (row.depth * 20).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Chevron gutter: ▾/▸ for a parent (toggles fold), blank for a leaf so the check dots still align.
        Box(Modifier.size(SubtaskChevronGutter), contentAlignment = Alignment.Center) {
            if (row.hasChildren) {
                Text(
                    text = if (row.isExpanded) DefernoIcons.ChevronDown else DefernoIcons.ChevronRight,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable(
                        onClickLabel = if (row.isExpanded) "Collapse ${task.title}" else "Expand ${task.title}",
                    ) { onToggleExpand(task.id.value, row.isExpanded) },
                )
            }
        }
        // The round done toggle (#231) replaces the square checkbox — calmer, and the kind marker rides
        // alongside it so each branch reads as part of the forest.
        CheckDot(
            checked = done,
            onCheckedChange = { onToggleDone(task) },
            contentDescription = if (done) "Mark “${task.title}” not done" else "Mark “${task.title}” done",
        )
        KindDot(
            color = kindColor(ItemKind.Task),
            modifier = Modifier
                .padding(end = 8.dp)
                .clearAndSetSemantics {},
        )
        // Tapping the title (not the check dot) drills into that subtask's own detail — web's chevron.
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
            text = DefernoIcons.ChevronRight,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.defernoColors.inkMuted,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

/** The leading fold-chevron column of an outline row; keeps a fixed gutter so leaf checkboxes align. */
private val SubtaskChevronGutter = 28.dp

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
 * The attachment list: filename + size/type + optional caption, tapping a row opens the signed URL in
 * the platform. An "Add file" affordance launches the platform file picker ([onAddClick]; the picker +
 * byte read are the host's androidMain glue), and each row offers Delete + an inline caption editor.
 * [isUploading] disables Add while a PUT is in flight.
 */
@Composable
internal fun AttachmentsSection(
    attachments: List<Attachment>,
    isUploading: Boolean,
    onAddClick: () -> Unit,
    onDelete: (String) -> Unit,
    onSetCaption: (String, String?) -> Unit,
    modifier: Modifier = Modifier,
    // On-device attachments (#211, e.g. a retained brain-dump recording). Rendered below the synced
    // attachments: they have no signed URL, so audio is played locally rather than opened in a browser.
    // Empty on platforms without on-device capture (desktop/iOS) — then this section is unchanged.
    onDeviceAttachments: List<OnDeviceAttachment> = emptyList(),
    onDeleteOnDevice: (String) -> Unit = {},
    onPlayOnDevice: (OnDeviceAttachment) -> Unit = {},
) {
    Column(modifier.fillMaxWidth()) {
        SectionHeader("Attachments", trailing = (attachments.size + onDeviceAttachments.size).toString())
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onAddClick, enabled = !isUploading) {
                Text(if (isUploading) "Uploading…" else "Add file")
            }
        }
        if (attachments.isEmpty() && onDeviceAttachments.isEmpty()) {
            Text(
                "No attachments.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.defernoColors.inkMuted,
            )
        } else {
            attachments.forEach { a -> AttachmentRow(a, onDelete, onSetCaption) }
            onDeviceAttachments.forEach { a -> OnDeviceAttachmentRow(a, onDeleteOnDevice, onPlayOnDevice) }
        }
    }
}

/**
 * One on-device attachment (#211): filename + size/type + an "On device" marker, with Play (audio only)
 * and Delete. No URL-open or caption editor — the bytes live on this device and (for a recording) are
 * played locally by the host's androidMain glue via [onPlay].
 */
@Composable
private fun OnDeviceAttachmentRow(
    attachment: OnDeviceAttachment,
    onDelete: (String) -> Unit,
    onPlay: (OnDeviceAttachment) -> Unit,
) {
    DetailCard {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(attachment.filename, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                MonoMeta(text = "${formatBytes(attachment.size)} · ${attachment.mime} · On device")
                attachment.caption?.takeIf { it.isNotBlank() }?.let { caption ->
                    Text(caption, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (attachment.isAudio) {
                TextButton(
                    onClick = { onPlay(attachment) },
                    modifier = Modifier.semantics { contentDescription = "Play ${attachment.filename}" },
                ) { Text("Play") }
            }
            TextButton(
                onClick = { onDelete(attachment.id) },
                modifier = Modifier.semantics { contentDescription = "Delete ${attachment.filename}" },
            ) { Text("Delete") }
        }
    }
}

/** A calm card for a detail row (attachment / comment): surfaceContainerLow, rounded, gentle spacing. */
@Composable
private fun DetailCard(content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) { content() }
}

@Composable
private fun AttachmentRow(
    attachment: Attachment,
    onDelete: (String) -> Unit,
    onSetCaption: (String, String?) -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    var editing by remember(attachment.id) { mutableStateOf(false) }
    DetailCard {
      Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClickLabel = "Open ${attachment.filename}") { uriHandler.openUri(attachment.url) },
            ) {
                Text(attachment.filename, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                MonoMeta(text = "${formatBytes(attachment.size)} · ${attachment.mime}")
                attachment.caption?.takeIf { it.isNotBlank() }?.let { caption ->
                    Text(caption, style = MaterialTheme.typography.bodyMedium)
                }
            }
            TextButton(
                onClick = { onDelete(attachment.id) },
                modifier = Modifier.semantics { contentDescription = "Delete ${attachment.filename}" },
            ) { Text("Delete") }
        }
        if (editing) {
            var draft by remember(attachment.id) { mutableStateOf(attachment.caption.orEmpty()) }
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("Caption") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                // #416: an explicit Remove clears the caption (sends null), distinct from typing an
                // empty string — shown only when there's a caption to remove.
                if (!attachment.caption.isNullOrBlank()) {
                    TextButton(
                        onClick = { onSetCaption(attachment.id, null); editing = false },
                        modifier = Modifier.semantics {
                            contentDescription = "Remove caption for ${attachment.filename}"
                        },
                    ) { Text("Remove") }
                }
                TextButton(onClick = { editing = false }) { Text("Cancel") }
                Button(
                    onClick = {
                        if (draft.isNotBlank()) onSetCaption(attachment.id, draft)
                        editing = false
                    },
                    enabled = draft.isNotBlank(),
                ) { Text("Save") }
            }
        } else {
            TextButton(
                onClick = { editing = true },
                modifier = Modifier.semantics { contentDescription = "Edit caption for ${attachment.filename}" },
            ) { Text(if (attachment.caption.isNullOrBlank()) "Add caption" else "Edit caption") }
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
    DetailCard {
      Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
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
