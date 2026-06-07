package com.circuitstitch.deferno.feature.plan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.circuitstitch.deferno.core.designsystem.theme.defernoColors
import com.circuitstitch.deferno.core.designsystem.theme.plexMono
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.WorkingState

// Shared building blocks for the Plan View (#27): thin, stateless Composables that render a [Task].
// Kept in commonMain (Android + desktop) so a future desktop Plan View can reuse them; the
// Android-native screen that arranges them lives in androidMain (PlanScreen.kt).

/** Minimum height for a tappable row/control — design-principles.md "≥44–48dp" touch targets. */
internal val MinTouchTarget = 48.dp

/**
 * A calm, **static** "working…" strip — the reduced-motion alternative to an animated progress bar
 * (design-principles.md: honor reduced-motion). Plain text, announced politely to screen readers.
 */
@Composable
internal fun LoadingStrip(label: String, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/** Gentle, non-judgmental empty plan (design-principles.md: lapses are normal; no pressure). */
@Composable
internal fun EmptyPlan(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Your plan is clear", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Nothing scheduled for today. Add something when you're ready — no pressure.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.defernoColors.inkMuted,
        )
    }
}

/** A tappable plan entry: title, optional human [Task.ref] in IBM Plex Mono, and a state label. */
@Composable
internal fun PlanTaskRow(task: Task, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .clickable(onClickLabel = "Open ${task.title}", onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                task.ref?.let { ref ->
                    Text(
                        text = ref,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = plexMono(),
                        color = MaterialTheme.defernoColors.inkMuted,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            StateLabel(task.workingState)
        }
    }
}

/** Plain-text working-state label (colour reinforces, never sole signal — WCAG). */
@Composable
private fun StateLabel(state: WorkingState, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val brand = MaterialTheme.defernoColors
    val (label, container, content) = when (state) {
        WorkingState.Open -> Triple("Open", scheme.surfaceVariant, scheme.onSurfaceVariant)
        WorkingState.InProgress -> Triple("In progress", scheme.primaryContainer, scheme.onPrimaryContainer)
        WorkingState.InReview -> Triple("In review", scheme.secondaryContainer, scheme.onSecondaryContainer)
        WorkingState.Done -> Triple("Done", brand.successContainer, brand.onSuccessContainer)
        WorkingState.Dropped -> Triple("Set aside", scheme.surfaceVariant, brand.inkMuted)
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = content,
        // TalkBack reads the bare label without context; prefix it so it's self-describing.
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(container)
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clearAndSetSemantics { contentDescription = "Status: $label" },
    )
}
