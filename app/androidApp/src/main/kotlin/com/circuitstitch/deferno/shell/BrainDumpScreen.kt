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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.circuitstitch.deferno.core.designsystem.theme.defernoColors

/**
 * The **Brain dump** surface View (ADR-0027, #150): continuous on-device dictation that the
 * [BrainDumpComponent] turns into reviewable draft Tasks, accepted one-by-one through the ordinary
 * create path (propose-only — nothing is written until the person taps "Add").
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
            component.startDictation()
        } else {
            // No rationale after a denial ⇒ "don't ask again" / permanently denied — the View then
            // deep-links to OS settings. Otherwise it's a soft denial the person can retry.
            val permanent = activity == null ||
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
            component.dictationPermissionDenied(permanent)
        }
    }

    // The single mic action: stop while listening (no permission needed); otherwise start, prompting for
    // RECORD_AUDIO first if it isn't already granted.
    fun onMic() {
        if (state.phase == Phase.Listening) {
            component.stopDictation()
            return
        }
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            component.startDictation()
        } else {
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    BrainDumpContent(
        state = state,
        onMic = ::onMic,
        onAccept = component::acceptDraft,
        onDismissDraft = component::dismissDraft,
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
    onAccept: (String) -> Unit,
    onDismissDraft: (String) -> Unit,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
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
                text = "Speak freely — Deferno turns it into draft tasks you can review before anything is saved.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.defernoColors.inkMuted,
            )

            Spacer(Modifier.padding(top = 16.dp))

            when (val phase = state.phase) {
                Phase.Idle -> IdleBody(state.micAvailable, onMic)
                Phase.Listening -> ListeningBody(state.transcript, onMic)
                Phase.Extracting -> StatusNote("Finding tasks…")
                Phase.Review -> ReviewBody(state, onAccept, onDismissDraft, onMic, Modifier.weight(1f))
                is Phase.Failed -> FailedBody(phase.reason, onMic, onClose)
                Phase.PermissionDenied -> PermissionBody(permanent = false, onMic = onMic, onOpenSettings = onOpenSettings)
                Phase.PermissionPermanentlyDenied ->
                    PermissionBody(permanent = true, onMic = onMic, onOpenSettings = onOpenSettings)
            }
        }
    }
}

@Composable
private fun IdleBody(micAvailable: Boolean, onMic: () -> Unit) {
    if (!micAvailable) {
        StatusNote("Dictation isn't available on this device yet.")
        return
    }
    Button(onClick = onMic) { Text("Start recording") }
}

@Composable
private fun ListeningBody(transcript: String, onMic: () -> Unit) {
    Text(
        text = "Listening…",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
    Spacer(Modifier.padding(top = 8.dp))
    Text(
        text = transcript.ifBlank { "Start speaking…" },
        style = MaterialTheme.typography.bodyLarge,
        color = if (transcript.isBlank()) MaterialTheme.defernoColors.inkMuted else MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.padding(top = 16.dp))
    Button(onClick = onMic) { Text("Stop & review") }
}

@Composable
private fun ReviewBody(
    state: BrainDumpState,
    onAccept: (String) -> Unit,
    onDismissDraft: (String) -> Unit,
    onMic: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        if (state.drafts.isEmpty()) {
            StatusNote("Nothing to add from that — try again.")
        } else {
            if (state.relationsDropped) {
                Text(
                    text = "Some tasks were related; each is added on its own for now.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.defernoColors.inkMuted,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.drafts, key = { it.id }) { DraftCardRow(it, onAccept, onDismissDraft) }
            }
        }
        Spacer(Modifier.padding(top = 12.dp))
        TextButton(onClick = onMic) { Text("Record again") }
    }
}

@Composable
private fun DraftCardRow(card: DraftCard, onAccept: (String) -> Unit, onDismissDraft: (String) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(card.title, style = MaterialTheme.typography.titleMedium)
            card.detail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.defernoColors.inkMuted)
            }
            Spacer(Modifier.padding(top = 8.dp))
            when (card.status) {
                DraftStatus.Pending -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onAccept(card.id) },
                        modifier = Modifier.semantics { contentDescription = "Add task: ${card.title}" },
                    ) { Text("Add") }
                    TextButton(
                        onClick = { onDismissDraft(card.id) },
                        modifier = Modifier.semantics { contentDescription = "Dismiss task: ${card.title}" },
                    ) { Text("Dismiss") }
                }
                DraftStatus.Creating -> StatusNote("Adding…")
                DraftStatus.Created ->
                    Text("Added", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.defernoColors.success)
                DraftStatus.Offline -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatusNote("Offline — reconnect to add.")
                    TextButton(onClick = { onAccept(card.id) }) { Text("Retry") }
                }
                DraftStatus.Failed -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatusNote("Couldn't add.")
                    TextButton(onClick = { onAccept(card.id) }) { Text("Retry") }
                }
            }
        }
    }
}

@Composable
private fun FailedBody(reason: FailureReason, onMic: () -> Unit, onClose: () -> Unit) {
    val note = when (reason) {
        FailureReason.NotConfigured ->
            "Set up the assistant in Settings → Agent to turn brain dumps into tasks."
        FailureReason.Malformed -> "That didn't come back cleanly. Try again."
        FailureReason.Transport -> "Couldn't reach the assistant. Check your connection and try again."
        FailureReason.Speech -> "Didn't catch that. Try again."
    }
    StatusNote(note)
    Spacer(Modifier.padding(top = 16.dp))
    // For "not set up", re-recording won't help — guide to Settings via the note and just close. The other
    // reasons are transient, so offer a retry.
    if (reason == FailureReason.NotConfigured) {
        Button(onClick = onClose) { Text("Close") }
    } else {
        Button(onClick = onMic) { Text("Try again") }
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
