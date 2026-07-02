package com.circuitstitch.deferno.feature.tasks.ui

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.circuitstitch.deferno.core.designsystem.resources.Res
import com.circuitstitch.deferno.core.designsystem.resources.common_back
import com.circuitstitch.deferno.core.designsystem.theme.defernoColors
import com.circuitstitch.deferno.core.model.ExternalRef
import com.circuitstitch.deferno.core.model.ItemSource
import org.jetbrains.compose.resources.stringResource

// Shared building blocks for the Tasks Views (#27). These are thin, stateless Composables — they
// render a Task and surface taps via callbacks; all logic stays in the shared components (#25).
// Design-principles.md: calm flat lists over dense cards, large touch targets, plain labels, and
// TalkBack semantics on every interactive element.

/** Minimum height for a tappable row/control — design-principles.md "≥44–48dp" touch targets. */
internal val MinTouchTarget = 48.dp

// --- External provenance display: the GitHub-import affordances (the source mark, the `[GitHub#N]` ref
// prefix, the Source cell). Pure helpers so the tree row and the detail render identically. ---

/** Human label for an external provenance [source] — the Source-cell + ref-prefix display name. */
internal fun sourceLabel(source: ItemSource): String = when (source) {
    ItemSource.GitHub -> "GitHub"
    ItemSource.GoogleCalendar -> "Google Calendar"
}

// A trailing issue/PR number on an opaque provider ref (`owner/repo#42` → `42`). A calendar id
// (`{calendar_id}:{event_id}`) has no trailing `#N`, so it yields no prefix.
private val ExternalRefNumber = Regex("#(\\d+)$")

/**
 * The dimmed `[GitHub#N]` ref prefix for an imported item, or `null` when there's no provenance or no
 * trailing issue/PR number. Derived from the opaque provider id — no extra round-trip — and it stays out
 * of the tracker-owned title.
 */
internal fun externalRefLabel(source: ItemSource?, externalId: String?): String? {
    if (source == null || externalId == null) return null
    val number = ExternalRefNumber.find(externalId)?.groupValues?.get(1) ?: return null
    return "[${sourceLabel(source)}#$number]"
}

/**
 * The detail Source-cell label: the opaque tracker ref (`owner/repo#N`, the client-side "origin label")
 * when it's a tracker ref, else the provider label as a fallback (for a non-tracker id with no issue
 * number, e.g. a calendar event).
 */
internal fun sourceOriginLabel(external: ExternalRef): String =
    if (external.id.contains('#')) external.id else sourceLabel(external.source)

/**
 * A title with the dimmed `[GitHub#N]` ref prefix prepended (a trailing space), or the bare title when the
 * item has no tracker ref. The prefix wears [prefixColor] (muted); the caller supplies it from the theme.
 */
internal fun titleWithExternalRef(
    title: String,
    source: ItemSource?,
    externalId: String?,
    prefixColor: Color,
): AnnotatedString = buildAnnotatedString {
    externalRefLabel(source, externalId)?.let { prefix ->
        withStyle(SpanStyle(color = prefixColor)) { append(prefix) }
        append(" ")
    }
    append(title)
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
                ) { Text(stringResource(Res.string.common_back)) }
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
