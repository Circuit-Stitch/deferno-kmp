package com.circuitstitch.deferno.feature.braindumps.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.circuitstitch.deferno.core.designsystem.component.DefernoIcons
import com.circuitstitch.deferno.core.designsystem.component.Eyebrow
import com.circuitstitch.deferno.core.designsystem.component.MonoMeta
import com.circuitstitch.deferno.core.designsystem.component.PrimaryActionButton
import com.circuitstitch.deferno.core.designsystem.component.TextLink
import com.circuitstitch.deferno.core.designsystem.theme.defernoColors
import com.circuitstitch.deferno.core.model.BrainDumpDraft

/** Vertical gap between Inbox list rows (header · section · draft cards · footer) — shared by the
 *  Android and desktop lists. */
internal val InboxCardSpacing = 12.dp

/**
 * The calm empty state ("See the trees" restyle): an inbox with no drafts is normal, not broken
 * (ADR-0015 Inbox amendment). A quiet ✦ over a one-line reassurance, centred.
 */
@Composable
internal fun EmptyInbox(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            DefernoIcons.Sparkle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Inbox zero",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Brain-dump drafts land here for you to review. Speak a brain dump and Deferno turns it into draft tasks for this list.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * One reviewable draft as a "See the trees" card: a `DRAFTED` eyebrow with a quiet **Dismiss** in the
 * corner, the title, an optional "Due …" mono line, the dictated notes, any gentle offline/error
 * [note], and a thumb-reachable **Add task** primary action. Add task commits it (online-only,
 * ADR-0016); Dismiss drops it (recoverably). While the create is in flight ([accepting]) the action
 * yields to a progress spinner and the Dismiss hides (taps are ignored).
 *
 * ponytail: no kind chip — the brain-dump pipeline is Task-only end to end (the draft carries no kind
 * and no signal to derive one). Kind-aware drafts are tracked in #259; add the chip there.
 */
@Composable
internal fun DraftCard(
    draft: BrainDumpDraft,
    accepting: Boolean,
    note: String?,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(scheme.surfaceContainerLow)
            .border(1.dp, scheme.outlineVariant, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Eyebrow(text = "DRAFTED")
            if (!accepting) {
                TextLink(text = "Dismiss", onClick = onDismiss, color = MaterialTheme.defernoColors.inkMuted)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(draft.title, style = MaterialTheme.typography.titleMedium)
        draft.dueLine()?.let {
            Spacer(Modifier.height(6.dp))
            MonoMeta(text = it)
        }
        draft.notes?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        note?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.error,
            )
        }
        Spacer(Modifier.height(14.dp))
        if (accepting) {
            Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        } else {
            PrimaryActionButton(text = "Add task", onClick = onAccept, icon = DefernoIcons.Check)
        }
    }
}

/** A short "Due …" line for a draft's deadline, or null when it carries none (mirrors the Brain dump overlay). */
private fun BrainDumpDraft.dueLine(): String? = completeBy?.let { due ->
    deadlineTimeOfDay?.let { time -> "Due $due at $time" } ?: "Due $due"
}
