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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.circuitstitch.deferno.core.designsystem.theme.defernoColors

/**
 * The **Brain dump** surface View (ADR-0027, #150; Stage 4 async rework, #212 follow-on): a deliberately
 * simple voice **recorder**. The person taps record, speaks, and stops; the take is handed to the
 * background **Brain dump worker** and the proposed drafts surface in the **Inbox** Destination for
 * triage — review no longer happens here (Stage 3, ADR-0015 amendment).
 *
 * Like [NewScreen], this View keeps only the Android affordances: the `RECORD_AUDIO` permission
 * round-trip and the app-settings deep-link. The rendering is the stateless [BrainDumpContent], driven
 * entirely by [BrainDumpState], so the visual states are testable without the permission plumbing.
 */
@Composable
fun BrainDumpScreen(component: BrainDumpComponent, modifier: Modifier = Modifier) {
    val state by component.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? Activity

    val requestMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            component.startRecording()
        } else {
            // No rationale after a denial ⇒ "don't ask again" / permanently denied — the View then
            // deep-links to OS settings. Otherwise it's a soft denial the person can retry.
            val permanent = activity == null ||
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
            component.dictationPermissionDenied(permanent)
        }
    }

    // The single mic action: stop while recording (no permission needed); otherwise start, prompting for
    // RECORD_AUDIO first if it isn't already granted.
    fun onMic() {
        if (state.phase == Phase.Recording) {
            component.stopRecording()
            return
        }
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            component.startRecording()
        } else {
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    BrainDumpContent(
        state = state,
        onMic = ::onMic,
        onClose = component::dismiss,
        onOpenSettings = {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        },
        modifier = modifier,
    )
}

/** The stateless Brain dump body — every visual state driven by [state], no platform affordances. */
@Composable
internal fun BrainDumpContent(
    state: BrainDumpState,
    onMic: () -> Unit,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        // Edge-to-edge (ADR-0035): this overlay sits above the whole chrome, so it owns its system-bar
        // insets — title clears the status bar, controls clear the nav bar (mirrors SearchScreen).
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).padding(24.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = "Brain dump",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
                TextButton(onClick = onClose) { Text("Close") }
            }

            Spacer(Modifier.padding(top = 8.dp))
            Text(
                text = "Speak freely — Deferno turns it into draft tasks and adds them to your Inbox to review.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.defernoColors.inkMuted,
            )

            Spacer(Modifier.padding(top = 16.dp))

            when (state.phase) {
                Phase.Idle -> Button(onClick = onMic) { Text("Start recording") }
                Phase.Recording -> {
                    Text(
                        text = "Recording…",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    Spacer(Modifier.padding(top = 16.dp))
                    Button(onClick = onMic) { Text("Stop") }
                }
                Phase.Enqueued -> {
                    StatusNote("Transcribing in the background — we'll let you know when your drafts are ready in the Inbox.")
                    Spacer(Modifier.padding(top = 16.dp))
                    Button(onClick = onClose) { Text("Done") }
                }
                Phase.Failed -> {
                    StatusNote("Couldn't record that. Try again.")
                    Spacer(Modifier.padding(top = 16.dp))
                    Button(onClick = onMic) { Text("Try again") }
                }
                Phase.PermissionDenied -> PermissionBody(permanent = false, onMic = onMic, onOpenSettings = onOpenSettings)
                Phase.PermissionPermanentlyDenied ->
                    PermissionBody(permanent = true, onMic = onMic, onOpenSettings = onOpenSettings)
            }
        }
    }
}

@Composable
private fun PermissionBody(permanent: Boolean, onMic: () -> Unit, onOpenSettings: () -> Unit) {
    if (permanent) {
        StatusNote("Brain dump needs microphone access, which is turned off for this app.")
        Spacer(Modifier.padding(top = 16.dp))
        Button(onClick = onOpenSettings) { Text("Open settings") }
    } else {
        StatusNote("Brain dump needs microphone access.")
        Spacer(Modifier.padding(top = 16.dp))
        Button(onClick = onMic) { Text("Allow microphone") }
    }
}

/** A gentle muted note — the calm, never-judgmental copy the surface speaks in. */
@Composable
private fun StatusNote(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.defernoColors.inkMuted)
}
