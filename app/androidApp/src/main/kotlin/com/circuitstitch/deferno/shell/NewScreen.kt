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
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.shell.ui.NewDateField
import com.circuitstitch.deferno.shell.ui.NewDeadlineTimeField
import com.circuitstitch.deferno.shell.ui.NewDictationMessage
import com.circuitstitch.deferno.shell.ui.NewEventEndField
import com.circuitstitch.deferno.shell.ui.NewEventStartField
import com.circuitstitch.deferno.shell.ui.NewKindPicker
import com.circuitstitch.deferno.shell.ui.NewNotesField
import com.circuitstitch.deferno.shell.ui.NewStatusMessage
import com.circuitstitch.deferno.shell.ui.NewSubmitButton
import com.circuitstitch.deferno.shell.ui.NewTitleField

/**
 * The **New** create surface View (#71, ADR-0015/0016): an **explicit** Task/Habit/Chore/Event kind
 * picker (a segmented row — *not* field-inference, design-principle #5) above a per-kind form. The
 * form adapts to the chosen kind; Create routes through the online-only create seam and, when
 * offline, the View shows a gentle "reconnect to save" rather than queuing (ADR-0016).
 *
 * Each text field carries a **[[Dictation]]** mic affordance (#92, ADR-0018) when on-device speech is
 * available: the first tap prompts for `RECORD_AUDIO`; with permission, spoken English streams as
 * partial [[Transcript]] text into that field and settles to a final result; a denial shows a gentle
 * "needs microphone access" (with an OS-settings deep-link when permanently denied) — never a silent
 * failure. Dictation only fills text; the kind is still chosen explicitly, and create still gates on
 * connectivity.
 *
 * Pure **chrome** since #175: the form rows themselves (kind picker, dictating Title/Notes, date and
 * Event start/end, the status + Dictation feedback, submit) are the shared stateless atoms in
 * `:app:shell:ui` — one form binding for Android and desktop. This View keeps only the phone overlay
 * layout and the Android affordances: the `RECORD_AUDIO` permission round-trip and the app-settings
 * deep-link intent.
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

            NewKindPicker(selectedKind = state.selectedKind, onSelectKind = component::selectKind)

            Spacer(Modifier.padding(top = 16.dp))

            NewTitleField(state = state, onTitleChange = component::setTitle, onMic = ::onMic)

            Spacer(Modifier.padding(top = 8.dp))

            NewNotesField(state = state, onNotesChange = component::setNotes, onMic = ::onMic)

            // The gentle Dictation feedback (#92): permission states + recognition errors, never silent.
            // The Android voice of the shared atom, deep-linking to this app's OS settings page.
            NewDictationMessage(
                status = state.dictation,
                deniedNote = "Dictation needs microphone access. Tap the mic to allow it.",
                permanentlyDeniedNote = "Dictation needs microphone access, which is turned off for this app.",
                openSettingsLabel = "Open settings",
                onOpenSettings = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
                modifier = Modifier.padding(top = 8.dp),
            )

            // A Date the item anchors to (#74) — the Calendar FAB pre-dates this to the selected day, and
            // it maps to `complete_by`. Shown for the non-Event kinds (an Event uses its fixed start
            // below instead).
            if (state.selectedKind != ItemKind.Event) {
                Spacer(Modifier.padding(top = 8.dp))
                NewDateField(value = state.date, onValueChange = component::setDate)
                Spacer(Modifier.padding(top = 8.dp))
                NewDeadlineTimeField(value = state.deadlineTime, onValueChange = component::setDeadlineTime)
            }

            // An Event has a fixed start/end window (CONTEXT.md → Event; AC #2): a required start +
            // optional end so a real Event create succeeds.
            if (state.selectedKind == ItemKind.Event) {
                Spacer(Modifier.padding(top = 8.dp))
                NewEventStartField(value = state.start, onValueChange = component::setStart)
                Spacer(Modifier.padding(top = 8.dp))
                NewEventEndField(value = state.end, onValueChange = component::setEnd)
            }

            // The gentle online-only feedback (ADR-0016).
            NewStatusMessage(status = state.status, modifier = Modifier.padding(top = 16.dp))

            Spacer(Modifier.padding(top = 24.dp))

            NewSubmitButton(status = state.status, canSubmit = state.canSubmit, onSubmit = component::submit)
        }
    }
}
