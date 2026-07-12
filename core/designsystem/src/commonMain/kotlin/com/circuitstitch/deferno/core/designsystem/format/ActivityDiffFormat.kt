package com.circuitstitch.deferno.core.designsystem.format

import androidx.compose.runtime.Composable
import com.circuitstitch.deferno.core.designsystem.component.DiffRow
import com.circuitstitch.deferno.core.designsystem.component.DiffValue
import com.circuitstitch.deferno.core.designsystem.resources.Res
import com.circuitstitch.deferno.core.designsystem.resources.activity_field_deadline
import com.circuitstitch.deferno.core.designsystem.resources.activity_field_pinned
import com.circuitstitch.deferno.core.designsystem.resources.activity_field_status
import com.circuitstitch.deferno.core.designsystem.resources.activity_field_title
import com.circuitstitch.deferno.core.designsystem.resources.activity_value_pinned
import com.circuitstitch.deferno.core.designsystem.resources.activity_value_unpinned
import com.circuitstitch.deferno.core.designsystem.resources.activity_when_pattern
import com.circuitstitch.deferno.core.designsystem.resources.common_labels
import com.circuitstitch.deferno.core.designsystem.resources.common_status_done
import com.circuitstitch.deferno.core.designsystem.resources.common_status_in_progress
import com.circuitstitch.deferno.core.designsystem.resources.common_status_in_review
import com.circuitstitch.deferno.core.designsystem.resources.new_notes_label
import com.circuitstitch.deferno.core.designsystem.resources.tasks_set_aside
import com.circuitstitch.deferno.core.designsystem.resources.tasks_working_state_open
import com.circuitstitch.deferno.core.model.ActivityField
import com.circuitstitch.deferno.core.model.ActivityFieldChange
import com.circuitstitch.deferno.core.model.ActivityFieldValue
import kotlin.time.Instant
import org.jetbrains.compose.resources.stringResource

/**
 * The one shared old->new change formatter (#260) — both the Activity destination and the Task Trail render
 * their captured [ActivityFieldChange] diffs through this single implementation (ADR-0004 #27), so the two
 * surfaces can never drift on a field's label or how its value reads. It maps the typed core/model change
 * onto the design system's generic, model-agnostic [DiffRow] contract the change-detail sheet consumes.
 *
 * The captured field diff → the change-detail sheet's rows: recognized fields only ([ActivityField.Unknown]
 * dropped), each with its localized label and both sides formatted per field (a deadline date, a status
 * label, pinned yes/no).
 */
@Composable
fun List<ActivityFieldChange>.toDiffRows(): List<DiffRow> =
    filter { it.field != ActivityField.Unknown }.map { change ->
        DiffRow(
            label = activityFieldLabel(change.field, change.rawKey),
            before = change.before.toDiffValue(change.field),
            after = change.after.toDiffValue(change.field),
        )
    }

/** The recognized changed-field names joined for a row's inline hint ("Title, Description"), or null if none. */
@Composable
fun List<ActivityFieldChange>.changedFieldHint(): String? {
    val names = filter { it.field != ActivityField.Unknown }.map { activityFieldLabel(it.field, it.rawKey) }
    return if (names.isEmpty()) null else names.joinToString(", ")
}

/** The localized label for a changed [field] — reuses the property vocabulary; [ActivityField.Unknown] shows its [rawKey]. */
@Composable
private fun activityFieldLabel(field: ActivityField, rawKey: String): String = when (field) {
    ActivityField.Title -> stringResource(Res.string.activity_field_title)
    ActivityField.Description -> stringResource(Res.string.new_notes_label)
    ActivityField.Deadline -> stringResource(Res.string.activity_field_deadline)
    ActivityField.Labels -> stringResource(Res.string.common_labels)
    ActivityField.Status -> stringResource(Res.string.activity_field_status)
    ActivityField.Pinned -> stringResource(Res.string.activity_field_pinned)
    ActivityField.Unknown -> rawKey
}

/** One captured value → its display side, formatted per [field] (a deadline date, a status label, pinned yes/no). */
@Composable
private fun ActivityFieldValue.toDiffValue(field: ActivityField): DiffValue = when (this) {
    ActivityFieldValue.Cleared -> DiffValue.Cleared
    ActivityFieldValue.Unavailable -> DiffValue.Unavailable
    is ActivityFieldValue.Present -> DiffValue.Text(formatFieldValue(field, raw))
}

@Composable
private fun formatFieldValue(field: ActivityField, raw: String): String = when (field) {
    ActivityField.Deadline -> {
        val pattern = stringResource(Res.string.activity_when_pattern)
        runCatching { formatInstant(Instant.parse(raw), pattern) }.getOrDefault(raw)
    }
    ActivityField.Status -> activityStatusLabel(raw)
    ActivityField.Pinned ->
        stringResource(if (raw == "true") Res.string.activity_value_pinned else Res.string.activity_value_unpinned)
    else -> raw
}

/**
 * A localized label for a Task working-state **wire token** (`open`/`in-progress`/`in-review`/`done`/
 * `dropped`) — the shared status formatter the Activity + Trail change diffs use to render a captured
 * `status` value (#260). Reuses the editor's status vocabulary; an unrecognised token degrades to itself.
 */
@Composable
fun activityStatusLabel(wireToken: String): String = when (wireToken) {
    "open" -> stringResource(Res.string.tasks_working_state_open)
    "in-progress" -> stringResource(Res.string.common_status_in_progress)
    "in-review" -> stringResource(Res.string.common_status_in_review)
    "done" -> stringResource(Res.string.common_status_done)
    "dropped" -> stringResource(Res.string.tasks_set_aside)
    else -> wireToken
}
