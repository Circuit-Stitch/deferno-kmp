package com.circuitstitch.deferno.shell.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.circuitstitch.deferno.core.designsystem.component.MonoMeta
import com.circuitstitch.deferno.core.designsystem.component.TreeChip
import com.circuitstitch.deferno.core.designsystem.theme.defernoColors
import com.circuitstitch.deferno.shell.ActivityComponent
import com.circuitstitch.deferno.shell.ActivityFeedRow
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * The **Activity** Destination View (#260): a calm, reverse-chronological feed of every change the app
 * has recorded in the offline-first ledger. A thin render of [ActivityComponent.state] — each row shows
 * what changed, who made it (a source chip), and when. Server-sourced ("via Website" / "via MCP") rows
 * land here too once the reconcile seam tags them, with no View change.
 *
 * Shared between the Android shell and the desktop shell (ADR-0004 #27): the component is Compose-free in
 * `:app:shell`, the atoms are cross-platform, so one View serves both platforms (no per-platform drift).
 */
@Composable
fun ActivityScreen(component: ActivityComponent, modifier: Modifier = Modifier) {
    val state by component.state.collectAsState()
    Column(modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "Activity",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() },
                )
                MonoMeta(text = if (state.rows.size == 1) "1 change" else "${state.rows.size} changes")
            }
        }
        if (state.rows.isEmpty()) {
            EmptyActivity(Modifier.fillMaxSize())
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                // Empty inset on desktop; clears the nav bar on Android.
                contentPadding = WindowInsets.systemBars.only(WindowInsetsSides.Bottom).asPaddingValues(),
            ) {
                items(state.rows, key = { it.seq }) { row ->
                    ActivityRowView(row)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

/** One feed row: the change, a source chip, and the time it was applied. */
@Composable
private fun ActivityRowView(row: ActivityFeedRow) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = row.summary, style = MaterialTheme.typography.titleMedium)
            TreeChip(text = row.sourceLabel, filled = false)
        }
        MonoMeta(text = formatWhen(row.recordedAt))
    }
}

@Composable
private fun EmptyActivity(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Nothing yet",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = "Every change you make — and every change synced from elsewhere — shows up here, newest first.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.defernoColors.inkMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** A calm absolute timestamp — "Jun 21 · 09:45" in the device's time zone. */
private fun formatWhen(instant: Instant): String {
    val t = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    val hh = t.hour.toString().padStart(2, '0')
    val mm = t.minute.toString().padStart(2, '0')
    return "${MONTHS[t.monthNumber - 1]} ${t.dayOfMonth} · $hh:$mm"
}
