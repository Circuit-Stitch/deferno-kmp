package com.circuitstitch.deferno.feature.tasks.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.circuitstitch.deferno.core.designsystem.theme.defernoColors
import com.circuitstitch.deferno.core.designsystem.theme.plexMono
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.WorkingState
import kotlinx.datetime.LocalTime

// Shared building blocks for the Tasks Views (#27). These are thin, stateless Composables — they
// render a [Task] and surface taps via callbacks; all logic stays in the shared components (#25).
// Design-principles.md: calm flat lists over dense cards, large touch targets, plain labels, and
// TalkBack semantics on every interactive element.

/** Minimum height for a tappable row/control — design-principles.md "≥44–48dp" touch targets. */
internal val MinTouchTarget = 48.dp

/** A [LocalTime] as a friendly 12-hour clock, e.g. `14:30` → "2:30 PM" (#348 display). */
internal fun LocalTime.toDisplayTime(): String {
    val h12 = (hour % 12).let { if (it == 0) 12 else it }
    val suffix = if (hour < 12) "AM" else "PM"
    return "$h12:${minute.toString().padStart(2, '0')} $suffix"
}

/**
 * A calm, single-line section header for a pane: a title (marked as a heading for screen readers)
 * with an optional leading **Back** affordance and trailing [actions]. Used by every Tasks pane so
 * placement stays predictable (design-principles.md "consistent placement, minimal surprise").
 */
@Composable
internal fun PaneHeader(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.heightIn(min = 56.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                androidx.compose.material3.TextButton(
                    onClick = onBack,
                    modifier = Modifier.heightIn(min = MinTouchTarget),
                ) { Text("Back") }
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
                    .semantics { heading() },
            )
            actions()
        }
    }
}

/**
 * A calm, **static** "working…" strip — the reduced-motion alternative to an animated progress bar
 * (design-principles.md: honor reduced-motion; provide non-motion alternatives to animated feedback).
 * It states what's happening as plain text and announces changes politely to screen readers, with no
 * animation at all. Shared by the list (refresh) and detail (hydrate) panes.
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

/** Gentle, non-judgmental empty state (design-principles.md): a kind title + supportive body, centered. */
@Composable
internal fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.defernoColors.inkMuted,
        )
    }
}

/**
 * A small, readable badge for a Task's [WorkingState]. The label is plain text (no jargon, no
 * shaming — design-principles.md) so it reads correctly under TalkBack; colour is reinforcement,
 * never the sole signal (WCAG: don't rely on colour alone).
 */
@Composable
internal fun WorkingStateBadge(state: WorkingState, modifier: Modifier = Modifier) {
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
        // TalkBack reads the bare label ("In progress") without context; prefix it so the badge is
        // self-describing as a status (the visible text stays unchanged).
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(container)
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clearAndSetSemantics { contentDescription = "Status: $label" },
    )
}

/**
 * A tappable Task row: title, optional human [Task.ref] in IBM Plex Mono, a pinned marker, and the
 * working-state badge. Flat with the divider supplied by the list (calm, low-overwhelm). The whole
 * row is one large touch target with a spoken "Open <title>" action label.
 */
@Composable
internal fun TaskRow(task: Task, onClick: () -> Unit, modifier: Modifier = Modifier) {
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (task.pinned) {
                        Text(
                            text = "★",
                            color = MaterialTheme.defernoColors.amberDeep,
                            modifier = Modifier.clearAndSetSemantics { contentDescription = "Pinned" },
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = task.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                task.ref?.let { ref ->
                    Text(
                        text = ref,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = plexMono(),
                        color = MaterialTheme.defernoColors.inkMuted,
                    )
                }
                // The deadline clock (#348), shown only when the task carries one (not all-day).
                task.deadlineTimeOfDay?.let { time ->
                    Text(
                        text = "Due ${time.toDisplayTime()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.defernoColors.inkMuted,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            WorkingStateBadge(task.workingState)
        }
    }
}
