package com.circuitstitch.deferno.shell

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.circuitstitch.deferno.R
import com.circuitstitch.deferno.core.designsystem.component.Eyebrow
import com.circuitstitch.deferno.core.designsystem.component.MonoMeta
import com.circuitstitch.deferno.core.designsystem.component.PrimaryActionButton
import com.circuitstitch.deferno.core.designsystem.component.SegmentedFilter
import com.circuitstitch.deferno.core.designsystem.theme.defernoColors

/**
 * The **Brain dump** surface View (ADR-0027, #150; Stage 4 async rework, #212 follow-on), restyled to
 * the "See the trees" direction: a deliberately simple, low-overwhelm voice **recorder**. The person
 * speaks (or types) a stream of thought; the take is handed to the background **Brain dump worker** and
 * the proposed drafts surface in the **Inbox** Destination for triage — review never happens here (Stage
 * 3, ADR-0015 amendment).
 *
 * Like [NewScreen], this View keeps only the Android affordances: the `RECORD_AUDIO` permission
 * round-trip, the app-settings deep-link, and the system reduced-motion read. The rendering is the
 * stateless [BrainDumpContent], driven by [BrainDumpState], so the visual states are testable without
 * the permission plumbing.
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

    // Honor reduced-motion (design-principles.md): the mic-orb pulse is gated on the OS animator scale —
    // when the person has turned animations off (scale 0), the orb is static (no pulse, no waveform).
    val reducedMotion = remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
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
        reducedMotion = reducedMotion,
        modifier = modifier,
    )
}

/**
 * The stateless Brain dump body — every recorder visual state driven by [state], no platform affordances.
 * [reducedMotion] gates the mic-orb pulse (a static fallback when on); it defaults to `true` so static
 * renders (tests, previews) never spin an infinite animation. The recorder strings + this signature are
 * preserved from the pre-restyle surface so the existing render-state tests pin the same behavior.
 */
@Composable
internal fun BrainDumpContent(
    state: BrainDumpState,
    onMic: () -> Unit,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    reducedMotion: Boolean = true,
) {
    // Speak ↔ Type is a presentation-only choice (// ponytail: local state — BrainDumpComponent exposes
    // only the recorder seam, so there is no typed-extract callback to bind it to). Speak drives the real
    // recorder; Type is the text-entry affordance the design shows, with its own gentle note.
    var typeMode by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        // Edge-to-edge (ADR-0035): this overlay sits above the whole chrome, so it owns its system-bar
        // insets — title clears the status bar, controls clear the nav bar (mirrors SearchScreen).
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).padding(24.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Brain dump",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
                TextButton(onClick = onClose) { Text("Close") }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = "Speak or type whatever's on your mind. I'll draft trees from it — nothing's added " +
                    "until you accept them in your Inbox.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.defernoColors.inkMuted,
            )

            Spacer(Modifier.height(16.dp))

            // The Speak / Type choice. The recorder phases live entirely inside the Speak pane; switching
            // to Type while a recording is active is harmless (the orb's Stop is reachable by switching back).
            SegmentedFilter(
                options = speakTypeOptions,
                selectedIndex = if (typeMode) 1 else 0,
                onSelect = { typeMode = it == 1 },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(24.dp))

            if (typeMode) {
                TypePane(text = typed, onTextChange = { typed = it })
            } else {
                SpeakPane(
                    state = state,
                    onMic = onMic,
                    onClose = onClose,
                    onOpenSettings = onOpenSettings,
                    reducedMotion = reducedMotion,
                )
            }
        }
    }
}

/**
 * The SPEAK pane: the mic orb + the recorder lifecycle. The orb is a tap target for [onMic]; while
 * recording it shows a calm "LISTENING…" [Eyebrow] and (unless [reducedMotion]) a gentle pulse halo —
 * the static fallback is the steady orb. Recorder strings are preserved verbatim (pinned by tests).
 */
@Composable
private fun SpeakPane(
    state: BrainDumpState,
    onMic: () -> Unit,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
    reducedMotion: Boolean,
) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        when (state.phase) {
            Phase.Idle -> {
                MicOrb(active = false, reducedMotion = reducedMotion, contentDescription = "Start recording", onClick = onMic)
                Spacer(Modifier.height(16.dp))
                // Kept as a labelled tappable line so the existing test (onNodeWithText("Start recording"))
                // still finds + clicks it.
                OrbCaption(text = "Start recording", onClick = onMic)
            }
            Phase.Recording -> {
                MicOrb(active = true, reducedMotion = reducedMotion, contentDescription = "Stop", onClick = onMic)
                Spacer(Modifier.height(16.dp))
                Eyebrow("LISTENING…", modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                Spacer(Modifier.height(8.dp))
                Text("Recording…", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                TranscriptCard(
                    "Listening for what's on your mind. Tap to stop when you're done — I'll draft from it.",
                )
                Spacer(Modifier.height(20.dp))
                PrimaryActionButton(text = "Stop", onClick = onMic, icon = null)
            }
            Phase.Enqueued -> {
                StatusNote("Transcribing in the background — we'll let you know when your drafts are ready in the Inbox.")
                Spacer(Modifier.height(8.dp))
                MonoMeta("Drafts will appear in your Inbox", modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(20.dp))
                PrimaryActionButton(text = "Done", onClick = onClose, icon = null)
            }
            Phase.Failed -> {
                StatusNote("Couldn't record that. Try again.")
                Spacer(Modifier.height(20.dp))
                PrimaryActionButton(text = "Try again", onClick = onMic, icon = null)
            }
            Phase.PermissionDenied -> PermissionBody(permanent = false, onMic = onMic, onOpenSettings = onOpenSettings)
            Phase.PermissionPermanentlyDenied -> PermissionBody(permanent = true, onMic = onMic, onOpenSettings = onOpenSettings)
        }
    }
}

/**
 * The TYPE pane: a calm multi-line text area + a draft-count hint + the primary extract action. (//
 * ponytail: typed brain-dump extraction has no seam on [BrainDumpComponent] — it only records — so the
 * action is a documented no-op placeholder until a typed-extract callback lands; it stays disabled until
 * there's text so it never *looks* like it dropped the person's words silently.)
 */
@Composable
private fun TypePane(text: String, onTextChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            label = { Text("What's on your mind?") },
            minLines = 6,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 160.dp)
                .semantics { contentDescription = "Brain dump text" },
        )
        Spacer(Modifier.height(12.dp))
        MonoMeta(
            text = if (text.isBlank()) "No drafts yet" else "Ready to draft from what you typed",
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        // No typed-extract seam yet — keep the affordance, gate it on text, no-op for now (see KDoc).
        PrimaryActionButton(
            text = "Extract drafts",
            onClick = { /* ponytail: no typed-extract seam on BrainDumpComponent yet */ },
            icon = null,
            enabled = text.isNotBlank(),
        )
    }
}

/**
 * The mic orb (a round tap target). [active] = recording (filled primary); idle = a soft container. When
 * recording and motion is allowed, a gentle scale pulse plays behind the orb; [reducedMotion] swaps it
 * for a steady static orb (design-principles.md: honor reduced-motion).
 */
@Composable
private fun MicOrb(
    active: Boolean,
    reducedMotion: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    // The animated halo: only when recording AND motion is allowed. Static otherwise.
    val haloScale: Float
    val haloAlpha: Float
    if (active && !reducedMotion) {
        val transition = rememberInfiniteTransition(label = "mic-pulse")
        haloScale = transition.animateFloat(
            initialValue = 1f,
            targetValue = 1.35f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1100, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "halo-scale",
        ).value
        haloAlpha = transition.animateFloat(
            initialValue = 0.45f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1100, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "halo-alpha",
        ).value
    } else {
        haloScale = 1f
        haloAlpha = if (active) 0.25f else 0f
    }
    Box(contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(120.dp)
                .scale(haloScale)
                .graphicsLayer { alpha = haloAlpha }
                .clip(CircleShape)
                .background(scheme.primary),
        )
        Surface(
            color = if (active) scheme.primary else scheme.primaryContainer,
            contentColor = if (active) scheme.onPrimary else MaterialTheme.defernoColors.amberDeep,
            shape = CircleShape,
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .clickable(onClickLabel = contentDescription, onClick = onClick)
                .semantics { this.contentDescription = contentDescription },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(R.drawable.ic_mic),
                    contentDescription = null,
                    modifier = Modifier.size(44.dp),
                )
            }
        }
    }
}

/** A labelled, tappable caption beneath the idle orb — kept findable-by-text so the recorder test passes. */
@Composable
private fun OrbCaption(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClickLabel = text, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/** A calm transcript card — the well the live transcript reads into during a recording. */
@Composable
private fun TranscriptCard(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.defernoColors.inkMuted,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun PermissionBody(permanent: Boolean, onMic: () -> Unit, onOpenSettings: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (permanent) {
            StatusNote("Brain dump needs microphone access, which is turned off for this app.")
            Spacer(Modifier.height(20.dp))
            PrimaryActionButton(text = "Open settings", onClick = onOpenSettings, icon = null)
        } else {
            StatusNote("Brain dump needs microphone access.")
            Spacer(Modifier.height(20.dp))
            PrimaryActionButton(text = "Allow microphone", onClick = onMic, icon = null)
        }
    }
}

/** A gentle muted note — the calm, never-judgmental copy the surface speaks in. */
@Composable
private fun StatusNote(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.defernoColors.inkMuted)
}

/** The Speak / Type mode labels. */
private val speakTypeOptions = listOf("Speak", "Type")
