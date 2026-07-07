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
import com.circuitstitch.deferno.core.data.activity.ActivitySource
import com.circuitstitch.deferno.core.data.activity.ActivitySummary
import com.circuitstitch.deferno.core.data.activity.ActivityVerb
import com.circuitstitch.deferno.core.designsystem.component.MonoMeta
import com.circuitstitch.deferno.core.designsystem.component.TreeChip
import com.circuitstitch.deferno.core.designsystem.format.formatInstant
import com.circuitstitch.deferno.core.designsystem.resources.Res
import com.circuitstitch.deferno.core.designsystem.resources.activity_change_count
import com.circuitstitch.deferno.core.designsystem.resources.activity_empty_body
import com.circuitstitch.deferno.core.designsystem.resources.activity_empty_title
import com.circuitstitch.deferno.core.designsystem.resources.activity_source_mcp
import com.circuitstitch.deferno.core.designsystem.resources.activity_source_mobile
import com.circuitstitch.deferno.core.designsystem.resources.activity_source_unknown
import com.circuitstitch.deferno.core.designsystem.resources.activity_source_website
import com.circuitstitch.deferno.core.designsystem.resources.activity_summary_changed_settings
import com.circuitstitch.deferno.core.designsystem.resources.activity_summary_cleared_occurrence_chore
import com.circuitstitch.deferno.core.designsystem.resources.activity_summary_cleared_occurrence_event
import com.circuitstitch.deferno.core.designsystem.resources.activity_summary_cleared_occurrence_habit
import com.circuitstitch.deferno.core.designsystem.resources.activity_summary_commented
import com.circuitstitch.deferno.core.designsystem.resources.activity_summary_created_chore
import com.circuitstitch.deferno.core.designsystem.resources.activity_summary_created_event
import com.circuitstitch.deferno.core.designsystem.resources.activity_summary_created_habit
import com.circuitstitch.deferno.core.designsystem.resources.activity_summary_created_item
import com.circuitstitch.deferno.core.designsystem.resources.activity_summary_created_task
import com.circuitstitch.deferno.core.designsystem.resources.activity_summary_deleted_task
import com.circuitstitch.deferno.core.designsystem.resources.activity_summary_moved_item
import com.circuitstitch.deferno.core.designsystem.resources.activity_summary_updated_item
import com.circuitstitch.deferno.core.designsystem.resources.activity_summary_updated_occurrence_chore
import com.circuitstitch.deferno.core.designsystem.resources.activity_summary_updated_occurrence_event
import com.circuitstitch.deferno.core.designsystem.resources.activity_summary_updated_occurrence_habit
import com.circuitstitch.deferno.core.designsystem.resources.activity_summary_updated_plan
import com.circuitstitch.deferno.core.designsystem.resources.activity_summary_updated_task
import com.circuitstitch.deferno.core.designsystem.resources.activity_when_pattern
import com.circuitstitch.deferno.core.designsystem.theme.defernoColors
import com.circuitstitch.deferno.shell.ActivityComponent
import com.circuitstitch.deferno.shell.ActivityFeedRow
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

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
                // ponytail: title lives in the shell top bar (chromeFor → ForDestination(Activity)); the body
                // carries only the count so the screen doesn't show "Activity" twice. Matches Settings/Calendar.
                MonoMeta(text = pluralStringResource(Res.plurals.activity_change_count, state.rows.size, state.rows.size))
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
            Text(text = row.summaryInfo.text, style = MaterialTheme.typography.titleMedium)
            TreeChip(text = row.source.label, filled = false)
        }
        // A calm absolute timestamp — "Jun 21 · 09:45" in the device's time zone and language.
        MonoMeta(text = formatInstant(row.recordedAt, stringResource(Res.string.activity_when_pattern)))
    }
}

/** The localized one-liner for a typed [ActivitySummary] — per-kind keys keep article/gender right. */
private val ActivitySummary.text: String
    @Composable get() = when (verb) {
        ActivityVerb.ChangedSettings -> stringResource(Res.string.activity_summary_changed_settings)
        ActivityVerb.Created -> when (kindToken) {
            "task" -> stringResource(Res.string.activity_summary_created_task)
            "chore" -> stringResource(Res.string.activity_summary_created_chore)
            "habit" -> stringResource(Res.string.activity_summary_created_habit)
            "event" -> stringResource(Res.string.activity_summary_created_event)
            else -> stringResource(Res.string.activity_summary_created_item)
        }
        ActivityVerb.MovedItem -> stringResource(Res.string.activity_summary_moved_item)
        ActivityVerb.UpdatedPlan -> stringResource(Res.string.activity_summary_updated_plan)
        ActivityVerb.DeletedTask -> stringResource(Res.string.activity_summary_deleted_task)
        ActivityVerb.UpdatedTask -> stringResource(Res.string.activity_summary_updated_task)
        ActivityVerb.ClearedOccurrence -> when (kindToken) {
            "chore" -> stringResource(Res.string.activity_summary_cleared_occurrence_chore)
            "habit" -> stringResource(Res.string.activity_summary_cleared_occurrence_habit)
            else -> stringResource(Res.string.activity_summary_cleared_occurrence_event)
        }
        ActivityVerb.UpdatedOccurrence -> when (kindToken) {
            "chore" -> stringResource(Res.string.activity_summary_updated_occurrence_chore)
            "habit" -> stringResource(Res.string.activity_summary_updated_occurrence_habit)
            else -> stringResource(Res.string.activity_summary_updated_occurrence_event)
        }
        ActivityVerb.UpdatedItem -> stringResource(Res.string.activity_summary_updated_item)
        ActivityVerb.Commented -> stringResource(Res.string.activity_summary_commented)
    }

/** The localized "who" chip: a local write reads "Mobile app"; remote writes name their surface. */
private val ActivitySource.label: String
    @Composable get() = when (this) {
        ActivitySource.Mobile -> stringResource(Res.string.activity_source_mobile)
        ActivitySource.Website -> stringResource(Res.string.activity_source_website)
        ActivitySource.Mcp -> stringResource(Res.string.activity_source_mcp)
        ActivitySource.Unknown -> stringResource(Res.string.activity_source_unknown)
    }

@Composable
private fun EmptyActivity(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(Res.string.activity_empty_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(Res.string.activity_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.defernoColors.inkMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
