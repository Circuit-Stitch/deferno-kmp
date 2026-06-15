package com.circuitstitch.deferno.desktop.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.circuitstitch.deferno.core.designsystem.resources.Res
import com.circuitstitch.deferno.core.designsystem.resources.ic_mic
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.shell.DictationField
import com.circuitstitch.deferno.shell.DictationStatus
import com.circuitstitch.deferno.shell.NewComponent
import com.circuitstitch.deferno.shell.ui.NewDictationMessage
import com.circuitstitch.deferno.shell.ui.NewEventEndField
import com.circuitstitch.deferno.shell.ui.NewEventStartField
import com.circuitstitch.deferno.shell.ui.NewKindPicker
import com.circuitstitch.deferno.shell.ui.NewNotesField
import com.circuitstitch.deferno.shell.ui.NewStatusMessage
import com.circuitstitch.deferno.shell.ui.NewSubmitButton
import com.circuitstitch.deferno.shell.ui.NewTitleField
import org.jetbrains.compose.resources.painterResource

/**
 * The **New** create surface, desktop edition (#87, ADR-0015/0016/0017) — the desktop counterpart of
 * the Android `NewScreen`. New spans every Item kind, so it is a **Shell View** in `app/desktopApp`
 * (not a `feature/<slice>/ui` module) that renders the shared, Compose-free [NewComponent] from `app/shell`
 * — the same component the Android `NewScreen` renders. It holds no create logic of its own (ADR-0007):
 * it observes the component's [com.circuitstitch.deferno.shell.NewState] and forwards every intent.
 *
 * Pure **chrome** since #175: the form rows themselves (the explicit kind picker — never
 * field-inference, ADR-0015 — the dictating Title/Notes, the Event start/end, the online-only
 * status feedback (ADR-0016), submit) are the shared stateless atoms in `:app:shell:ui` — one form
 * binding for Android and desktop. This View keeps only the desktop layout and affordances.
 *
 * Desktop divergence (ADR-0007: not the phone form stretched): the form is held to a comfortable
 * reading width and centred rather than spanning a wide window edge-to-edge. Dictation needs no
 * in-app permission prompt — the sidecar engine resolves macOS TCC itself (the #120 preflight: the
 * OS prompt fires on first use; a settled denial lands as PermissionPermanentlyDenied, with the
 * host-routed **Open System Settings** deep-link).
 */
@Composable
fun NewDesktopScreen(component: NewComponent, modifier: Modifier = Modifier) {
    val state by component.state.collectAsState()

    // Toggle [[Dictation]] into [field] (#94): tapping the active field's mic again stops it.
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

                NewKindPicker(selectedKind = state.selectedKind, onSelectKind = component::selectKind)

                // Desktop packages composeResources, so the design-system glyph loads directly here.
                val micIcon = painterResource(Res.drawable.ic_mic)
                NewTitleField(state = state, onTitleChange = component::setTitle, onMic = ::onMic, micIcon = micIcon)

                NewNotesField(state = state, onNotesChange = component::setNotes, onMic = ::onMic, micIcon = micIcon)

                // The gentle Dictation feedback (#94): a recognition/capture error, never a silent
                // failure — the desktop voice of the shared atom, with the System Settings deep-link
                // on a foreclosed permission (#120, host-routed to the blocked capability's pane).
                NewDictationMessage(
                    status = state.dictation,
                    deniedNote = "Dictation needs microphone access.",
                    permanentlyDeniedNote = "Dictation needs microphone access, which is turned off for Deferno.",
                    openSettingsLabel = "Open System Settings",
                    onOpenSettings = component::openDictationPermissionSettings,
                )

                // An Event has a fixed start/end window (CONTEXT.md → Event; AC #2): a required start +
                // optional end so a real Event create succeeds.
                if (state.selectedKind == ItemKind.Event) {
                    NewEventStartField(value = state.start, onValueChange = component::setStart)
                    NewEventEndField(value = state.end, onValueChange = component::setEnd)
                }

                // The gentle online-only feedback (ADR-0016).
                NewStatusMessage(status = state.status)

                NewSubmitButton(
                    status = state.status,
                    canSubmit = state.canSubmit,
                    onSubmit = component::submit,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/** Comfortable form column width for the New surface on a wide desktop window. */
private val NewFormWidth = 560.dp
