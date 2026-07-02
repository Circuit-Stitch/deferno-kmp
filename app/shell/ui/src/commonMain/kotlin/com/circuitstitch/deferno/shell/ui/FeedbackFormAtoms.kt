package com.circuitstitch.deferno.shell.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
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
import com.circuitstitch.deferno.core.data.feedback.FeedbackResult
import com.circuitstitch.deferno.core.designsystem.resources.Res
import com.circuitstitch.deferno.core.designsystem.resources.common_attach_files
import com.circuitstitch.deferno.core.designsystem.resources.common_error_app_out_of_date
import com.circuitstitch.deferno.core.designsystem.resources.common_cancel
import com.circuitstitch.deferno.core.designsystem.resources.common_remove
import com.circuitstitch.deferno.core.designsystem.resources.common_remove_named_cd
import com.circuitstitch.deferno.core.designsystem.resources.common_send
import com.circuitstitch.deferno.core.designsystem.resources.feedback_attachment_cd
import com.circuitstitch.deferno.core.designsystem.resources.feedback_body_cd
import com.circuitstitch.deferno.core.designsystem.resources.feedback_body_label
import com.circuitstitch.deferno.core.designsystem.resources.feedback_category_bug
import com.circuitstitch.deferno.core.designsystem.resources.feedback_category_cd
import com.circuitstitch.deferno.core.designsystem.resources.feedback_category_idea
import com.circuitstitch.deferno.core.designsystem.resources.feedback_category_other
import com.circuitstitch.deferno.core.designsystem.resources.feedback_category_question
import com.circuitstitch.deferno.core.designsystem.resources.feedback_error_presign_failed
import com.circuitstitch.deferno.core.designsystem.resources.feedback_error_send_failed
import com.circuitstitch.deferno.core.designsystem.resources.feedback_error_upload_failed
import com.circuitstitch.deferno.core.designsystem.resources.feedback_offline_note
import com.circuitstitch.deferno.core.designsystem.resources.feedback_status_cd
import com.circuitstitch.deferno.core.designsystem.resources.feedback_subject_label
import com.circuitstitch.deferno.core.designsystem.resources.feedback_submit_sending
import com.circuitstitch.deferno.core.designsystem.resources.feedback_title
import com.circuitstitch.deferno.core.designsystem.theme.defernoColors
import com.circuitstitch.deferno.shell.FeedbackCategory
import com.circuitstitch.deferno.shell.FeedbackComponent
import com.circuitstitch.deferno.shell.FeedbackStatus
import org.jetbrains.compose.resources.stringResource

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
        // Edge-to-edge (ADR-0035): this overlay sits above the whole chrome, so it owns its system-bar
        // insets — title clears the status bar, Send clears the nav bar (mirrors SearchScreen).
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)
                .verticalScroll(rememberScrollState()).padding(24.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = stringResource(Res.string.feedback_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
                TextButton(onClick = component::dismiss) { Text(stringResource(Res.string.common_cancel)) }
            }

            Spacer(Modifier.padding(top = 16.dp))

            val categoryCd = stringResource(Res.string.feedback_category_cd)
            Row(
                Modifier.fillMaxWidth().semantics { contentDescription = categoryCd },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FeedbackCategory.entries.forEach { category ->
                    FilterChip(
                        selected = state.category == category,
                        onClick = { component.setCategory(category) },
                        label = { Text(category.chipLabel) },
                    )
                }
            }

            Spacer(Modifier.padding(top = 16.dp))

            val subjectLabel = stringResource(Res.string.feedback_subject_label)
            OutlinedTextField(
                value = state.subject,
                onValueChange = component::setSubject,
                label = { Text(subjectLabel) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = subjectLabel },
            )

            Spacer(Modifier.padding(top = 8.dp))

            val bodyCd = stringResource(Res.string.feedback_body_cd)
            OutlinedTextField(
                value = state.body,
                onValueChange = component::setBody,
                label = { Text(stringResource(Res.string.feedback_body_label)) },
                minLines = 4,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = bodyCd },
            )

            if (onAttach != null) {
                Spacer(Modifier.padding(top = 12.dp))
                val attachFiles = stringResource(Res.string.common_attach_files)
                OutlinedButton(
                    onClick = onAttach,
                    modifier = Modifier.semantics { contentDescription = attachFiles },
                ) { Text(attachFiles) }
            }

            state.attachments.forEachIndexed { index, file ->
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    val attachmentCd = stringResource(Res.string.feedback_attachment_cd, file.filename)
                    Text(
                        text = file.filename,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { contentDescription = attachmentCd },
                    )
                    val removeCd = stringResource(Res.string.common_remove_named_cd, file.filename)
                    TextButton(
                        onClick = { component.removeAttachment(index) },
                        modifier = Modifier.semantics { contentDescription = removeCd },
                    ) { Text(stringResource(Res.string.common_remove)) }
                }
            }

            when (val status = state.status) {
                FeedbackStatus.Offline -> FeedbackNote(stringResource(Res.string.feedback_offline_note))
                is FeedbackStatus.Failed -> FeedbackNote(status.localizedMessage, error = true)
                else -> Unit
            }

            Spacer(Modifier.padding(top = 24.dp))

            Button(
                onClick = component::submit,
                enabled = state.canSubmit,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (state.status == FeedbackStatus.Submitting) {
                        stringResource(Res.string.feedback_submit_sending)
                    } else {
                        stringResource(Res.string.common_send)
                    },
                )
            }
        }
    }
}

@Composable
private fun FeedbackNote(text: String, error: Boolean = false) {
    val statusCd = stringResource(Res.string.feedback_status_cd)
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.defernoColors.inkMuted,
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp).semantics { contentDescription = statusCd },
    )
}

/** The localized chip label for a [FeedbackCategory] — the enum's [FeedbackCategory.label] stays the
 *  wire/bridge English. */
private val FeedbackCategory.chipLabel: String
    @Composable get() = when (this) {
        FeedbackCategory.Bug -> stringResource(Res.string.feedback_category_bug)
        FeedbackCategory.Idea -> stringResource(Res.string.feedback_category_idea)
        FeedbackCategory.Question -> stringResource(Res.string.feedback_category_question)
        FeedbackCategory.Other -> stringResource(Res.string.feedback_category_other)
    }

/** The localized note for a failed send — server-authored prose renders verbatim. */
private val FeedbackStatus.Failed.localizedMessage: String
    @Composable get() = when (reason) {
        FeedbackResult.Failed.Reason.PrepareAttachments -> stringResource(Res.string.feedback_error_presign_failed)
        FeedbackResult.Failed.Reason.UploadFailed -> stringResource(Res.string.feedback_error_upload_failed, statusCode ?: 0)
        FeedbackResult.Failed.Reason.SendFailed -> stringResource(Res.string.feedback_error_send_failed, statusCode ?: 0)
        FeedbackResult.Failed.Reason.AppOutOfDate -> stringResource(Res.string.common_error_app_out_of_date)
        FeedbackResult.Failed.Reason.ServerMessage -> message
    }
