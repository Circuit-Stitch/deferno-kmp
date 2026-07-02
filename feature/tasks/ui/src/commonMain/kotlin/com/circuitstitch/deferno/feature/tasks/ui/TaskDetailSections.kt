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
import androidx.compose.material3.Icon
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.circuitstitch.deferno.core.designsystem.component.BlockedChip
import com.circuitstitch.deferno.core.designsystem.component.CheckDot
import com.circuitstitch.deferno.core.designsystem.component.DefernoIcons
import com.circuitstitch.deferno.core.designsystem.component.KindDot
import com.circuitstitch.deferno.core.designsystem.component.MonoMeta
import com.circuitstitch.deferno.core.designsystem.component.ProgressBarThin
import com.circuitstitch.deferno.core.designsystem.component.SectionLabel
import com.circuitstitch.deferno.core.designsystem.format.formatTime
import com.circuitstitch.deferno.core.designsystem.resources.Res
import com.circuitstitch.deferno.core.designsystem.resources.common_add
import com.circuitstitch.deferno.core.designsystem.resources.common_cancel
import com.circuitstitch.deferno.core.designsystem.resources.common_clear
import com.circuitstitch.deferno.core.designsystem.resources.common_collapse_named_cd
import com.circuitstitch.deferno.core.designsystem.resources.common_delete
import com.circuitstitch.deferno.core.designsystem.resources.common_edit
import com.circuitstitch.deferno.core.designsystem.resources.common_expand_named_cd
import com.circuitstitch.deferno.core.designsystem.resources.common_labels
import com.circuitstitch.deferno.core.designsystem.resources.common_loading
import com.circuitstitch.deferno.core.designsystem.resources.common_open_named_cd
import com.circuitstitch.deferno.core.designsystem.resources.common_remove
import com.circuitstitch.deferno.core.designsystem.resources.common_save
import com.circuitstitch.deferno.core.designsystem.resources.common_set
import com.circuitstitch.deferno.core.designsystem.resources.common_size_bytes
import com.circuitstitch.deferno.core.designsystem.resources.common_size_kb
import com.circuitstitch.deferno.core.designsystem.resources.common_size_mb
import com.circuitstitch.deferno.core.designsystem.resources.common_time_pattern
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_add_caption
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_add_comment_placeholder
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_add_file
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_add_label_placeholder
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_add_subtask_placeholder
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_attachment_meta
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_attachment_meta_on_device
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_caption_placeholder
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_clear_due_date_a11y
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_comment_author_member
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_comment_author_you
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_comment_edited
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_comments_error
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_delete_attachment_a11y
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_due_date_a11y
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_edit_caption
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_edit_caption_a11y
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_encrypted_comment
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_no_attachments
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_no_comments
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_play
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_play_attachment_a11y
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_post
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_posting
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_property_due
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_property_owner
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_property_source
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_property_time
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_remove_caption_a11y
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_remove_label_a11y
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_section_activity
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_section_attachments
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_section_properties
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_section_subtasks
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_set_due_date
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_source_open_in
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_source_row_a11y
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_source_row_no_link_a11y
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_subtask_mark_done_a11y
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_subtask_mark_not_done_a11y
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_uploading
import com.circuitstitch.deferno.core.designsystem.resources.tasks_progress_fraction
import com.circuitstitch.deferno.core.designsystem.theme.defernoColors
import com.circuitstitch.deferno.core.model.Attachment
import com.circuitstitch.deferno.core.model.Comment
import com.circuitstitch.deferno.core.model.ExternalRef
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.UserId
import com.circuitstitch.deferno.core.model.WorkingState
import com.circuitstitch.deferno.feature.tasks.OnDeviceAttachment
import com.circuitstitch.deferno.feature.tasks.SubtaskRow
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import java.util.Locale
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
    SectionLabel(
        text = label,
        modifier = modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp).semantics { heading() },
    )
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
        SectionHeader(stringResource(Res.string.tasks_detail_section_properties))
        DueRow(completeBy = task.completeBy, onSetDeadline = onSetDeadline)
        // ponytail: editable TIME is deferred — it needs the deadline_time_of_day mutation seam.
        PropertyRow(
            label = stringResource(Res.string.tasks_detail_property_time),
            value = task.deadlineTimeOfDay?.let { formatTime(it, stringResource(Res.string.common_time_pattern)) } ?: "—",
        )
        LabelsRow(labels = task.labels, onSetLabels = onSetLabels)
        PropertyRow(label = stringResource(Res.string.tasks_detail_property_owner), value = task.ownerOrgId?.value ?: "—")
        // Source cell for a synced/imported item: the provenance mark + origin label, linking to the
        // provider. Renders only when the item carries external provenance (a native Task shows no row).
        task.external?.let { SourceRow(it) }
    }
}

/**
 * The detail Source cell: the provider [SourceIndicator] mark + the origin label (the `owner/repo#N` tracker
 * ref, or the provider label for a non-tracker), opening the provider URL when present. The label underlines
 * only when it links somewhere. Read-only — provenance, not an editor.
 */
@Composable
private fun SourceRow(external: ExternalRef) {
    val uriHandler = LocalUriHandler.current
    val label = sourceOriginLabel(external)
    val url = external.url
    val rowSemantics = if (url != null) {
        stringResource(Res.string.tasks_detail_source_row_a11y, sourceLabel(external.source), label)
    } else {
        stringResource(Res.string.tasks_detail_source_row_no_link_a11y, sourceLabel(external.source), label)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .then(
                if (url != null) {
                    Modifier.clickable(
                        onClickLabel = stringResource(Res.string.tasks_detail_source_open_in, sourceLabel(external.source)),
                    ) { uriHandler.openUri(url) }
                } else {
                    Modifier
                },
            )
            .clearAndSetSemantics { contentDescription = rowSemantics },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(Res.string.tasks_detail_property_source),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.defernoColors.inkMuted,
            modifier = Modifier.width(80.dp),
        )
        SourceIndicator(external.source)
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textDecoration = if (url != null) TextDecoration.Underline else TextDecoration.None,
            modifier = Modifier.weight(1f),
        )
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
            text = stringResource(Res.string.tasks_detail_property_due),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.defernoColors.inkMuted,
            modifier = Modifier.width(80.dp),
        )
        val dueDateA11y = stringResource(Res.string.tasks_detail_due_date_a11y, display)
        Text(
            text = display,
            style = MaterialTheme.typography.bodyLarge,
            color = if (completeBy == null) MaterialTheme.defernoColors.inkMuted else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = MinTouchTarget)
                .clickable(onClickLabel = stringResource(Res.string.tasks_detail_set_due_date)) { showPicker = true }
                .semantics { contentDescription = dueDateA11y },
        )
        if (completeBy != null) {
            val clearDueDateA11y = stringResource(Res.string.tasks_detail_clear_due_date_a11y)
            TextButton(
                onClick = { onSetDeadline(null) },
                modifier = Modifier.semantics { contentDescription = clearDueDateA11y },
            ) { Text(stringResource(Res.string.common_clear)) }
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
                ) { Text(stringResource(Res.string.common_set)) }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text(stringResource(Res.string.common_cancel)) }
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
            text = stringResource(Res.string.common_labels),
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
                        val removeLabelA11y = stringResource(Res.string.tasks_detail_remove_label_a11y, label)
                        Text(
                            text = "×",
                            modifier = Modifier.semantics { contentDescription = removeLabelA11y },
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
        placeholder = { Text(stringResource(Res.string.tasks_detail_add_label_placeholder)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() }),
        trailingIcon = {
            TextButton(onClick = ::submit, enabled = text.isNotBlank()) { Text(stringResource(Res.string.common_add)) }
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
    // The kebab's "Add subtask" requests focus on the add field via this — null when no kebab drives it.
    addSubtaskFocus: FocusRequester? = null,
) {
    Column(modifier.fillMaxWidth()) {
        SectionHeader(
            stringResource(Res.string.tasks_detail_section_subtasks),
            trailing = if (total > 0) stringResource(Res.string.tasks_progress_fraction, done, total) else null,
        )
        if (total > 0) {
            ProgressBarThin(
                fraction = done.toFloat() / total,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
        }
        rows.forEach { row -> SubtaskRowView(row, onToggleDone, onToggleExpand, onOpen) }
        AddSubtaskField(onAddSubtask, addSubtaskFocus)
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
            // The curvy connecting rail (#231) hangs each subtask off its parent as a continuous spine in
            // a calm tint of the Task accent (the detail outline is Task-only); also adds the depth indent.
            .treeRail(row.spine, kindColor(ItemKind.Task).copy(alpha = RailTintAlpha)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Chevron gutter: ▾/▸ for a parent (toggles fold), blank for a leaf so the check dots still align.
        Box(Modifier.size(SubtaskChevronGutter), contentAlignment = Alignment.Center) {
            if (row.hasChildren) {
                Icon(
                    imageVector = if (row.isExpanded) DefernoIcons.ChevronDown else DefernoIcons.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp).clickable(
                        onClickLabel = if (row.isExpanded) {
                            stringResource(Res.string.common_collapse_named_cd, task.title)
                        } else {
                            stringResource(Res.string.common_expand_named_cd, task.title)
                        },
                    ) { onToggleExpand(task.id.value, row.isExpanded) },
                )
            }
        }
        // The round done toggle (#231) replaces the square checkbox — calmer, and the kind marker rides
        // alongside it so each branch reads as part of the forest.
        CheckDot(
            checked = done,
            onCheckedChange = { onToggleDone(task) },
            contentDescription = if (done) {
                stringResource(Res.string.tasks_detail_subtask_mark_not_done_a11y, task.title)
            } else {
                stringResource(Res.string.tasks_detail_subtask_mark_done_a11y, task.title)
            },
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
            // A blocked subtask mutes like a done one but WITHOUT the strike — "blocked, not finished"
            // (mirrors the tree's ItemTreeRow, #290/#292).
            textDecoration = if (done) TextDecoration.LineThrough else TextDecoration.None,
            color = if (done || task.blocked) MaterialTheme.defernoColors.inkMuted else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .weight(1f)
                .clickable(onClickLabel = stringResource(Res.string.common_open_named_cd, task.title)) { onOpen(task) },
        )
        // The "Blocked" pill carries its own TalkBack label (the row isn't a merged semantics node).
        if (task.blocked) {
            BlockedChip(modifier = Modifier.padding(horizontal = 4.dp))
        }
        Icon(
            imageVector = DefernoIcons.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.defernoColors.inkMuted,
            modifier = Modifier.padding(horizontal = 8.dp).size(20.dp),
        )
    }
}

/** The leading fold-chevron column of an outline row; keeps a fixed gutter so leaf checkboxes align. */
private val SubtaskChevronGutter = 28.dp

@Composable
private fun AddSubtaskField(onAdd: (String) -> Unit, focusRequester: FocusRequester? = null) {
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
        placeholder = { Text(stringResource(Res.string.tasks_detail_add_subtask_placeholder)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() }),
        trailingIcon = {
            TextButton(onClick = ::submit, enabled = text.isNotBlank()) { Text(stringResource(Res.string.common_add)) }
        },
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
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
        SectionHeader(
            stringResource(Res.string.tasks_detail_section_attachments),
            trailing = (attachments.size + onDeviceAttachments.size).toString(),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onAddClick, enabled = !isUploading) {
                Text(
                    if (isUploading) {
                        stringResource(Res.string.tasks_detail_uploading)
                    } else {
                        stringResource(Res.string.tasks_detail_add_file)
                    },
                )
            }
        }
        if (attachments.isEmpty() && onDeviceAttachments.isEmpty()) {
            Text(
                stringResource(Res.string.tasks_detail_no_attachments),
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
                MonoMeta(
                    text = stringResource(
                        Res.string.tasks_detail_attachment_meta_on_device,
                        formatBytes(attachment.size),
                        attachment.mime,
                    ),
                )
                attachment.caption?.takeIf { it.isNotBlank() }?.let { caption ->
                    Text(caption, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (attachment.isAudio) {
                val playA11y = stringResource(Res.string.tasks_detail_play_attachment_a11y, attachment.filename)
                TextButton(
                    onClick = { onPlay(attachment) },
                    modifier = Modifier.semantics { contentDescription = playA11y },
                ) { Text(stringResource(Res.string.tasks_detail_play)) }
            }
            val deleteA11y = stringResource(Res.string.tasks_detail_delete_attachment_a11y, attachment.filename)
            TextButton(
                onClick = { onDelete(attachment.id) },
                modifier = Modifier.semantics { contentDescription = deleteA11y },
            ) { Text(stringResource(Res.string.common_delete)) }
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
                    .clickable(
                        onClickLabel = stringResource(Res.string.common_open_named_cd, attachment.filename),
                    ) { uriHandler.openUri(attachment.url) },
            ) {
                Text(attachment.filename, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                MonoMeta(
                    text = stringResource(
                        Res.string.tasks_detail_attachment_meta,
                        formatBytes(attachment.size),
                        attachment.mime,
                    ),
                )
                attachment.caption?.takeIf { it.isNotBlank() }?.let { caption ->
                    Text(caption, style = MaterialTheme.typography.bodyMedium)
                }
            }
            val deleteA11y = stringResource(Res.string.tasks_detail_delete_attachment_a11y, attachment.filename)
            TextButton(
                onClick = { onDelete(attachment.id) },
                modifier = Modifier.semantics { contentDescription = deleteA11y },
            ) { Text(stringResource(Res.string.common_delete)) }
        }
        if (editing) {
            var draft by remember(attachment.id) { mutableStateOf(attachment.caption.orEmpty()) }
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text(stringResource(Res.string.tasks_detail_caption_placeholder)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                // #416: an explicit Remove clears the caption (sends null), distinct from typing an
                // empty string — shown only when there's a caption to remove.
                if (!attachment.caption.isNullOrBlank()) {
                    val removeCaptionA11y =
                        stringResource(Res.string.tasks_detail_remove_caption_a11y, attachment.filename)
                    TextButton(
                        onClick = { onSetCaption(attachment.id, null); editing = false },
                        modifier = Modifier.semantics {
                            contentDescription = removeCaptionA11y
                        },
                    ) { Text(stringResource(Res.string.common_remove)) }
                }
                TextButton(onClick = { editing = false }) { Text(stringResource(Res.string.common_cancel)) }
                Button(
                    onClick = {
                        if (draft.isNotBlank()) onSetCaption(attachment.id, draft)
                        editing = false
                    },
                    enabled = draft.isNotBlank(),
                ) { Text(stringResource(Res.string.common_save)) }
            }
        } else {
            val editCaptionA11y = stringResource(Res.string.tasks_detail_edit_caption_a11y, attachment.filename)
            TextButton(
                onClick = { editing = true },
                modifier = Modifier.semantics { contentDescription = editCaptionA11y },
            ) {
                Text(
                    if (attachment.caption.isNullOrBlank()) {
                        stringResource(Res.string.tasks_detail_add_caption)
                    } else {
                        stringResource(Res.string.tasks_detail_edit_caption)
                    },
                )
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
        SectionHeader(stringResource(Res.string.tasks_detail_section_activity), trailing = comments.size.toString())
        CommentComposer(isPosting = isPosting, onPost = onPost)
        when {
            loading && comments.isEmpty() -> MutedLine(stringResource(Res.string.common_loading))
            error && comments.isEmpty() -> MutedLine(stringResource(Res.string.tasks_detail_comments_error))
            comments.isEmpty() -> MutedLine(stringResource(Res.string.tasks_detail_no_comments))
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
            placeholder = { Text(stringResource(Res.string.tasks_detail_add_comment_placeholder)) },
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
            ) {
                Text(
                    if (isPosting) {
                        stringResource(Res.string.tasks_detail_posting)
                    } else {
                        stringResource(Res.string.tasks_detail_post)
                    },
                )
            }
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
                text = if (isMine) {
                    stringResource(Res.string.tasks_detail_comment_author_you)
                } else {
                    stringResource(Res.string.tasks_detail_comment_author_member)
                },
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = comment.createdAt.toDisplayDate() +
                    if (comment.editedAt != null) " " + stringResource(Res.string.tasks_detail_comment_edited) else "",
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
                TextButton(onClick = { editing = false }) { Text(stringResource(Res.string.common_cancel)) }
                Button(
                    onClick = {
                        if (draft.isNotBlank()) onEdit(comment.id, draft)
                        editing = false
                    },
                    enabled = draft.isNotBlank(),
                ) { Text(stringResource(Res.string.common_save)) }
            }
        } else {
            Text(
                text = comment.body ?: stringResource(Res.string.tasks_detail_encrypted_comment),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (isMine) {
                Row {
                    TextButton(onClick = { editing = true }) { Text(stringResource(Res.string.common_edit)) }
                    TextButton(onClick = { onDelete(comment.id) }) { Text(stringResource(Res.string.common_delete)) }
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

/** Bytes as a friendly size, e.g. 12345 → "12.1 KB". Unit suffix and decimal separator are locale-aware. */
@Composable
internal fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> stringResource(Res.string.common_size_bytes, bytes)
    bytes < 1024 * 1024 -> stringResource(Res.string.common_size_kb, formatTenths(bytes * 10 / 1024))
    else -> stringResource(Res.string.common_size_mb, formatTenths(bytes * 10 / (1024 * 1024)))
}

/** One-decimal rendering of a tenths count (123 → "12.3"), with the device locale's decimal separator. */
private fun formatTenths(tenths: Long): String = String.format(Locale.getDefault(), "%.1f", tenths / 10.0)

/**
 * The date portion of a comment's timestamp (e.g. "2026-04-17"). Sliced straight from the RFC3339
 * string the [kotlin.time.Instant] round-trips — a zero-dependency display (ponytail: no timezone
 * library pulled into the UI module for a single label); promote to a localized, zoned format when the
 * detail earns richer time display.
 */
internal fun kotlin.time.Instant.toDisplayDate(): String = toString().substringBefore('T')
