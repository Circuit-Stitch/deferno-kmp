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
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.circuitstitch.deferno.core.designsystem.theme.defernoColors
import com.circuitstitch.deferno.core.model.ItemKind

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
