package com.circuitstitch.deferno.shell.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.circuitstitch.deferno.core.designsystem.resources.Res
import com.circuitstitch.deferno.core.designsystem.resources.ic_mic
import com.circuitstitch.deferno.core.designsystem.theme.defernoColors
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.shell.DictationField
import com.circuitstitch.deferno.shell.DictationStatus
import com.circuitstitch.deferno.shell.NewState
import com.circuitstitch.deferno.shell.NewStatus
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import org.jetbrains.compose.resources.painterResource

// The shared, stateless **New**-form atoms (#175): the platform-neutral pieces of the New create
// surface (#71/#87, ADR-0015/0016) extracted to this module's commonMain so the binding to
// [com.circuitstitch.deferno.shell.NewComponent]'s state exists ONCE — the ADR-0004 #27 `:ui`
// pattern applied to the shell. All state is hoisted: each atom takes (state, callbacks) and only
// renders. The Android overlay (app/androidApp `NewScreen`) and the desktop window (app/desktopApp
// `NewDesktopScreen`) are chrome around these: layout + platform affordances (the Android
// RECORD_AUDIO prompt and OS-settings intent; the desktop's sidecar-settled TCC and System
// Settings deep-link) — and the platform voice of the Dictation notes, passed in as plain strings.

/**
 * The explicit Task/Habit/Chore/Event kind picker (ADR-0015): a segmented row of [FilterChip]s,
 * defaulting to Task at the component — **never field-inference** (design-principle #5).
 */
@Composable
fun NewKindPicker(
    selectedKind: ItemKind,
    onSelectKind: (ItemKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().semantics { contentDescription = "Kind picker" },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ItemKind.entries.forEach { kind ->
            FilterChip(
                selected = selectedKind == kind,
                onClick = { onSelectKind(kind) },
                label = { Text(kind.pickerLabel) },
            )
        }
    }
}

/**
 * The Title row: a single-line field carrying the per-field [[Dictation]] mic affordance (#92,
 * ADR-0018) when the engine is available. While dictating into Title, spoken English streams as
 * partial [[Transcript]] text into [NewState.title] (the component owns that binding — the streamed
 * text simply arrives through `state.title` here) and the mic shows as active ("Stop dictation").
 */
@Composable
fun NewTitleField(
    state: NewState,
    onTitleChange: (String) -> Unit,
    onMic: (DictationField) -> Unit,
    modifier: Modifier = Modifier,
) {
    DictationTextField(
        value = state.title,
        onValueChange = onTitleChange,
        label = "Title",
        singleLine = true,
        field = DictationField.Title,
        state = state,
        onMic = onMic,
        modifier = modifier,
    )
}

/** The Notes row: the multi-line counterpart of [NewTitleField], dictating into [NewState.notes]. */
@Composable
fun NewNotesField(
    state: NewState,
    onNotesChange: (String) -> Unit,
    onMic: (DictationField) -> Unit,
    modifier: Modifier = Modifier,
) {
    DictationTextField(
        value = state.notes,
        onValueChange = onNotesChange,
        label = "Notes",
        singleLine = false,
        field = DictationField.Notes,
        state = state,
        onMic = onMic,
        modifier = modifier,
    )
}

/**
 * A text row with the trailing [[Dictation]] mic: the mic is offered only while the engine is
 * available ([NewState.dictationAvailable]) and renders as listening only when dictation is active
 * **into this [field]** — the shared focused-field binding both platforms previously re-derived.
 */
@Composable
private fun DictationTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    singleLine: Boolean,
    field: DictationField,
    state: NewState,
    onMic: (DictationField) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = singleLine,
        trailingIcon = {
            if (state.dictationAvailable) {
                MicButton(
                    field = field,
                    listening = state.dictation == DictationStatus.Listening && state.dictationField == field,
                    onClick = onMic,
                )
            }
        },
        modifier = modifier.fillMaxWidth().semantics { contentDescription = label },
    )
}

/**
 * The per-field [[Dictation]] mic (#92/#94). Tinted primary while [listening] (and labelled
 * "Stop dictation" so a tap toggles it off), muted otherwise. The glyph is the shared design-system
 * [Res.drawable.ic_mic] (material-icons-core has no Mic), recoloured by [Icon]'s tint.
 */
@Composable
private fun MicButton(
    field: DictationField,
    listening: Boolean,
    onClick: (DictationField) -> Unit,
) {
    IconButton(onClick = { onClick(field) }) {
        Icon(
            painter = painterResource(Res.drawable.ic_mic),
            contentDescription = if (listening) "Stop dictation" else "Dictate",
            tint = if (listening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The gentle Dictation status line (#92/#94, ADR-0018): a "needs microphone access" note on a denial
 * — with an OS-settings deep-link when permanently foreclosed — or a soft recognition-error note,
 * never a silent failure. Silent (nothing rendered) while idle or listening: the streaming text
 * itself is the listening feedback. The note wording and the settings-link label are the platform's
 * voice ([deniedNote] / [permanentlyDeniedNote] / [openSettingsLabel]); [onOpenSettings] routes to
 * the platform's settings surface (the Android app-details intent, the macOS Privacy pane).
 */
@Composable
fun NewDictationMessage(
    status: DictationStatus,
    deniedNote: String,
    permanentlyDeniedNote: String,
    openSettingsLabel: String,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (status) {
        DictationStatus.PermissionDenied -> DictationNote(deniedNote, modifier)
        DictationStatus.PermissionPermanentlyDenied -> Column(modifier.fillMaxWidth()) {
            DictationNote(permanentlyDeniedNote)
            TextButton(
                onClick = onOpenSettings,
                modifier = Modifier.semantics { contentDescription = openSettingsLabel },
            ) { Text(openSettingsLabel) }
        }
        is DictationStatus.Error -> DictationNote("Couldn't hear that — try the mic again.", modifier)
        DictationStatus.Idle, DictationStatus.Listening -> Unit
    }
}

@Composable
private fun DictationNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.defernoColors.inkMuted,
        modifier = modifier.fillMaxWidth().semantics { contentDescription = "Dictation status" },
    )
}

/**
 * The Date row (#74) — the Task/Habit/Chore `complete_by` anchor the Calendar FAB pre-dates. An ISO
 * `yyyy-mm-dd` text input: a parseable value pushes a real [LocalDate] up via [onValueChange], an
 * unparseable one clears it (so a half-typed value never POSTs an invalid date). Pre-filled from
 * [value]; a native date picker is a follow-up.
 */
@Composable
fun NewDateField(
    value: LocalDate?,
    onValueChange: (LocalDate?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember(value) { mutableStateOf(value?.toString() ?: "") }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onValueChange(runCatching { LocalDate.parse(it.trim()) }.getOrNull())
        },
        label = { Text("Date (optional, e.g. 2026-06-08)") },
        singleLine = true,
        isError = text.isNotBlank() && runCatching { LocalDate.parse(text.trim()) }.getOrNull() == null,
        modifier = modifier.fillMaxWidth().semantics { contentDescription = "Date" },
    )
}

/**
 * The Task/Habit/Chore **deadline time-of-day** row (#348): an `HH:MM` text input that maps to a
 * [LocalTime] via the same parse-or-clear contract as [NewDateField] — a parseable value pushes a
 * real [LocalTime] up, an unparseable/blank one clears it (an all-day deadline). Shown beneath the
 * Date for the non-Event kinds; an Event's clock lives in its start/end instants instead. A native
 * time picker is a follow-up (consistent with the ISO date/instant text entry).
 */
@Composable
fun NewDeadlineTimeField(
    value: LocalTime?,
    onValueChange: (LocalTime?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember(value) { mutableStateOf(value?.toString() ?: "") }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onValueChange(runCatching { LocalTime.parse(it.trim()) }.getOrNull())
        },
        label = { Text("Time (optional, e.g. 14:30)") },
        singleLine = true,
        isError = text.isNotBlank() && runCatching { LocalTime.parse(text.trim()) }.getOrNull() == null,
        modifier = modifier.fillMaxWidth().semantics { contentDescription = "Deadline time" },
    )
}

/**
 * The Event's required fixed start (CONTEXT.md → Event; AC #2): an ISO-8601 instant row — see
 * [InstantField] for the parse-or-clear contract.
 */
@Composable
fun NewEventStartField(
    value: Instant?,
    onValueChange: (Instant?) -> Unit,
    modifier: Modifier = Modifier,
) {
    InstantField(
        value = value,
        onValueChange = onValueChange,
        label = "Starts (e.g. 2026-06-08T09:00:00Z)",
        semanticsLabel = "Event start",
        modifier = modifier,
    )
}

/** The Event's optional end: the [NewEventStartField] counterpart for the window's close. */
@Composable
fun NewEventEndField(
    value: Instant?,
    onValueChange: (Instant?) -> Unit,
    modifier: Modifier = Modifier,
) {
    InstantField(
        value = value,
        onValueChange = onValueChange,
        label = "Ends (optional)",
        semanticsLabel = "Event end",
        modifier = modifier,
    )
}

/**
 * An ISO-8601 instant text input (the Event start/end, FIX 1/AC #2). The user types an RFC3339
 * instant; a parseable value is pushed up via [onValueChange] as a real [Instant], an unparseable
 * one clears it (so a half-typed value never POSTs an invalid `complete_by`). v1 entry shape — a
 * native date-time picker is a follow-up; the component stays Compose-free and unit-tested on
 * [Instant]s directly.
 */
@Composable
private fun InstantField(
    value: Instant?,
    onValueChange: (Instant?) -> Unit,
    label: String,
    semanticsLabel: String,
    modifier: Modifier = Modifier,
) {
    var text by remember(value) { mutableStateOf(value?.toString() ?: "") }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onValueChange(runCatching { Instant.parse(it.trim()) }.getOrNull())
        },
        label = { Text(label) },
        singleLine = true,
        isError = text.isNotBlank() && runCatching { Instant.parse(text.trim()) }.getOrNull() == null,
        modifier = modifier.fillMaxWidth().semantics { contentDescription = semanticsLabel },
    )
}

/**
 * The gentle online-only create feedback (ADR-0016): the centred "reconnect to save" on
 * [NewStatus.Offline] (nothing was enqueued) and the server's gentle message on [NewStatus.Failed].
 * Nothing rendered while editing or submitting (the submit button carries the "Saving…" feedback).
 */
@Composable
fun NewStatusMessage(status: NewStatus, modifier: Modifier = Modifier) {
    when (status) {
        NewStatus.Offline -> Text(
            text = "You're offline — reconnect to save. Nothing was queued.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.defernoColors.inkMuted,
            textAlign = TextAlign.Center,
            modifier = modifier.fillMaxWidth().semantics { contentDescription = "Reconnect to save" },
        )
        is NewStatus.Failed -> Text(
            text = status.message,
            color = MaterialTheme.colorScheme.error,
            modifier = modifier,
        )
        NewStatus.Editing, NewStatus.Submitting -> Unit
    }
}

/**
 * The Create button: gated by the shared [NewState.canSubmit] (non-blank title; an Event also needs
 * a start or a pre-dated date) and showing "Saving…" while [NewStatus.Submitting].
 */
@Composable
fun NewSubmitButton(
    status: NewStatus,
    canSubmit: Boolean,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onSubmit,
        enabled = canSubmit,
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(if (status == NewStatus.Submitting) "Saving…" else "Create")
    }
}

/** The picker label for an [ItemKind] — a View concern, kept out of the shared model. */
private val ItemKind.pickerLabel: String
    get() = when (this) {
        ItemKind.Task -> "Task"
        ItemKind.Habit -> "Habit"
        ItemKind.Chore -> "Chore"
        ItemKind.Event -> "Event"
    }
