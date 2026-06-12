package com.circuitstitch.deferno.shell.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.circuitstitch.deferno.core.designsystem.theme.defernoColors
import com.circuitstitch.deferno.shell.FeedbackCategory
import com.circuitstitch.deferno.shell.FeedbackComponent
import com.circuitstitch.deferno.shell.FeedbackStatus

/**
 * The shared, stateless **Feedback** form (#375): the platform-neutral render of
 * [com.circuitstitch.deferno.shell.FeedbackComponent]'s state — the category picker, subject + body,
 * the attachment list, the online-only status note, and Send — so the binding exists ONCE for Android
 * and desktop (the ADR-0004 #27 `:ui` pattern, mirroring the New form atoms). The platform chrome
 * around it supplies only [onAttach]: the file-picker trigger (Android SAF, desktop file dialog) that
 * reads bytes and calls [FeedbackComponent.addAttachments]. A `null` [onAttach] hides the attach
 * affordance (a host without a picker yet renders a text-only form).
 */
@Composable
fun FeedbackForm(
    component: FeedbackComponent,
    onAttach: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val state by component.state.collectAsStateWithLifecycle()

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = "Send feedback",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
                TextButton(onClick = component::dismiss) { Text("Cancel") }
            }

            Spacer(Modifier.padding(top = 16.dp))

            Row(
                Modifier.fillMaxWidth().semantics { contentDescription = "Category" },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FeedbackCategory.entries.forEach { category ->
                    FilterChip(
                        selected = state.category == category,
                        onClick = { component.setCategory(category) },
                        label = { Text(category.label) },
                    )
                }
            }

            Spacer(Modifier.padding(top = 16.dp))

            OutlinedTextField(
                value = state.subject,
                onValueChange = component::setSubject,
                label = { Text("Subject") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Subject" },
            )

            Spacer(Modifier.padding(top = 8.dp))

            OutlinedTextField(
                value = state.body,
                onValueChange = component::setBody,
                label = { Text("What's going on?") },
                minLines = 4,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Feedback body" },
            )

            if (onAttach != null) {
                Spacer(Modifier.padding(top = 12.dp))
                OutlinedButton(
                    onClick = onAttach,
                    modifier = Modifier.semantics { contentDescription = "Attach files" },
                ) { Text("Attach files") }
            }

            state.attachments.forEachIndexed { index, file ->
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = file.filename,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { contentDescription = "Attachment ${file.filename}" },
                    )
                    TextButton(
                        onClick = { component.removeAttachment(index) },
                        modifier = Modifier.semantics { contentDescription = "Remove ${file.filename}" },
                    ) { Text("Remove") }
                }
            }

            when (val status = state.status) {
                FeedbackStatus.Offline -> FeedbackNote("You're offline — reconnect to send. Nothing was queued.")
                is FeedbackStatus.Failed -> FeedbackNote(status.message, error = true)
                else -> Unit
            }

            Spacer(Modifier.padding(top = 24.dp))

            Button(
                onClick = component::submit,
                enabled = state.canSubmit,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.status == FeedbackStatus.Submitting) "Sending…" else "Send")
            }
        }
    }
}

@Composable
private fun FeedbackNote(text: String, error: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.defernoColors.inkMuted,
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp).semantics { contentDescription = "Feedback status" },
    )
}
