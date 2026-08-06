package com.circuitstitch.deferno.feature.tasks.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.circuitstitch.deferno.core.designsystem.component.DefernoIcons
import com.circuitstitch.deferno.core.designsystem.component.MarkdownDescription
import com.circuitstitch.deferno.core.designsystem.component.MonoMeta
import com.circuitstitch.deferno.core.designsystem.component.OccurrenceStateChip
import com.circuitstitch.deferno.core.designsystem.component.kindLabel
import com.circuitstitch.deferno.core.designsystem.component.occurrenceStateChipStyle
import com.circuitstitch.deferno.core.designsystem.format.currentToday
import com.circuitstitch.deferno.core.designsystem.format.formatInstant
import com.circuitstitch.deferno.core.designsystem.resources.Res
import com.circuitstitch.deferno.core.designsystem.resources.common_close
import com.circuitstitch.deferno.core.designsystem.resources.common_labels
import com.circuitstitch.deferno.core.designsystem.resources.common_status_in_review
import com.circuitstitch.deferno.core.designsystem.resources.new_notes_label
import com.circuitstitch.deferno.core.designsystem.resources.settings_security_device_date_pattern
import com.circuitstitch.deferno.core.designsystem.resources.tasks_cadence_with_bound
import com.circuitstitch.deferno.core.designsystem.resources.tasks_definition_state_active
import com.circuitstitch.deferno.core.designsystem.resources.tasks_definition_state_archived
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_item_not_found_body
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_item_not_found_title
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_loading
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_no_description
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_property_kind
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_property_next_due
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_property_repeats
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_property_status
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_property_today
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_today_cancelled
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_today_not_firing
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_today_row_a11y
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_today_unavailable
import com.circuitstitch.deferno.core.designsystem.resources.tasks_recurrence_series_ended
import com.circuitstitch.deferno.core.designsystem.theme.defernoColors
import com.circuitstitch.deferno.core.model.DayFiring
import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.Item
import com.circuitstitch.deferno.core.model.OccurrenceState
import com.circuitstitch.deferno.core.model.RecurrenceCursor
import com.circuitstitch.deferno.core.model.RecurringDefinition
import com.circuitstitch.deferno.core.model.TodayOccurrence
import com.circuitstitch.deferno.core.model.recurrenceCursor
import com.circuitstitch.deferno.feature.tasks.DefinitionDetailState
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

/**
 * The **recurring-definition** detail body (#383) — platform-neutral Compose (Android + desktop), the shared
 * half of the detail that opens a [[Habit]], [[Chore]] or [[Event]]. Until this slice those three kinds could
 * not be opened at all on any platform: every detail route was `TaskId`-typed, so a recurring id either never
 * reached one or landed on the Task detail's "Task not found".
 *
 * A thin, stateless renderer of [DefinitionDetailState] — and **read-only by design**, so unlike
 * [TaskDetailContent] it takes exactly one callback ([onClose]). Every write on a definition (the rule,
 * per-field patches, delete) belongs to #378/#388/#389, and the one write that already works kind-neutrally —
 * the Archive/Restore light switch — lives in the tree's command menu (#299) rather than being duplicated
 * here. That is why the header carries a close × where the Task detail carries a ⋮ overflow: there is no
 * write to hang off a kebab, and an empty one would be a promise this screen cannot keep.
 *
 * The properties table is deliberately the **same** [PropertyTableRow] table the Task detail draws, with the
 * rows a definition can actually answer: KIND · REPEATS · NEXT DUE · TODAY · STATUS · LABELS. A Task's WHEN /
 * TARGET DATE / PRIORITY have no counterpart here (a definition has a walked [[Recurrence cursor]], not a
 * deadline), and STATUS reads the [[Definition state]] light switch, which is a different axis from a Task's
 * [[Working state]] and can never be "done".
 *
 * The series-era chain (`state.eras`) and `originLabel` ride the component's state but render nowhere yet —
 * #395 owns that surface; this issue only carries them.
 */
@Composable
internal fun DefinitionDetailContent(
    state: DefinitionDetailState,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    // Whether the body draws its own close × (ADR-0044's `showHeaderOverflow` split, for the affordance this
    // screen has instead of a kebab): true in a two-pane host, where nothing above the body can dismiss it;
    // false on the compact single-pane fold, where the shell's drilled ← bar already owns going back and a
    // second affordance would double it.
    showClose: Boolean = true,
) {
    val definition = state.definition
    // Opaque background for the same reason the Task detail paints one: this body renders as an overlay above
    // the tree on a compact fold, so it must paint a full surface and consume taps.
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        // Bottom nav-bar inset only — the shell top bar / two-pane host already sits below the status bar.
        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars)) {
            if (state.isHydrating) {
                LoadingStrip(label = stringResource(Res.string.tasks_detail_loading))
            }
            when {
                // The KIND-NEUTRAL empty state, not the Task-worded `tasks_detail_not_found_*`: this screen is
                // reached before it can know which of three kinds went missing, so it says "item".
                state.isMissing -> EmptyState(
                    title = stringResource(Res.string.tasks_detail_item_not_found_title),
                    body = stringResource(Res.string.tasks_detail_item_not_found_body),
                )
                definition == null -> Unit // brief hydrating gap before the cached row is observed
                else -> DefinitionBody(
                    definition = definition,
                    item = state.item,
                    today = state.today,
                    isHydrating = state.isHydrating,
                    onClose = onClose,
                    showClose = showClose,
                )
            }
        }
    }
}

@Composable
private fun DefinitionBody(
    definition: RecurringDefinition,
    // The [Item] projection of the same definition (`RecurringDefinition.toItem()`), which is what the SHARED
    // display readings are written against. Nullable only because the state's two halves are independent
    // fields; in practice it is present whenever [definition] is.
    item: Item?,
    today: TodayOccurrence,
    isHydrating: Boolean,
    onClose: () -> Unit,
    showClose: Boolean,
) {
    // Resolve "today" FROM the zone and thread the pair together. `currentToday(zone)`'s KDoc is explicit that
    // a caller holding the two independently can silently slip a day near a date boundary; the shared
    // [recurrenceRulePhrase] applies the same coupling internally off the zone it is handed, so every reading
    // on this screen is resolved in one zone. #392 makes that an account zone with no retype here.
    val zone = TimeZone.currentSystemDefault()
    val now = currentToday(zone)
    // Keyed on the id so re-keying the detail slot onto a different definition starts at the top — an unkeyed
    // state would carry the previous item's scroll position in, opening the next one past its title (#231).
    val scrollState = remember(definition.id) { ScrollState(0) }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scrollState),
    ) {
        DefinitionHeader(definition = definition, onClose = onClose, showClose = showClose)
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // NOTES — the same markdown path the Task detail takes: a GitHub-imported body is GFM, and the
            // muted "no description yet" only appears once hydration has settled (so it never flashes).
            val description = definition.description
            if (!description.isNullOrBlank() || !isHydrating) {
                SectionHeader(stringResource(Res.string.new_notes_label))
            }
            when {
                !description.isNullOrBlank() -> MarkdownDescription(
                    markdown = description,
                    modifier = Modifier.fillMaxWidth(),
                    sheetTitle = stringResource(Res.string.new_notes_label),
                )
                !isHydrating -> Text(
                    text = stringResource(Res.string.tasks_detail_no_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.defernoColors.inkMuted,
                )
            }
            DefinitionPropertiesSection(
                definition = definition,
                item = item,
                today = today,
                zone = zone,
                now = now,
            )
        }
    }
}

/**
 * The heading: the definition's title at headline rank over its mono `#N` ref, with the close × riding
 * top-right when [showClose]. The Task detail's connected-parent branch is deliberately absent — a
 * definition's `parentId` is carried but its parent summary is not read on this slice, and drawing half a
 * branch would suggest a drill-in that does not exist yet.
 */
@Composable
private fun DefinitionHeader(definition: RecurringDefinition, onClose: () -> Unit, showClose: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text = definition.title, style = MaterialTheme.typography.headlineSmall)
            shortRef(definition.ref)?.let { MonoMeta(text = it) }
        }
        if (showClose) {
            IconButton(onClick = onClose) {
                Icon(DefernoIcons.Close, contentDescription = stringResource(Res.string.common_close))
            }
        }
    }
}

/**
 * The definition's properties table — the Task detail's table shape ([PropertyTableRow] +
 * [PropertyTableDivider]) over the six rows a definition can answer. Every row is always present: unlike the
 * Task table, where a missing WHEN row means "no deadline set" and the row would otherwise be an editor for
 * one, these are read-only facts and a definition that cannot answer one says so in the cell.
 */
@Composable
private fun DefinitionPropertiesSection(
    definition: RecurringDefinition,
    item: Item?,
    today: TodayOccurrence,
    zone: TimeZone,
    now: LocalDate,
    modifier: Modifier = Modifier,
) {
    val rows = buildList<@Composable () -> Unit> {
        add {
            // KIND: the plain kind word, from the design system's shared kind vocabulary — the same one the
            // tree row's marker and the Plan row read, so the four kinds are named identically everywhere.
            PropertyTableRow(label = stringResource(Res.string.tasks_detail_property_kind)) {
                PropertyValueText(kindLabel(definition.kind))
            }
        }
        add {
            // REPEATS: cadence + bound through the SHARED reading, plus the monthly/yearly anchor the tree row
            // drops and this surface owns (see [recurrenceRulePhrase]).
            PropertyTableRow(label = stringResource(Res.string.tasks_detail_property_repeats)) {
                PropertyValueText(item?.let { recurrenceRulePhrase(it, zone) })
            }
        }
        add {
            PropertyTableRow(label = stringResource(Res.string.tasks_detail_property_next_due)) {
                NextDueCell(item = item, cursorAt = definition.cursorAt, zone = zone, now = now)
            }
        }
        add {
            PropertyTableRow(label = stringResource(Res.string.tasks_detail_property_today)) {
                TodayCell(today)
            }
        }
        add {
            // STATUS: the [[Definition state]] light switch — NOT a Task's working state. A Habit is never
            // "done"; it is switched on or off, which is why this cell is plain text and not the Task
            // detail's three-node journey track.
            PropertyTableRow(label = stringResource(Res.string.tasks_detail_property_status)) {
                PropertyValueText(definitionStateLabel(definition.definitionState))
            }
        }
        add {
            // LABELS: the Task detail's read-only pills with no Edit toggle and no add field — this whole
            // screen is read-only, so an editor here would be an affordance with nothing behind it.
            PropertyTableRow(label = stringResource(Res.string.common_labels)) {
                ReadOnlyLabelsCell(definition.labels)
            }
        }
    }
    Column(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium),
    ) {
        rows.forEachIndexed { i, row ->
            if (i > 0) PropertyTableDivider()
            row()
        }
    }
}

/** A properties-table value: the plain reading, or a muted em dash when there is nothing to say. */
@Composable
private fun PropertyValueText(value: String?) {
    Text(
        text = value ?: "—",
        style = MaterialTheme.typography.bodyLarge,
        color = if (value == null) MaterialTheme.defernoColors.inkMuted else MaterialTheme.colorScheme.onSurface,
    )
}

/**
 * The NEXT DUE cell: where the series has walked to, read against [now].
 *
 * It goes through [Item.recurrenceCursor], which already answers the one question a renderer must not
 * re-litigate — an **Archived** definition reads [RecurrenceCursor.NoCursor] even though the server left its
 * `complete_by` exactly where it stopped, so a Habit switched off in July never reads "overdue since July".
 * The absolute day is therefore printed only on the [RecurrenceCursor.DueOn] arm: [cursorAt] is present on an
 * archived definition too, and rendering it off any other arm would smuggle back the stale cursor the
 * reading just refused.
 */
@Composable
private fun NextDueCell(item: Item?, cursorAt: Instant?, zone: TimeZone, now: LocalDate) {
    val cursor = item?.recurrenceCursor(zone, now) ?: RecurrenceCursor.NoCursor
    val text = when (cursor) {
        RecurrenceCursor.NoCursor -> null
        RecurrenceCursor.Exhausted -> stringResource(Res.string.tasks_recurrence_series_ended)
        is RecurrenceCursor.DueOn -> {
            val relative = relativeDayText(cursor.day)
            val day = cursorAt?.let { formatInstant(it, stringResource(Res.string.settings_security_device_date_pattern)) }
            // "Aug 8, 2026 · In 3 days" — the absolute day beside its relative reading, exactly as the Task
            // detail's WHEN cell pairs them. The joiner is the catalog's own " · " template (documented on
            // [recurrenceSummary] as the one such key) rather than a middot typed here.
            if (day == null) relative else stringResource(Res.string.tasks_cadence_with_bound, day, relative)
        }
    }
    PropertyValueText(text)
}

/**
 * The TODAY cell — the ADR-0053 decision 4 honesty contract, and the one place on this screen where getting
 * the wording wrong would state a fact the device does not have.
 *
 * Two orthogonal questions, never conflated: **does anything fire today** ([TodayOccurrence.firing], from the
 * offline expander) and **how did today go** ([TodayOccurrence.state], resolved over the stored fact, this
 * device's coverage and the light switch). The backend says the same of its own field — `today_occurrence`
 * *"always describes today's date for this item; it does not mean the item is scheduled to fire today."*
 *
 * - [DayFiring.NotFiring] — the grid **was** reproduced and puts nothing on today. Plain text, no chip: there
 *   is no occurrence to have a state.
 * - A **cancelled** firing — the slot existed and was called off, which is a different statement from the
 *   rule never having fired, so it must not read as "not scheduled".
 * - [DayFiring.Unavailable] with nothing else known — this device cannot reproduce the grid (a `Custom` rule,
 *   a cadence this build cannot model, an unresolvable anchor, or a backend-**elided** series block).
 *   Rendering "Not scheduled today" here is the exact lie this slice exists to prevent: absent inputs are not
 *   an empty schedule.
 * - Otherwise the [OccurrenceState] chip, which includes the [OccurrenceState.Unknown] "Not synced" reading —
 *   an unreproducible grid whose day nonetheless has a stored fact (a completed firing, say) should report
 *   that fact rather than shrug.
 */
@Composable
private fun TodayCell(today: TodayOccurrence) {
    val firing = today.firing
    val plain = when {
        firing is DayFiring.NotFiring -> stringResource(Res.string.tasks_detail_today_not_firing)
        firing is DayFiring.Fires && firing.firing.isCancelled -> stringResource(Res.string.tasks_detail_today_cancelled)
        firing is DayFiring.Unavailable && today.state == OccurrenceState.Unknown ->
            stringResource(Res.string.tasks_detail_today_unavailable)
        else -> null
    }
    if (plain != null) {
        // Muted, and NOT chip-shaped: these three arms are statements about the schedule (or about this
        // device), not a status badge, and dressing them as one would make an absence look like a state.
        val a11y = stringResource(Res.string.tasks_detail_today_row_a11y, plain)
        Text(
            text = plain,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.defernoColors.inkMuted,
            modifier = Modifier.clearAndSetSemantics { contentDescription = a11y },
        )
    } else {
        // The row's own "Today: …" wrapper, not the chip's default "Status: …" — this row is already labelled
        // TODAY, and it is read-only, so the announcement must not end in "Tap to change".
        val label = occurrenceStateChipStyle(today.state).label
        OccurrenceStateChip(
            occurrence = today.state,
            semanticLabel = stringResource(Res.string.tasks_detail_today_row_a11y, label),
        )
    }
}

/** The labels as read-only pills, or a muted em dash when there are none — the Task LABELS cell minus editing. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReadOnlyLabelsCell(labels: List<String>) {
    if (labels.isEmpty()) {
        PropertyValueText(null)
        return
    }
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        labels.forEach { LabelChip(it) }
    }
}

/**
 * The plain noun for each [DefinitionState] — the light switch's reading, deliberately not a [[Working
 * state]] word. [DefinitionState.InReview] has no key of its own: it reads the shared
 * `common_status_in_review`, since "in review" means the same thing on both axes.
 */
@Composable
internal fun definitionStateLabel(state: DefinitionState): String = stringResource(
    when (state) {
        DefinitionState.Active -> Res.string.tasks_definition_state_active
        DefinitionState.InReview -> Res.string.common_status_in_review
        DefinitionState.Archived -> Res.string.tasks_definition_state_archived
    },
)
