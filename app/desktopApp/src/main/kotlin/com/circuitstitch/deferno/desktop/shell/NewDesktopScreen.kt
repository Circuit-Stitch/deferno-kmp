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
import com.circuitstitch.deferno.core.designsystem.theme.defernoColors
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.shell.NewComponent
import com.circuitstitch.deferno.shell.NewStatus
import kotlin.time.Instant

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
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Title" },
                )

                OutlinedTextField(
                    value = state.notes,
                    onValueChange = component::setNotes,
                    label = { Text("Notes") },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Notes" },
                )

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

/** The picker label for an [ItemKind] — a View concern, kept out of the shared model. */
private val ItemKind.pickerLabel: String
    get() = when (this) {
        ItemKind.Task -> "Task"
        ItemKind.Habit -> "Habit"
        ItemKind.Chore -> "Chore"
        ItemKind.Event -> "Event"
    }
