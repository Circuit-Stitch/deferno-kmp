package com.circuitstitch.deferno.desktop.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.circuitstitch.deferno.core.designsystem.resources.Res
import com.circuitstitch.deferno.core.designsystem.resources.ic_mic
import com.circuitstitch.deferno.core.designsystem.theme.defernoColors
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.shell.DictationField
import com.circuitstitch.deferno.shell.DictationStatus
import com.circuitstitch.deferno.shell.NewComponent
import com.circuitstitch.deferno.shell.NewStatus
import kotlin.time.Instant
import org.jetbrains.compose.resources.painterResource

/**
 * The **New** create surface, desktop edition (#87, ADR-0015/0016/0017) — the desktop counterpart of
 * the Android `NewScreen`. New spans every Item kind, so it is a **Shell View** in `app/desktopApp`
 * (not a `feature/<slice>/ui` module) that renders the shared, Compose-free [NewComponent] from `app/shell`
 * — the same component the Android `NewScreen` renders. It holds no create logic of its own (ADR-0007):
 * it observes the component's [com.circuitstitch.deferno.shell.NewState] and forwards every intent.
 *
 * It presents an **explicit** Task/Habit/Chore/Event kind picker (a segmented row of [FilterChip]s,
 * defaulting to Task — never field-inference, ADR-0015) above a per-kind form: title + notes for every
 * kind, plus a required **start** and optional **end** when Event is selected. Create is **online-only**
 * (ADR-0016): submitting routes through the shell's real JVM create seam, and the surface shows the
 * shared [NewStatus] feedback — Submitting ("Saving…"), a gentle "reconnect to save" on Offline (nothing
 * enqueued), and a gentle error on Failed. On Accepted the host clears the overlay and the new row
 * arrives through the repository `Flow` (no separate insert here). Submit honors the shared `canSubmit`.
 *
 * Desktop divergence (ADR-0007: not the phone form stretched): the form is held to a comfortable
 * reading width and centred rather than spanning a wide window edge-to-edge.
 */
@Composable
fun NewDesktopScreen(component: NewComponent, modifier: Modifier = Modifier) {
    val state by component.state.collectAsState()

    // Toggle [[Dictation]] into [field] (#94): tapping the active field's mic again stops it. Desktop has
    // no in-app permission prompt — the OS gates mic access; a capture failure surfaces as a gentle error.
    fun onMic(field: DictationField) {
        if (state.dictation == DictationStatus.Listening && state.dictationField == field) {
            component.stopDictation()
        } else {
            component.startDictation(field)
        }
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier.widthIn(max = NewFormWidth).fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "New",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() },
                    )
                    TextButton(onClick = component::dismiss) { Text("Cancel") }
                }

                // The explicit kind picker (ADR-0015): a segmented control, defaulting to Task.
                Row(
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Kind picker" },
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ItemKind.entries.forEach { kind ->
                        FilterChip(
                            selected = state.selectedKind == kind,
                            onClick = { component.selectKind(kind) },
                            label = { Text(kind.pickerLabel) },
                        )
                    }
                }

                OutlinedTextField(
                    value = state.title,
                    onValueChange = component::setTitle,
                    label = { Text("Title") },
                    singleLine = true,
                    trailingIcon = {
                        if (state.dictationAvailable) {
                            MicButton(
                                field = DictationField.Title,
                                listening = state.dictation == DictationStatus.Listening &&
                                    state.dictationField == DictationField.Title,
                                onClick = ::onMic,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Title" },
                )

                OutlinedTextField(
                    value = state.notes,
                    onValueChange = component::setNotes,
                    label = { Text("Notes") },
                    trailingIcon = {
                        if (state.dictationAvailable) {
                            MicButton(
                                field = DictationField.Notes,
                                listening = state.dictation == DictationStatus.Listening &&
                                    state.dictationField == DictationField.Notes,
                                onClick = ::onMic,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Notes" },
                )

                // The gentle Dictation feedback (#94): a recognition/capture error, never a silent failure.
                DictationMessage(status = state.dictation)

                // An Event has a fixed start/end window (CONTEXT.md → Event; AC #2). The form surfaces a
                // required start + optional end so a real Event create succeeds. Inputs take an ISO-8601
                // instant (e.g. 2026-06-08T09:00:00Z) — a pragmatic v1 entry; a native date-time picker is
                // a follow-up. (Location is absent from the v0.1 backend contract, so it is not collected.)
                if (state.selectedKind == ItemKind.Event) {
                    InstantField(
                        value = state.start,
                        onValueChange = component::setStart,
                        label = "Starts (e.g. 2026-06-08T09:00:00Z)",
                        semanticsLabel = "Event start",
                    )
                    InstantField(
                        value = state.end,
                        onValueChange = component::setEnd,
                        label = "Ends (optional)",
                        semanticsLabel = "Event end",
                    )
                }

                // The gentle online-only feedback (ADR-0016).
                when (val status = state.status) {
                    NewStatus.Offline -> ReconnectMessage()
                    is NewStatus.Failed -> Text(
                        text = status.message,
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> Unit
                }

                Button(
                    onClick = component::submit,
                    enabled = state.canSubmit,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Text(if (state.status == NewStatus.Submitting) "Saving…" else "Create")
                }
            }
        }
    }
}

/** Comfortable form column width for the New surface on a wide desktop window. */
private val NewFormWidth = 560.dp

/**
 * An ISO-8601 instant text input (the Event start/end, AC #2). The user types an RFC3339 instant; a
 * parseable value is pushed up via [onValueChange] as a real [Instant], an unparseable one clears it
 * (so a half-typed value never POSTs an invalid `complete_by`). v1 entry shape — a native date-time
 * picker is a follow-up; the component stays Compose-free and unit-tested on [Instant]s directly.
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

@Composable
private fun ReconnectMessage(modifier: Modifier = Modifier) {
    Text(
        text = "You're offline — reconnect to save. Nothing was queued.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.defernoColors.inkMuted,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth().semantics { contentDescription = "Reconnect to save" },
    )
}

/**
 * The per-field [[Dictation]] mic (#94). Tinted primary while [listening] (and labelled "Stop dictation"
 * so a tap toggles it off), muted otherwise. Shown only when the engine is available (the caller gates on
 * `dictationAvailable`) — the desktop counterpart of the Android `MicButton`. The mic glyph is the shared
 * design-system [Res.drawable.ic_mic] (material-icons-core has no Mic), recoloured by [Icon]'s tint.
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
 * The gentle Dictation status line (#94, ADR-0018): a soft recognition/capture-error note, never a silent
 * failure. Silent while idle or listening (the streaming text itself is the listening feedback). The
 * permission states never arise on desktop (the OS gates the mic, not an in-app prompt) but are handled
 * totally over the sealed [DictationStatus].
 */
@Composable
private fun DictationMessage(status: DictationStatus, modifier: Modifier = Modifier) {
    val message = when (status) {
        is DictationStatus.Error -> "Couldn't hear that — try the mic again."
        DictationStatus.PermissionDenied,
        DictationStatus.PermissionPermanentlyDenied,
        -> "Dictation needs microphone access."
        DictationStatus.Idle, DictationStatus.Listening -> return
    }
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.defernoColors.inkMuted,
        modifier = modifier.fillMaxWidth().semantics { contentDescription = "Dictation status" },
    )
}

/** The picker label for an [ItemKind] — a View concern, kept out of the shared model. */
private val ItemKind.pickerLabel: String
    get() = when (this) {
        ItemKind.Task -> "Task"
        ItemKind.Habit -> "Habit"
        ItemKind.Chore -> "Chore"
        ItemKind.Event -> "Event"
    }
