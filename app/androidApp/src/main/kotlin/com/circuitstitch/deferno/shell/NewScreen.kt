package com.circuitstitch.deferno.shell

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.circuitstitch.deferno.R
import com.circuitstitch.deferno.core.designsystem.theme.defernoColors
import com.circuitstitch.deferno.core.model.ItemKind
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

/**
 * The **New** create surface View (#71, ADR-0015/0016): an **explicit** Task/Habit/Chore/Event kind
 * picker (a segmented row of [FilterChip]s — *not* field-inference, design-principle #5) above a
 * per-kind form. The form adapts to the chosen kind; Create routes through the online-only create seam
 * and, when offline, the View shows a gentle "reconnect to save" rather than queuing (ADR-0016).
 *
 * Each text field carries a **[[Dictation]]** mic affordance (#92, ADR-0018) when on-device speech is
 * available: the first tap prompts for `RECORD_AUDIO`; with permission, spoken English streams as
 * partial [[Transcript]] text into that field and settles to a final result; a denial shows a gentle
 * "needs microphone access" (with an OS-settings deep-link when permanently denied) — never a silent
 * failure. Dictation only fills text; the kind is still chosen explicitly, and create still gates on
 * connectivity.
 */
@Composable
fun NewScreen(component: NewComponent, modifier: Modifier = Modifier) {
    val state by component.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? Activity

    // The field whose mic triggered the OS permission prompt, so the grant callback dictates into it.
    var pendingDictationField by remember { mutableStateOf<DictationField?>(null) }
    val requestMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val field = pendingDictationField
        pendingDictationField = null
        when {
            granted && field != null -> component.startDictation(field)
            !granted -> {
                // No rationale offered after a denial ⇒ "don't ask again" / permanently denied: the View
                // then deep-links to OS settings. Otherwise it's a soft denial the user can retry.
                val permanent = activity == null ||
                    !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
                component.dictationPermissionDenied(permanent)
            }
        }
    }

    fun onMic(field: DictationField) {
        // Tapping the active field's mic again stops dictation (toggle).
        if (state.dictation == DictationStatus.Listening && state.dictationField == field) {
            component.stopDictation()
            return
        }
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            component.startDictation(field)
        } else {
            pendingDictationField = field
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

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

            Spacer(Modifier.padding(top = 8.dp))

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

            // The gentle Dictation feedback (#92): permission states + recognition errors, never silent.
            DictationMessage(status = state.dictation, onOpenSettings = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            })

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
 * The per-field [[Dictation]] mic (#92). Tinted primary while [listening] (and labelled "Stop dictation"
 * so a tap toggles it off), muted otherwise. Shown only when the engine is available (the caller gates
 * on `dictationAvailable`).
 */
@Composable
private fun MicButton(
    field: DictationField,
    listening: Boolean,
    onClick: (DictationField) -> Unit,
) {
    IconButton(onClick = { onClick(field) }) {
        Icon(
            painter = painterResource(R.drawable.ic_mic),
            contentDescription = if (listening) "Stop dictation" else "Dictate",
            tint = if (listening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The gentle Dictation status line (#92, ADR-0018): a "needs microphone access" message on denial — with
 * an **Open settings** deep-link when permanently denied — or a soft recognition-error note. Silent
 * (nothing rendered) while idle or listening; the streaming text itself is the listening feedback.
 */
@Composable
private fun DictationMessage(
    status: DictationStatus,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (status) {
        DictationStatus.PermissionDenied -> DictationNote(
            "Dictation needs microphone access. Tap the mic to allow it.",
            modifier,
        )
        DictationStatus.PermissionPermanentlyDenied -> Column(modifier.fillMaxWidth()) {
            DictationNote("Dictation needs microphone access, which is turned off for this app.")
            TextButton(
                onClick = onOpenSettings,
                modifier = Modifier.semantics { contentDescription = "Open settings" },
            ) { Text("Open settings") }
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
        modifier = modifier.fillMaxWidth().padding(top = 8.dp).semantics { contentDescription = "Dictation status" },
    )
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
