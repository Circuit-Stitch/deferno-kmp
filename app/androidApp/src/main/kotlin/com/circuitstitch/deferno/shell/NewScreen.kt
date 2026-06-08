package com.circuitstitch.deferno.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.circuitstitch.deferno.core.designsystem.theme.defernoColors
import com.circuitstitch.deferno.core.model.ItemKind
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

/**
 * The **New** create surface View (#71, ADR-0015/0016): an **explicit** Task/Habit/Chore/Event kind
 * picker (a segmented row of [FilterChip]s — *not* field-inference, design-principle #5) above a
 * per-kind form. The form adapts to the chosen kind; Create routes through the online-only create seam
 * and, when offline, the View shows a gentle "reconnect to save" rather than queuing (ADR-0016 — the
 * surface enqueues nothing). The created item then joins the normal offline-first observe/edit flow.
 */
@Composable
fun NewScreen(component: NewComponent, modifier: Modifier = Modifier) {
    val state by component.state.collectAsStateWithLifecycle()

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = "New",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
                TextButton(onClick = component::dismiss) { Text("Cancel") }
            }

            Spacer(Modifier.padding(top = 16.dp))

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

            Spacer(Modifier.padding(top = 16.dp))

            OutlinedTextField(
                value = state.title,
                onValueChange = component::setTitle,
                label = { Text("Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Title" },
            )

            Spacer(Modifier.padding(top = 8.dp))

            OutlinedTextField(
                value = state.notes,
                onValueChange = component::setNotes,
                label = { Text("Notes") },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Notes" },
            )

            // A Date the item anchors to (#74) — the Calendar FAB pre-dates this to the selected day, and
            // it maps to `complete_by`. Shown for the non-Event kinds (an Event uses its fixed start
            // below instead). ISO `yyyy-mm-dd` entry — a native date picker is a follow-up.
            if (state.selectedKind != ItemKind.Event) {
                Spacer(Modifier.padding(top = 8.dp))
                DateField(
                    value = state.date,
                    onValueChange = component::setDate,
                    label = "Date (optional, e.g. 2026-06-08)",
                    semanticsLabel = "Date",
                )
            }

            // An Event has a fixed start/end window (CONTEXT.md → Event; AC #2). The form surfaces a
            // required start + optional end so a real Event create succeeds. Inputs take an ISO-8601
            // instant (e.g. 2026-06-08T09:00:00Z) — a pragmatic v1 entry; a native date-time picker is a
            // follow-up. (Location is absent from the v0.1 backend contract, so it is not collected here.)
            if (state.selectedKind == ItemKind.Event) {
                Spacer(Modifier.padding(top = 8.dp))
                InstantField(
                    value = state.start,
                    onValueChange = component::setStart,
                    label = "Starts (e.g. 2026-06-08T09:00:00Z)",
                    semanticsLabel = "Event start",
                )
                Spacer(Modifier.padding(top = 8.dp))
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
                    modifier = Modifier.padding(top = 16.dp),
                )
                else -> Unit
            }

            Spacer(Modifier.padding(top = 24.dp))

            Button(
                onClick = component::submit,
                enabled = state.canSubmit,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.status == NewStatus.Submitting) "Saving…" else "Create")
            }
        }
    }
}

/**
 * An ISO-8601 instant text input (the Event start/end, FIX 1). The user types an RFC3339 instant; a
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

/**
 * An ISO `yyyy-mm-dd` date text input (#74) — the Task/Habit/Chore date the Calendar FAB pre-dates. A
 * parseable value pushes a real [LocalDate] up; an unparseable one clears it (so a half-typed value
 * never POSTs an invalid date). Pre-filled from [value]; a native date picker is a follow-up.
 */
@Composable
private fun DateField(
    value: LocalDate?,
    onValueChange: (LocalDate?) -> Unit,
    label: String,
    semanticsLabel: String,
    modifier: Modifier = Modifier,
) {
    var text by remember(value) { mutableStateOf(value?.toString() ?: "") }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onValueChange(runCatching { LocalDate.parse(it.trim()) }.getOrNull())
        },
        label = { Text(label) },
        singleLine = true,
        isError = text.isNotBlank() && runCatching { LocalDate.parse(text.trim()) }.getOrNull() == null,
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
        modifier = modifier.fillMaxWidth().padding(top = 16.dp).semantics { contentDescription = "Reconnect to save" },
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
