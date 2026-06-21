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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.circuitstitch.deferno.R
import com.circuitstitch.deferno.core.designsystem.component.Eyebrow
import com.circuitstitch.deferno.core.designsystem.component.MonoMeta
import com.circuitstitch.deferno.core.designsystem.component.PrimaryActionButton
import com.circuitstitch.deferno.core.designsystem.component.SectionLabel
import com.circuitstitch.deferno.core.designsystem.component.SegmentedFilter
import com.circuitstitch.deferno.core.designsystem.theme.defernoColors
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.shell.ui.NewDateField
import com.circuitstitch.deferno.shell.ui.NewDeadlineTimeField
import com.circuitstitch.deferno.shell.ui.NewDictationMessage
import com.circuitstitch.deferno.shell.ui.NewEventEndField
import com.circuitstitch.deferno.shell.ui.NewEventStartField
import com.circuitstitch.deferno.shell.ui.NewNotesField
import com.circuitstitch.deferno.shell.ui.NewStatusMessage
import com.circuitstitch.deferno.shell.ui.NewTitleField

/**
 * The **New task** create surface View (#71, ADR-0015/0016), restyled to the "See the trees" direction:
 * a deliberate, low-overwhelm form where the person **chooses the kind explicitly** (Task/Habit/Chore/
 * Event — *never* field-inference, design-principle #5: "Deferno never guesses on New") above a per-kind
 * form. Create routes through the online-only create seam and, when offline, the View shows a gentle
 * "reconnect to save" rather than queuing (ADR-0016).
 *
 * Each text field carries a **[[Dictation]]** mic affordance (#92, ADR-0018) when on-device speech is
 * available: the first tap prompts for `RECORD_AUDIO`; with permission, spoken English streams as
 * partial [[Transcript]] text into that field and settles to a final result; a denial shows a gentle
 * "needs microphone access" (with an OS-settings deep-link when permanently denied) — never a silent
 * failure. Dictation only fills text; the kind is still chosen explicitly, and create still gates on
 * connectivity.
 *
 * Visual-only restyle (#175 split preserved): the form rows themselves (the dictating Title/Notes,
 * date and Event start/end, the status + Dictation feedback) are the shared stateless atoms in
 * `:app:shell:ui`. The kind picker is rendered here with the shared
 * [com.circuitstitch.deferno.core.designsystem.component.SegmentedFilter] (the "See the trees" segmented
 * control) bound to the same [NewComponent.selectKind] seam the old picker used — no behavior change.
 * This View keeps only the phone overlay layout + the Android affordances: the `RECORD_AUDIO`
 * permission round-trip and the app-settings deep-link intent.
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
        // Edge-to-edge (ADR-0035): this overlay sits above the whole chrome, so it owns its system-bar
        // insets — title clears the status bar, Create clears the nav bar (mirrors SearchScreen).
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "New task",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
                TextButton(onClick = component::dismiss) { Text("Cancel") }
            }

            Spacer(Modifier.height(20.dp))

            // The explicit kind choice — the "See the trees" segmented control. Bound to the same
            // selectKind seam the old FilterChip picker used (ItemKind.entries is Task/Habit/Chore/Event).
            SectionLabel("WHAT KIND OF THING?")
            Spacer(Modifier.height(8.dp))
            SegmentedFilter(
                options = kindOptions,
                selectedIndex = ItemKind.entries.indexOf(state.selectedKind),
                onSelect = { component.selectKind(ItemKind.entries[it]) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "You choose the kind here — Deferno never guesses it on New.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.defernoColors.inkMuted,
            )

            Spacer(Modifier.height(20.dp))

            // The dictation mic glyph: a native res/drawable on Android (the app packages no
            // dependency-module composeResources, so the shared atom takes an injected painter — the
            // same approach as the shell-chrome icons).
            val micIcon = painterResource(R.drawable.ic_mic)
            SectionLabel("TITLE")
            Spacer(Modifier.height(8.dp))
            NewTitleField(state = state, onTitleChange = component::setTitle, onMic = ::onMic, micIcon = micIcon)

            Spacer(Modifier.height(16.dp))

            SectionLabel("NOTES · OPTIONAL")
            Spacer(Modifier.height(8.dp))
            NewNotesField(state = state, onNotesChange = component::setNotes, onMic = ::onMic, micIcon = micIcon)

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

            Spacer(Modifier.height(16.dp))

            // The "When / details" settings card: the date + deadline-time rows (the non-Event kinds) or
            // the Event's fixed start/end window — re-skinned into one calm card, the same atoms + seams.
            SectionLabel("WHEN")
            Spacer(Modifier.height(8.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    // A Date the item anchors to (#74) — the Calendar FAB pre-dates this; maps to
                    // `complete_by`. Shown for the non-Event kinds (an Event uses its fixed start instead).
                    if (state.selectedKind != ItemKind.Event) {
                        NewDateField(value = state.date, onValueChange = component::setDate)
                        Spacer(Modifier.height(12.dp))
                        NewDeadlineTimeField(value = state.deadlineTime, onValueChange = component::setDeadlineTime)
                    } else {
                        // An Event has a fixed start/end window (CONTEXT.md → Event; AC #2): a required
                        // start + optional end so a real Event create succeeds.
                        NewEventStartField(value = state.start, onValueChange = component::setStart)
                        Spacer(Modifier.height(12.dp))
                        NewEventEndField(value = state.end, onValueChange = component::setEnd)
                    }
                }
            }

            // The gentle online-only feedback (ADR-0016).
            NewStatusMessage(status = state.status, modifier = Modifier.padding(top = 16.dp))

            Spacer(Modifier.height(20.dp))

            Eyebrow("SAVES ONLINE · YOU CAN CHANGE ANYTHING LATER")
            Spacer(Modifier.height(10.dp))

            // Create — the big, thumb-reachable primary action (the "See the trees" verb), gated by the
            // same canSubmit rule and surfacing the submitting state ("Saving…"), wired to the existing
            // create seam.
            PrimaryActionButton(
                text = if (state.status == NewStatus.Submitting) "Saving…" else "Create task",
                onClick = component::submit,
                icon = null,
                enabled = state.canSubmit,
            )
        }
    }
}

/** The kind-picker labels — the explicit Task/Habit/Chore/Event choice, in [ItemKind.entries] order. */
private val kindOptions = listOf("Task", "Habit", "Chore", "Event")
