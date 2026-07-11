package com.circuitstitch.deferno.feature.tasks.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.circuitstitch.deferno.core.designsystem.component.BlockedChip
import com.circuitstitch.deferno.core.designsystem.component.CheckDot
import com.circuitstitch.deferno.core.designsystem.component.DefernoIcons
import com.circuitstitch.deferno.core.designsystem.component.DottedLabelDivider
import com.circuitstitch.deferno.core.designsystem.component.KindDot
import com.circuitstitch.deferno.core.designsystem.component.MonoMeta
import com.circuitstitch.deferno.core.designsystem.component.ProgressBarThin
import com.circuitstitch.deferno.core.designsystem.component.SectionLabel
import com.circuitstitch.deferno.core.designsystem.format.currentToday
import com.circuitstitch.deferno.core.designsystem.format.formatInstant
import com.circuitstitch.deferno.core.designsystem.resources.Res
import com.circuitstitch.deferno.core.designsystem.resources.activity_field_deadline
import com.circuitstitch.deferno.core.designsystem.resources.activity_field_title
import com.circuitstitch.deferno.core.designsystem.resources.activity_history_created
import com.circuitstitch.deferno.core.designsystem.resources.activity_history_folded_peer
import com.circuitstitch.deferno.core.designsystem.resources.activity_history_merged_into_parent
import com.circuitstitch.deferno.core.designsystem.resources.activity_history_merged_peer
import com.circuitstitch.deferno.core.designsystem.resources.activity_history_moved_peer
import com.circuitstitch.deferno.core.designsystem.resources.activity_history_parent_peer
import com.circuitstitch.deferno.core.designsystem.resources.activity_history_peer_unknown
import com.circuitstitch.deferno.core.designsystem.resources.activity_history_split_peer
import com.circuitstitch.deferno.core.designsystem.resources.activity_history_status_transition
import com.circuitstitch.deferno.core.designsystem.resources.activity_history_unknown
import com.circuitstitch.deferno.core.designsystem.resources.activity_history_updated
import com.circuitstitch.deferno.core.designsystem.resources.activity_history_updated_fields
import com.circuitstitch.deferno.core.designsystem.resources.common_add
import com.circuitstitch.deferno.core.designsystem.resources.common_cancel
import com.circuitstitch.deferno.core.designsystem.resources.common_clear
import com.circuitstitch.deferno.core.designsystem.resources.common_collapse_named_cd
import com.circuitstitch.deferno.core.designsystem.resources.common_delete
import com.circuitstitch.deferno.core.designsystem.resources.common_done
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
import com.circuitstitch.deferno.core.designsystem.resources.new_notes_label
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_add_caption
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_add_comment_placeholder
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_add_file
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_add_label_placeholder
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_add_subtask_placeholder
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_attachment_count
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_attachment_meta
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_attachment_meta_on_device
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_attachments_total
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_attachments_view
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
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_play
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_play_attachment_a11y
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_post
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_posting
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_property_owner
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_property_source
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_property_status
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_property_when
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_remove_caption_a11y
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_remove_label_a11y
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_section_attachments
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_filter_hide_done
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_section_subtasks
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_set_due_date
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_status_picker_title
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_status_row_a11y
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_tab_trail
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_trail_empty
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_due_days_ago
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_due_days_away
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_due_today
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_due_tomorrow
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_due_yesterday
import com.circuitstitch.deferno.core.designsystem.resources.tasks_journey_blocked
import com.circuitstitch.deferno.core.designsystem.resources.tasks_journey_done
import com.circuitstitch.deferno.core.designsystem.resources.tasks_journey_in_progress
import com.circuitstitch.deferno.core.designsystem.resources.tasks_journey_in_review
import com.circuitstitch.deferno.core.designsystem.resources.tasks_journey_not_doing
import com.circuitstitch.deferno.core.designsystem.resources.tasks_journey_todo
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
import com.circuitstitch.deferno.core.model.ItemHistoryEvent
import com.circuitstitch.deferno.core.model.ExternalRef
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.JourneyLabel
import com.circuitstitch.deferno.core.model.JourneySlot
import com.circuitstitch.deferno.core.model.JourneyStyle
import com.circuitstitch.deferno.core.model.RelativeDay
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.UserId
import com.circuitstitch.deferno.core.model.WorkingState
import com.circuitstitch.deferno.core.model.journeyStatus
import com.circuitstitch.deferno.core.model.relativeDay
import com.circuitstitch.deferno.feature.tasks.ActivityItem
import com.circuitstitch.deferno.feature.tasks.OnDeviceAttachment
import com.circuitstitch.deferno.feature.tasks.SubtaskRow
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
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

// --- Properties table (WHEN · STATUS · LABELS · OWNER · SOURCE) ---

/**
 * The Task detail's **properties table** (ADR-0044): a small-caps label column ruled off from the content,
 * rows divided by hairlines, wrapped in a rounded border — the calm "everything in one card" of the detail
 * mockup. WHEN (the deadline day + a relative-day suffix) and LABELS are inline-editable through the
 * [TaskDetailComponent] write seams ([onSetDeadline] / [onSetLabels], optimistic + offline-first); STATUS is
 * the read-only journey track whose whole row opens the status picker sheet. OWNER and SOURCE rows appear only
 * when the item carries them. Renders straight off the hydrated [task] fields — no new component state (#195).
 */
@Composable
internal fun PropertiesSection(
    task: Task,
    onSetDeadline: (LocalDate?) -> Unit,
    onSetLabels: (List<String>) -> Unit,
    onStatusRowClick: () -> Unit,
    ownerGroupCount: Int,
    // ATTACHMENTS now rides as the table's last row (rather than its own section below) — the file list +
    // "Add file" affordance in the content cell, the label column supplying the "ATTACHMENTS" heading.
    attachments: List<Attachment>,
    isUploadingAttachment: Boolean,
    onAddAttachment: () -> Unit,
    onDeleteAttachment: (String) -> Unit,
    onSetAttachmentCaption: (String, String?) -> Unit,
    onDeviceAttachments: List<OnDeviceAttachment> = emptyList(),
    onDeleteOnDeviceAttachment: (String) -> Unit = {},
    onPlayOnDeviceAttachment: (OnDeviceAttachment) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val statusLabel = journeyLabelText(journeyStatus(task.workingState, task.blocked).label)
    val statusA11y = stringResource(Res.string.tasks_detail_status_row_a11y, statusLabel)

    // Only the rows this item actually carries (ADR-0044): WHEN drops when no deadline is set; STATUS + LABELS
    // are always present; SOURCE only for an imported item. OWNER shows only for a shared / multi-group account
    // ([ownerGroupCount] > 1) — a single-group user's only group is their own personal org, so the row is noise.
    val rows = buildList<@Composable () -> Unit> {
        if (task.completeBy != null) {
            add {
                // WHEN: the deadline day + a relative-day suffix ("N days away"), editable through the picker.
                PropertyTableRow(label = stringResource(Res.string.tasks_detail_property_when)) {
                    DueCell(completeBy = task.completeBy, onSetDeadline = onSetDeadline)
                }
            }
        }
        add {
            // STATUS: the read-only journey track; tapping the whole row opens the status picker sheet.
            PropertyTableRow(
                label = stringResource(Res.string.tasks_detail_property_status),
                onClick = onStatusRowClick,
                onClickLabel = stringResource(Res.string.tasks_detail_status_picker_title),
                rowSemantics = statusA11y,
            ) {
                JourneyStatusIndicator(workingState = task.workingState, blocked = task.blocked)
            }
        }
        add {
            PropertyTableRow(label = stringResource(Res.string.common_labels)) {
                LabelsCell(labels = task.labels, onSetLabels = onSetLabels)
            }
        }
        // OWNER: the owning org — shown only for a shared / multi-group account (more than one group across
        // the cached items). A single-group user's only group is their personal org, so the row is hidden.
        if (ownerGroupCount > 1) {
            task.ownerOrgId?.let { owner ->
                add {
                    PropertyTableRow(label = stringResource(Res.string.tasks_detail_property_owner)) {
                        Text(
                            text = owner.value,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
        task.external?.let { external ->
            add {
                // SOURCE: the provenance mark + origin label for a synced/imported item; only when external.
                PropertyTableRow(label = stringResource(Res.string.tasks_detail_property_source)) {
                    SourceCell(external)
                }
            }
        }
        add {
            // ATTACHMENTS: always present (it holds the "Add file" affordance even with no files yet). A
            // compact summary — the count + combined size + View/Add buttons — matching the other rows'
            // columnar label|value shape; the full list (playback + delete) opens in the View sheet.
            PropertyTableRow(label = stringResource(Res.string.tasks_detail_section_attachments)) {
                AttachmentsCell(
                    attachments = attachments,
                    isUploading = isUploadingAttachment,
                    onAddClick = onAddAttachment,
                    onDelete = onDeleteAttachment,
                    onSetCaption = onSetAttachmentCaption,
                    onDeviceAttachments = onDeviceAttachments,
                    onDeleteOnDevice = onDeleteOnDeviceAttachment,
                    onPlayOnDevice = onPlayOnDeviceAttachment,
                )
            }
        }
    }
    Column(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium),
    ) {
        rows.forEachIndexed { i, row ->
            if (i > 0) PropertyTableDivider()
            row()
        }
    }
}

/** The fixed left label column of the properties table — wide enough for the longest label ("ATTACHMENTS"). */
private val PropLabelWidth = 116.dp

/**
 * One properties-table row: a tinted small-caps label cell ruled off from the content cell. When [onClick] is
 * set the whole row is tappable (the STATUS row → status picker) and announced as one node via [rowSemantics].
 */
@Composable
private fun PropertyTableRow(
    label: String,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    rowSemantics: String? = null,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .heightIn(min = MinTouchTarget)
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClickLabel = onClickLabel) { onClick() }
                } else {
                    Modifier
                },
            )
            .then(
                if (rowSemantics != null) {
                    Modifier.clearAndSetSemantics { contentDescription = rowSemantics }
                } else {
                    Modifier
                },
            ),
    ) {
        Box(
            Modifier
                .width(PropLabelWidth)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 12.dp, vertical = 14.dp),
        ) {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.defernoColors.inkMuted,
            )
        }
        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Box(
            Modifier
                .weight(1f)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            content()
        }
    }
}

/** The hairline between two properties-table rows. */
@Composable
private fun PropertyTableDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/**
 * The read-only **journey status** indicator (ADR-0044): a labelled three-node track — initial `TO-DO` → a
 * present marker → terminal `DONE`. Each slot label sits **above** the track, aligned over its node; the
 * active node is filled in its slot colour (`DONE` in the success green, a blocked middle in an error-red
 * four-point star), and the not-yet-reached nodes are a soft grey. A shelved (Dropped / `NOT DOING`) reading
 * draws a **dashed** tail to a **hollow, struck-through** `DONE` — "not headed to done". Colour is
 * reinforcement only — the reading carries text plus a `clearAndSetSemantics` contentDescription, so it is
 * never the sole signal. There is no `onClick`: state changes go through the status picker sheet.
 */
@Composable
internal fun JourneyStatusIndicator(
    workingState: WorkingState,
    blocked: Boolean,
    modifier: Modifier = Modifier,
) {
    val status = journeyStatus(workingState, blocked)
    val middleText = when (status.label) {
        JourneyLabel.InProgress -> stringResource(Res.string.tasks_journey_in_progress)
        JourneyLabel.InReview -> stringResource(Res.string.tasks_journey_in_review)
        JourneyLabel.Blocked -> stringResource(Res.string.tasks_journey_blocked)
        JourneyLabel.NotDoing -> stringResource(Res.string.tasks_journey_not_doing)
        // When the reading is at an endpoint the middle shows a muted "not there yet" hint.
        JourneyLabel.ToDo, JourneyLabel.Done -> stringResource(Res.string.tasks_journey_in_progress)
    }
    val a11y = journeyLabelText(status.label)

    val primary = MaterialTheme.colorScheme.primary
    val done = MaterialTheme.defernoColors.success
    val muted = MaterialTheme.defernoColors.inkMuted
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    // Soft grey fill for the not-yet-reached nodes (reinforced by the muted, non-bold labels above).
    val idle = muted.copy(alpha = 0.35f)
    val middleColor = when (status.style) {
        JourneyStyle.Blocked -> MaterialTheme.colorScheme.error
        JourneyStyle.NotDoing -> muted
        JourneyStyle.Normal -> primary
    }
    val shelved = status.style == JourneyStyle.NotDoing

    Column(
        modifier = modifier.clearAndSetSemantics { contentDescription = a11y },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // The labels overlaid above the track, each aligned over its node (start · center · end). A Box rather
        // than equal thirds so a long middle label ("IN-PROGRESS") isn't capped to a third and truncated — the
        // short end labels (TO-DO / DONE) leave the centre room.
        Box(Modifier.fillMaxWidth()) {
            JourneyLabel(
                text = stringResource(Res.string.tasks_journey_todo),
                active = status.slot == JourneySlot.Initial,
                color = primary, muted = muted,
                modifier = Modifier.align(Alignment.CenterStart),
            )
            JourneyLabel(
                text = middleText,
                active = status.slot == JourneySlot.Middle,
                color = middleColor, muted = muted,
                modifier = Modifier.align(Alignment.Center),
            )
            JourneyLabel(
                text = stringResource(Res.string.tasks_journey_done),
                active = status.slot == JourneySlot.Terminal,
                color = done, muted = muted, struck = shelved,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
        // The track: a hairline through three nodes at the start / centre / end.
        Canvas(Modifier.fillMaxWidth().height(JourneyTrackHeight)) {
            val cy = size.height / 2f
            val r = JourneyNodeRadius.toPx()
            val left = Offset(r, cy)
            val mid = Offset(size.width / 2f, cy)
            val right = Offset(size.width - r, cy)
            val stroke = JourneyTrackStroke.toPx()
            drawLine(lineColor, left, mid, stroke)
            drawLine(
                lineColor, mid, right, stroke,
                pathEffect = if (shelved) {
                    val d = JourneyDash.toPx()
                    PathEffect.dashPathEffect(floatArrayOf(d, d))
                } else {
                    null
                },
            )
            journeyNode(left, active = status.slot == JourneySlot.Initial, color = primary, idle = idle, radius = r)
            if (status.style == JourneyStyle.Blocked) {
                journeyStar(mid, JourneyStarRadius.toPx(), middleColor)
            } else {
                journeyNode(mid, active = status.slot == JourneySlot.Middle, color = middleColor, idle = idle, radius = r)
            }
            if (shelved) {
                // Not headed to done — a hollow ring rather than a filled node.
                drawCircle(muted, r, right, style = Stroke(width = stroke))
            } else {
                journeyNode(right, active = status.slot == JourneySlot.Terminal, color = done, idle = idle, radius = r)
            }
        }
    }
}

/** One journey slot label above its node; coloured + semibold only when it is the active slot. */
@Composable
private fun JourneyLabel(
    text: String,
    active: Boolean,
    color: Color,
    muted: Color,
    modifier: Modifier = Modifier,
    struck: Boolean = false,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        color = if (active) color else muted,
        textDecoration = if (struck) TextDecoration.LineThrough else TextDecoration.None,
        maxLines = 1,
        softWrap = false,
        modifier = modifier,
    )
}

/** A filled track node — the slot colour when active, a soft grey when the reading has not reached it. */
private fun DrawScope.journeyNode(center: Offset, active: Boolean, color: Color, idle: Color, radius: Float) {
    drawCircle(if (active) color else idle, radius, center)
}

/** The blocked marker: a four-point star ("stuck") on the middle node, drawn in the error colour. */
private fun DrawScope.journeyStar(center: Offset, outer: Float, color: Color) {
    val inner = outer * 0.4f
    val path = Path()
    for (i in 0 until 8) {
        val rad = if (i % 2 == 0) outer else inner
        val a = ((-90f + i * 45f) * PI / 180f).toFloat()
        path.run { if (i == 0) moveTo(center.x + rad * cos(a), center.y + rad * sin(a)) else lineTo(center.x + rad * cos(a), center.y + rad * sin(a)) }
    }
    path.close()
    drawPath(path, color)
}

private val JourneyTrackHeight = 18.dp
private val JourneyNodeRadius = 5.dp
private val JourneyStarRadius = 8.dp
private val JourneyTrackStroke = 1.5.dp
private val JourneyDash = 4.dp

/** The display-only journey label (ADR-0044): a [JourneyLabel] typed code → its `tasks_journey_*` string. */
@Composable
private fun journeyLabelText(label: JourneyLabel): String = stringResource(
    when (label) {
        JourneyLabel.ToDo -> Res.string.tasks_journey_todo
        JourneyLabel.InProgress -> Res.string.tasks_journey_in_progress
        JourneyLabel.InReview -> Res.string.tasks_journey_in_review
        JourneyLabel.Done -> Res.string.tasks_journey_done
        JourneyLabel.NotDoing -> Res.string.tasks_journey_not_doing
        JourneyLabel.Blocked -> Res.string.tasks_journey_blocked
    },
)

/** The relative-day reading (ADR-0044) mapped to its localized string — discrete keys, plurals for N days. */
@Composable
private fun relativeDayText(rel: RelativeDay): String = when (rel) {
    RelativeDay.Today -> stringResource(Res.string.tasks_detail_due_today)
    RelativeDay.Tomorrow -> stringResource(Res.string.tasks_detail_due_tomorrow)
    RelativeDay.Yesterday -> stringResource(Res.string.tasks_detail_due_yesterday)
    is RelativeDay.DaysAway -> pluralStringResource(Res.plurals.tasks_detail_due_days_away, rel.days, rel.days)
    is RelativeDay.DaysAgo -> pluralStringResource(Res.plurals.tasks_detail_due_days_ago, rel.days, rel.days)
}

/**
 * The status picker sheet (ADR-0044): a modal bottom sheet listing the five [WorkingState]s (via
 * [workingStateLabel]) with the [current] one marked; tapping a row forwards [onSelect] then [onDismiss].
 * This is the only way to reach Dropped now the inline chips + the kebab's "Set aside" are gone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StatusPickerSheet(
    current: WorkingState,
    onSelect: (WorkingState) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(Res.string.tasks_detail_status_picker_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 8.dp).semantics { heading() },
            )
            WorkingState.entries.forEach { s ->
                val selected = s == current
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = MinTouchTarget)
                        .clickable { onSelect(s) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = workingStateLabel(s),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    if (selected) {
                        Icon(
                            imageVector = DefernoIcons.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The SOURCE cell content: the provider [SourceIndicator] mark + the origin label (the `owner/repo#N` tracker
 * ref, or the provider label for a non-tracker), opening the provider URL when present. The label underlines
 * only when it links somewhere. Read-only — provenance, not an editor.
 */
@Composable
private fun SourceCell(external: ExternalRef) {
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
        SourceIndicator(external.source)
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textDecoration = if (url != null) TextDecoration.Underline else TextDecoration.None,
        )
    }
}

/**
 * The WHEN cell content: the deadline day + a relative-day suffix (or a muted "—"). Tapping the value opens a
 * Material3 [DatePickerDialog] seeded from [completeBy]; confirming forwards the picked day, and a Clear
 * affordance forwards `null` to drop the deadline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DueCell(completeBy: Instant?, onSetDeadline: (LocalDate?) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    // WHEN (ADR-0044): the absolute deadline day + a relative-day suffix ("· In 3 days" / "· Yesterday").
    val today = currentToday
    val display = completeBy?.let { instant ->
        val date = instant.toDisplayDate()
        "$date  ·  ${relativeDayText(relativeDay(instant, today = today))}"
    } ?: "—"
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val dueDateA11y = stringResource(Res.string.tasks_detail_due_date_a11y, display)
        Text(
            text = display,
            style = MaterialTheme.typography.bodyLarge,
            color = if (completeBy == null) MaterialTheme.defernoColors.inkMuted else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .weight(1f)
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
 * The LABELS cell (ADR-0044): **read-only by default** — the labels as calm filled chips (matching the design
 * mockup) — with a trailing **Edit** toggle. Tapping Edit reveals removable [InputChip]s + an inline "add
 * label" field; **Done** returns to read-only. On any add or remove the whole updated list (trimmed, blanks +
 * duplicates dropped) is forwarded through [onSetLabels] — the component replaces the Task's labels wholesale.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LabelsCell(labels: List<String>, onSetLabels: (List<String>) -> Unit) {
    fun normalize(list: List<String>): List<String> =
        list.map { it.trim() }.filter { it.isNotBlank() }.distinct()

    var editing by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Box(Modifier.weight(1f).padding(top = 4.dp)) {
                when {
                    labels.isNotEmpty() -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        labels.forEach { label ->
                            if (editing) {
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
                            } else {
                                LabelChip(label)
                            }
                        }
                    }
                    // Empty + read-only: a muted em dash, like the WHEN cell's "no value".
                    !editing -> Text(
                        text = "—",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.defernoColors.inkMuted,
                    )
                }
            }
            TextButton(onClick = { editing = !editing }) {
                Text(stringResource(if (editing) Res.string.common_done else Res.string.common_edit))
            }
        }
        if (editing) {
            AddLabelField(onAdd = { entry -> onSetLabels(normalize(labels + entry)) })
        }
    }
}

/** A calm read-only label pill (ADR-0044 mockup): the label text in a filled, rounded chip. */
@Composable
private fun LabelChip(label: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
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
    // The "Hide done" filter (#197): drops Done rows from the outline; defaults off so other callers/tests
    // needn't wire it. The progress count above is unaffected (it still spans the whole subtree).
    hideDone: Boolean = false,
    onSetHideDone: (Boolean) -> Unit = {},
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                FilterChip(
                    selected = hideDone,
                    onClick = { onSetHideDone(!hideDone) },
                    label = { Text(stringResource(Res.string.tasks_detail_filter_hide_done)) },
                )
                if (hideDone) {
                    // Shown/total (the hidden Done rows are total − done) — the filter's "(n/m)".
                    Text(
                        text = stringResource(Res.string.tasks_progress_fraction, total - done, total),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.defernoColors.inkMuted,
                    )
                }
            }
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
 * The ATTACHMENTS cell (ADR-0044): the value half of the properties table's attachments row — a **compact
 * summary** (the count + combined size) over two actions: **View** (opens the [AttachmentsSheet] with the full
 * list — playback, delete, caption edit) and **Add file** (the platform file picker; the picker + byte read
 * are the host's androidMain glue). [isUploading] disables Add while a PUT is in flight. Keeping the list off
 * the row lets the attachments live in the narrow value column like the other properties (LABELS / SOURCE).
 */
@Composable
private fun AttachmentsCell(
    attachments: List<Attachment>,
    isUploading: Boolean,
    onAddClick: () -> Unit,
    onDelete: (String) -> Unit,
    onSetCaption: (String, String?) -> Unit,
    // On-device attachments (#211, e.g. a retained brain-dump recording). Folded into the count + size and
    // shown in the View sheet (they have no signed URL, so audio plays locally). Empty on platforms without
    // on-device capture (desktop/iOS).
    onDeviceAttachments: List<OnDeviceAttachment> = emptyList(),
    onDeleteOnDevice: (String) -> Unit = {},
    onPlayOnDevice: (OnDeviceAttachment) -> Unit = {},
) {
    var showSheet by remember { mutableStateOf(false) }
    val count = attachments.size + onDeviceAttachments.size
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (count == 0) {
            Text(
                stringResource(Res.string.tasks_detail_no_attachments),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.defernoColors.inkMuted,
            )
        } else {
            // Count over combined size, stacked — the narrow value column can't hold both side by side.
            val totalBytes = attachments.sumOf { it.size } + onDeviceAttachments.sumOf { it.size }
            Text(
                text = pluralStringResource(Res.plurals.tasks_detail_attachment_count, count, count),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            MonoMeta(text = stringResource(Res.string.tasks_detail_attachments_total, formatBytes(totalBytes)))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (count > 0) {
                TextButton(onClick = { showSheet = true }) {
                    Text(stringResource(Res.string.tasks_detail_attachments_view))
                }
            }
            TextButton(onClick = onAddClick, enabled = !isUploading) {
                Text(
                    if (isUploading) {
                        stringResource(Res.string.tasks_detail_uploading)
                    } else {
                        stringResource(Res.string.tasks_detail_add_file)
                    },
                )
            }
        }
    }
    if (showSheet) {
        AttachmentsSheet(
            attachments = attachments,
            onDeviceAttachments = onDeviceAttachments,
            onDelete = onDelete,
            onSetCaption = onSetCaption,
            onDeleteOnDevice = onDeleteOnDevice,
            onPlayOnDevice = onPlayOnDevice,
            onDismiss = { showSheet = false },
        )
    }
}

/**
 * The **View attachments** sheet (ADR-0044): a modal bottom sheet with the full attachment list the compact
 * cell summarises — each synced file (open / delete / caption) and each on-device recording (play / delete).
 * Adding a file stays on the row (its picker is the host's glue); this sheet is view + manage only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttachmentsSheet(
    attachments: List<Attachment>,
    onDeviceAttachments: List<OnDeviceAttachment>,
    onDelete: (String) -> Unit,
    onSetCaption: (String, String?) -> Unit,
    onDeleteOnDevice: (String) -> Unit,
    onPlayOnDevice: (OnDeviceAttachment) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                text = stringResource(Res.string.tasks_detail_section_attachments),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 8.dp).semantics { heading() },
            )
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

// --- Trail: one merged, reverse-chronological comments + enriched-history feed (ADR-0046) ---

/**
 * The **Trail** (ADR-0046): the single, interleaved comments + server-history feed — the whole
 * [ActivityItem] list the component already merged and sorted **newest-first** (no filterIsInstance split,
 * no coalescing; every row stays). The comment [CommentComposer] sits **inline at the top** (non-sticky,
 * first slot); below it the feed renders top-to-bottom, each row a comment (its own Edit/Delete when
 * [currentUserId] owns it) or an enriched, glyph-prefixed history line. Reads from the cache (offline-first)
 * — no error state; [loading] flags the best-effort on-open refresh only while the feed is still empty.
 */
@Composable
internal fun TrailSection(
    activity: List<ActivityItem>,
    currentUserId: UserId?,
    loading: Boolean,
    isPosting: Boolean,
    onPost: (String) -> Unit,
    onEdit: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
    // The Android FAB's "Add comment" focus target (mirrors [SubtasksSection]'s addSubtaskFocus): threaded
    // into the top composer so revealing the Trail tab focuses it. Null when no host drives a reveal.
    commentFocus: FocusRequester? = null,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionHeader(stringResource(Res.string.tasks_detail_tab_trail), trailing = activity.size.toString())
        CommentComposer(isPosting = isPosting, onPost = onPost, focusRequester = commentFocus)
        when {
            loading && activity.isEmpty() -> MutedLine(stringResource(Res.string.common_loading))
            activity.isEmpty() -> MutedLine(stringResource(Res.string.tasks_detail_trail_empty))
            else -> {
                // Group by the device-local day (same zone the row time uses, so a row never lands under
                // the wrong header at a day boundary); the header carries the date, each row just its time.
                val timePattern = stringResource(Res.string.common_time_pattern)
                val today = currentToday.toString() // device-zone ISO, matches trailDay()
                val todayLabel = stringResource(Res.string.tasks_detail_due_today).uppercase()
                activity.groupBy { it.at.trailDay() }.forEach { (day, rows) ->
                    DottedLabelDivider(
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 2.dp),
                        startLabel = day,
                        centerLabel = if (day == today) todayLabel else null,
                    )
                    rows.forEach { item ->
                        key(item.id) {
                            when (item) {
                                is ActivityItem.Comment -> CommentRow(
                                    item.comment,
                                    isMine = currentUserId != null && item.comment.createdBy == currentUserId,
                                    time = item.at.trailTime(timePattern),
                                    onEdit = onEdit, onDelete = onDelete,
                                )
                                is ActivityItem.HistoryEvent -> HistoryEventRow(item, time = item.at.trailTime(timePattern))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * A read-only server-history row (ADR-0046) — a **leading unicode glyph** by kind (no material-icons
 * dependency) then the **enriched, payload-rendered** line: the status transition in the editor vocabulary,
 * the peer-title verbs (resolved off the local cache, "another item" when aged out), or the humanized
 * changed-field list, with the recorded date trailing.
 */
@Composable
private fun HistoryEventRow(item: ActivityItem.HistoryEvent, time: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = historyGlyph(item.event),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.defernoColors.inkMuted,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = historyLabel(item.event, item.peerTitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.defernoColors.inkMuted,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = time,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.defernoColors.inkMuted,
        )
    }
}

/**
 * The leading unicode glyph for a history event (ADR-0046) — a dependency-free kind marker (no
 * material-icons). Plain [String] constants, not localized text (decorative, like the "×"/"—" glyphs
 * elsewhere in this file).
 */
private fun historyGlyph(event: ItemHistoryEvent): String = when (event) {
    is ItemHistoryEvent.Created -> "●" // ●
    is ItemHistoryEvent.Updated -> "✎" // ✎
    is ItemHistoryEvent.StatusChanged -> "↻" // ↻
    is ItemHistoryEvent.Split -> "✂" // ✂
    is ItemHistoryEvent.Moved -> "→" // →
    is ItemHistoryEvent.ParentAssigned -> "◈" // ◈
    is ItemHistoryEvent.FoldedInto -> "≡" // ≡
    is ItemHistoryEvent.MergedChild -> "≡" // ≡
    is ItemHistoryEvent.MergedIntoParent -> "≡" // ≡
    is ItemHistoryEvent.Unknown -> "…" // …
}

/**
 * The enriched history line (ADR-0046) — the typed-code → localized-string mapping, rendering the wire
 * payload the old coarse `label()` discarded. [StatusChanged] shows the from→to transition in the editor
 * vocabulary (the reused status-picker labels via [workingStateLabel]); the structural peer events show the
 * resolved [peerTitle] (degrading to "another item"); [Updated] names the humanized changed field(s) (generic
 * fallback on an unknown token); [Created] / [MergedIntoParent] / [Unknown] keep their generic labels.
 */
@Composable
private fun historyLabel(event: ItemHistoryEvent, peerTitle: String?): String {
    val peer = peerTitle ?: stringResource(Res.string.activity_history_peer_unknown)
    return when (event) {
        is ItemHistoryEvent.Created -> stringResource(Res.string.activity_history_created)
        is ItemHistoryEvent.MergedIntoParent -> stringResource(Res.string.activity_history_merged_into_parent)
        is ItemHistoryEvent.Unknown -> stringResource(Res.string.activity_history_unknown)
        is ItemHistoryEvent.StatusChanged -> stringResource(
            Res.string.activity_history_status_transition,
            workingStateLabel(event.from),
            workingStateLabel(event.to),
        )
        is ItemHistoryEvent.Split -> stringResource(Res.string.activity_history_split_peer, peer)
        is ItemHistoryEvent.Moved -> stringResource(Res.string.activity_history_moved_peer, peer)
        is ItemHistoryEvent.ParentAssigned -> stringResource(Res.string.activity_history_parent_peer, peer)
        is ItemHistoryEvent.FoldedInto -> stringResource(Res.string.activity_history_folded_peer, peer)
        is ItemHistoryEvent.MergedChild -> stringResource(Res.string.activity_history_merged_peer, peer)
        is ItemHistoryEvent.Updated -> updatedLabel(event.fields)
    }
}

/**
 * The [ItemHistoryEvent.Updated] line: "Edited: X, Y" over the humanized changed-field names, or the generic
 * "Edited this task" when the field list is empty or carries any token this View can't humanize (never leak
 * raw snake_case). No old→new values exist on the wire.
 */
@Composable
private fun updatedLabel(fields: List<String>): String {
    val names = ArrayList<String>(fields.size)
    var unknown = fields.isEmpty()
    for (token in fields) {
        val label = fieldLabel(token)
        if (label == null) unknown = true else names.add(label)
    }
    return if (unknown) {
        stringResource(Res.string.activity_history_updated)
    } else {
        stringResource(Res.string.activity_history_updated_fields, names.joinToString(", "))
    }
}

/** Humanizes a raw wire field token to a localized name (reusing property labels), or `null` if unknown. */
@Composable
private fun fieldLabel(token: String): String? = when (token) {
    "title" -> stringResource(Res.string.activity_field_title)
    "description", "notes" -> stringResource(Res.string.new_notes_label)
    "deadline", "complete_by" -> stringResource(Res.string.activity_field_deadline)
    "labels" -> stringResource(Res.string.common_labels)
    else -> null
}

@Composable
private fun CommentComposer(
    isPosting: Boolean,
    onPost: (String) -> Unit,
    // The Android FAB's "Add comment" focus target — applied to the composer field so a reveal focuses it and
    // pops the keyboard. Null (desktop + existing callers) leaves the field un-driven.
    focusRequester: FocusRequester? = null,
) {
    var text by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = text,
            onValueChange = { if (it.length <= MaxCommentLength) text = it },
            placeholder = { Text(stringResource(Res.string.tasks_detail_add_comment_placeholder)) },
            enabled = !isPosting,
            modifier = Modifier.fillMaxWidth()
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
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
    time: String,
    onEdit: (String, String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var editing by remember(comment.id) { mutableStateOf(false) }
    DetailCard {
      Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The Trail's comment glyph (ADR-0046, U+1F4AC) — decorative, no material-icons dependency.
            Text(text = "💬", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(8.dp))
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
                text = time +
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

/**
 * The Trail group key — the ISO local day (device zone) an instant falls on. ISO on purpose (a machine
 * date divider, not localized prose); zoned so it matches [trailTime], keeping a row under the header for
 * the same day its time reads.
 */
private fun kotlin.time.Instant.trailDay(): String = formatInstant(this, "yyyy-MM-dd")

/** The Trail row time — the instant's clock time in the device zone/locale (pattern from `common_time_pattern`). */
private fun kotlin.time.Instant.trailTime(pattern: String): String = formatInstant(this, pattern)
