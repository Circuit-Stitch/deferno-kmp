package com.circuitstitch.deferno.feature.plan.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.circuitstitch.deferno.core.designsystem.component.BlockedChip
import com.circuitstitch.deferno.core.designsystem.component.CheckDot
import com.circuitstitch.deferno.core.designsystem.component.DashedAddButton
import com.circuitstitch.deferno.core.designsystem.component.DefernoIcons
import com.circuitstitch.deferno.core.designsystem.component.Eyebrow
import com.circuitstitch.deferno.core.designsystem.component.KindDot
import com.circuitstitch.deferno.core.designsystem.component.MonoMeta
import com.circuitstitch.deferno.core.designsystem.component.PrimaryActionButton
import com.circuitstitch.deferno.core.designsystem.component.SectionLabel
import com.circuitstitch.deferno.core.designsystem.component.StartPill
import com.circuitstitch.deferno.core.designsystem.component.TextLink
import com.circuitstitch.deferno.core.designsystem.component.TreeChip
import com.circuitstitch.deferno.core.designsystem.component.kindA11yLabel
import com.circuitstitch.deferno.core.designsystem.component.kindColor
import com.circuitstitch.deferno.core.designsystem.component.kindLabel
import com.circuitstitch.deferno.core.designsystem.format.currentToday
import com.circuitstitch.deferno.core.designsystem.resources.Res
import com.circuitstitch.deferno.core.designsystem.resources.common_a11y_phrase_join
import com.circuitstitch.deferno.core.designsystem.resources.common_due
import com.circuitstitch.deferno.core.designsystem.resources.common_mark_done_cd
import com.circuitstitch.deferno.core.designsystem.resources.common_mark_not_done_cd
import com.circuitstitch.deferno.core.designsystem.resources.common_open_named_cd
import com.circuitstitch.deferno.core.designsystem.resources.common_start
import com.circuitstitch.deferno.core.designsystem.resources.plan_add_from_forest
import com.circuitstitch.deferno.core.designsystem.resources.plan_back_to_today
import com.circuitstitch.deferno.core.designsystem.resources.plan_choose_task_click_label
import com.circuitstitch.deferno.core.designsystem.resources.plan_exit_focus
import com.circuitstitch.deferno.core.designsystem.resources.plan_focus_done
import com.circuitstitch.deferno.core.designsystem.resources.plan_focus_pause
import com.circuitstitch.deferno.core.designsystem.resources.plan_focus_subtitle
import com.circuitstitch.deferno.core.designsystem.resources.plan_need_attention
import com.circuitstitch.deferno.core.designsystem.resources.plan_nothing_overdue
import com.circuitstitch.deferno.core.designsystem.resources.plan_pick_for_me
import com.circuitstitch.deferno.core.designsystem.resources.plan_rather_not_decide
import com.circuitstitch.deferno.core.designsystem.resources.plan_refreshing
import com.circuitstitch.deferno.core.designsystem.resources.plan_see_everything
import com.circuitstitch.deferno.core.designsystem.resources.plan_start_with_title
import com.circuitstitch.deferno.core.designsystem.resources.plan_suggested_chip
import com.circuitstitch.deferno.core.designsystem.resources.plan_suggestion_eyebrow_caps
import com.circuitstitch.deferno.core.designsystem.resources.plan_today_subtitle
import com.circuitstitch.deferno.core.designsystem.resources.plan_today_title
import com.circuitstitch.deferno.core.designsystem.resources.plan_whats_next_subtitle
import com.circuitstitch.deferno.core.designsystem.resources.plan_whats_next_title
import com.circuitstitch.deferno.core.designsystem.resources.plan_why_fire
import com.circuitstitch.deferno.core.designsystem.resources.plan_why_pinned
import com.circuitstitch.deferno.core.designsystem.resources.plan_why_quick_win
import com.circuitstitch.deferno.core.designsystem.resources.plan_your_day_section_caps
import com.circuitstitch.deferno.core.designsystem.theme.defernoColors
import com.circuitstitch.deferno.core.model.PlanRow
import com.circuitstitch.deferno.core.model.Priority
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.feature.plan.PlanComponent
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The daily Plan pane (#27) restyled to the "See the trees" direction — the app's calm home
 * (design-principles.md: "open into today's Plan, not the whole backlog"). A thin renderer of
 * [PlanComponent]: observes today's ordered rows — items of any kind since #385 — and forwards taps
 * (open the Task; a recurring row has no detail surface to open yet, #383).
 *
 * On top of that it hosts three **local** sub-screens with no shell ripple (no Decompose, no
 * navigation): Today → What's next? (a decision helper) → Focus (a single-task surface). Mode lives
 * in plain Compose state here; Start/exit just flip it.
 *
 * Shared between the Android shell and the desktop shell (ADR-0004 #27): the body uses only
 * cross-platform Compose + the shared designsystem atoms, so one dashboard serves both platforms — the
 * desktop wrapper ([PlanDesktopScreen]) only centres it at a reading width.
 */
@Composable
fun PlanScreen(component: PlanComponent, modifier: Modifier = Modifier) {
    val state by component.state.collectAsState()
    // "Today" for the header/greeting — read through the shared seam so screenshot tests can pin a
    // fixed date (production falls back to the live system clock). See [currentToday].
    val today = currentToday

    var mode by remember { mutableStateOf<PlanMode>(PlanMode.Today) }

    when (val m = mode) {
        PlanMode.Today -> PlanContent(
            rows = state.rows,
            isRefreshing = state.isRefreshing,
            onTaskClick = component::onTaskClicked,
            today = today,
            onStartFocus = { mode = PlanMode.Focus(it) },
            onWhatsNext = { mode = PlanMode.WhatsNext },
            modifier = modifier,
        )

        // What's next? and Focus are Task verbs — "start this", "work on it until done". A recurring
        // commitment has no such verb, so both take the Task projection of the plan rather than every row.
        PlanMode.WhatsNext -> WhatsNextContent(
            tasks = state.rows.mapNotNull { it.task },
            onBack = { mode = PlanMode.Today },
            onStartFocus = { mode = PlanMode.Focus(it) },
            modifier = modifier,
        )

        is PlanMode.Focus -> {
            val task = state.rows.firstNotNullOfOrNull { row -> row.task?.takeIf { it.id == m.taskId } }
            if (task == null) {
                // The task vanished (refresh dropped it); fall back to Today rather than a blank screen.
                mode = PlanMode.Today
            } else {
                FocusContent(
                    task = task,
                    onDone = { mode = PlanMode.Today },
                    onExit = { mode = PlanMode.Today },
                    modifier = modifier,
                )
            }
        }
    }
}

/** Which local Plan sub-screen is showing. Plain Compose state — no Decompose (zero shell ripple). */
internal sealed interface PlanMode {
    data object Today : PlanMode
    data object WhatsNext : PlanMode
    data class Focus(val taskId: TaskId) : PlanMode
}

/**
 * The task we gently suggest starting with: the first [Priority.Fire] one, else the first pinned one, else
 * the first in the plan. Fire outranks pinned (#375) — the person marked it urgent, which is a stronger
 * "start here" signal than having parked it at the top.
 *
 * This picks; it does **not** sort. The Plan's order is the one the person arranged, and stays exactly as
 * they left it — only the ✦ suggestion (and its "why" line) moves.
 *
 * `internal` (not private) so the precedence is unit-testable as the pure function it is.
 */
internal fun List<Task>.suggestedTask(): Task? =
    firstOrNull { it.priority == Priority.Fire } ?: firstOrNull { it.pinned } ?: firstOrNull()

internal fun List<PlanRow>.suggested(): PlanRow? {
    // Task rows only. The banner's verb is "Start", and starting is what you do to a Task — a Habit is a
    // commitment you keep, not work you pick up, and tapping through leads to Focus mode, which is
    // Task-shaped end to end. A plan of nothing but recurring rows therefore gets no ✦, which is the
    // honest outcome rather than a suggestion nobody can act on (#385).
    val taskRows = filter { it.task != null }
    val pick = taskRows.mapNotNull { it.task }.suggestedTask() ?: return null
    return taskRows.first { it.task!!.id == pick.id }
}

// ───────────────────────────────────────────────────────────────────────────────────────────────
// 1. "Today" — the hero
// ───────────────────────────────────────────────────────────────────────────────────────────────

/** Stateless Today body — rendered directly by screenshot/UI tests with fixed inputs. */
@Composable
internal fun PlanContent(
    rows: List<PlanRow>,
    isRefreshing: Boolean,
    onTaskClick: (TaskId) -> Unit,
    today: LocalDate,
    onStartFocus: (TaskId) -> Unit,
    onWhatsNext: () -> Unit,
    modifier: Modifier = Modifier,
    onAddFromForest: () -> Unit = {},
    onSeeEverything: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    val brand = MaterialTheme.defernoColors
    val suggested = rows.suggested()

    Column(modifier = modifier.fillMaxSize().background(scheme.surface)) {
        if (isRefreshing) {
            LoadingStrip(label = stringResource(Res.string.plan_refreshing))
        }
        if (rows.isEmpty() && !isRefreshing) {
            EmptyPlan()
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            // Edge-to-edge (ADR-0035 #2): pad the last row clear of the system nav bar (empty on desktop).
            contentPadding = WindowInsets.systemBars.only(WindowInsetsSides.Bottom).asPaddingValues(),
        ) {
            // Header: title + date + gentle subtitle.
            item(key = "header") {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(Res.string.plan_today_title),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.semantics { heading() },
                        )
                        MonoMeta(text = formatHeaderDate(today))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = pluralStringResource(Res.plurals.plan_today_subtitle, rows.size, rows.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }

            // Suggestion banner.
            if (suggested != null) {
                item(key = "banner") {
                    SuggestionBanner(
                        task = suggested.task!!,
                        onStart = { onStartFocus(suggested.task!!.id) },
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                    Spacer(Modifier.height(20.dp))
                }
            }

            item(key = "section") {
                SectionLabel(
                    text = stringResource(Res.string.plan_your_day_section_caps),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }

            // The day list. The suggested row is a highlighted card; the rest are flat.
            itemsIndexed(rows) { index, row ->
                val isSuggested = row.item.id == suggested?.item?.id
                DayRow(
                    row = row,
                    highlighted = isSuggested,
                    // Only a Task opens: no recurring kind has a detail surface on any platform yet
                    // (#383), so a recurring row carries no tap rather than one that goes nowhere.
                    onClick = row.task?.let { task -> { onTaskClick(task.id) } },
                )
                if (!isSuggested) {
                    HorizontalDivider(
                        color = scheme.outlineVariant,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
            }

            item(key = "add") {
                DashedAddButton(
                    text = stringResource(Res.string.plan_add_from_forest),
                    onClick = onAddFromForest,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                )
            }

            item(key = "footer") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextLink(
                        text = stringResource(Res.string.plan_see_everything),
                        onClick = onSeeEverything,
                        trailingChevron = true,
                    )
                    Text(
                        text = attentionLabel(rows, today),
                        style = MaterialTheme.typography.bodySmall,
                        color = brand.inkMuted,
                    )
                }
            }
        }
    }
}

/**
 * "Nothing's overdue" or "{n} need attention" — gentle, never alarming.
 *
 * Counts Task rows only. Deciding that a recurring firing is unresolved needs the occurrence-fact table
 * (#390) and the derivation in Half B of #385; until then a recurring row contributes zero rather than
 * being guessed at in either direction.
 */
@Composable
private fun attentionLabel(rows: List<PlanRow>, today: LocalDate): String {
    val nowStart = today.atStartOfDayInstant()
    val overdue = rows.count { row ->
        val t = row.task ?: return@count false
        t.completeBy?.let { it < nowStart } == true && !t.workingState.isTerminal
    }
    return if (overdue == 0) {
        stringResource(Res.string.plan_nothing_overdue)
    } else {
        pluralStringResource(Res.plurals.plan_need_attention, overdue, overdue)
    }
}

private fun LocalDate.atStartOfDayInstant() =
    atStartOfDayIn(TimeZone.currentSystemDefault())

@Composable
private fun SuggestionBanner(task: Task, onStart: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(scheme.surfaceContainerLow)
            .border(1.dp, scheme.primaryContainer, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(DefernoIcons.Sparkle, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Eyebrow(text = stringResource(Res.string.plan_suggestion_eyebrow_caps))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = task.title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(12.dp))
        StartPill(text = stringResource(Res.string.common_start), onClick = onStart)
    }
}

/**
 * A single day row. The suggested one is a highlighted [surfaceContainerLow] card with a ✦ before the
 * title; the rest are flat (the caller draws the dividers).
 *
 * **A row is one of four kinds (#385).** A Task row keeps everything it had — the completion dot, the
 * deadline subline, the open-on-tap. A Habit/Chore/Event row renders its title and a kind marker, and
 * deliberately carries neither:
 *
 * - **No completion control.** A firing's done-state is a *reading* against today, not a stored fact
 *   (ADR-0053), and the fact table it will be derived from does not exist yet (#390). An unchecked dot
 *   would assert "not done" on no evidence. The dot this row used to draw for Tasks was worse than
 *   that — it toggled a `remember`ed local boolean with nothing behind it, a tick that silently reset
 *   on recompose. Both Apple Plan views already refuse to fake it ("No completion intent yet — opening
 *   the task is the honest action"), so Compose now matches them instead of being the odd one out.
 * - **No tap.** No recurring kind has a detail surface on any platform (#383), so [onClick] is null for
 *   those rows and the clickable — and its "Open …" a11y action — is not attached at all. A row that
 *   cannot be opened should not announce that it can.
 */
@Composable
private fun DayRow(row: PlanRow, highlighted: Boolean, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val brand = MaterialTheme.defernoColors
    val item = row.item
    val task = row.task
    val done = task?.workingState?.isTerminal == true

    val rowModifier = if (highlighted) {
        modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.surfaceContainerLow)
    } else {
        modifier.fillMaxWidth().padding(horizontal = 20.dp)
    }

    Row(
        modifier = rowModifier
            .heightIn(min = 64.dp)
            .let { m ->
                if (onClick == null) {
                    m
                } else {
                    m.clickable(
                        onClickLabel = stringResource(Res.string.common_open_named_cd, item.title),
                        onClick = onClick,
                    )
                }
            }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (task != null) {
            CheckDot(
                checked = done,
                // Opening is the honest action while there is no completion intent on PlanComponent —
                // the same contract both SwiftUI Plan views already state.
                onCheckedChange = { onClick?.invoke() },
                contentDescription = stringResource(Res.string.common_open_named_cd, item.title),
            )
        } else {
            // Centred in the CheckDot's own 24.dp footprint so the two row shapes share a title column —
            // a bare 10.dp KindDot would leave a recurring row's title hanging 7.dp to the left of every
            // Task's, which reads as a nesting level that isn't there.
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                KindDot(color = kindColor(item.kind))
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (highlighted) {
                    Icon(
                        DefernoIcons.Sparkle,
                        contentDescription = null,
                        tint = scheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                // The kind rides the title's spoken label rather than the dot's: the dot is decorative
                // reinforcement, and a screen-reader user needs "Take a Walk, habit" as one phrase. Joined
                // through the catalog key, never a literal ", " — separator and order belong to the
                // translator (mirrors the Item tree, #385).
                val titleA11y = if (task == null) {
                    stringResource(Res.string.common_a11y_phrase_join, item.title, kindA11yLabel(item.kind))
                } else {
                    item.title
                }
                Text(
                    text = item.title,
                    modifier = Modifier.weight(1f, fill = false).semantics { contentDescription = titleA11y },
                    style = MaterialTheme.typography.titleMedium,
                    // A blocked row mutes like a done one but WITHOUT the strike — "blocked, not finished"
                    // (mirrors the tree's ItemTreeRow, #290/#292). Manually-added blocked items stay on the plan.
                    color = if (done || item.blocked) brand.inkMuted else scheme.onSurface,
                    textDecoration = if (done) TextDecoration.LineThrough else null,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.blocked) {
                    Spacer(Modifier.width(6.dp))
                    BlockedChip()
                }
            }
            Text(
                // A Task's subline is its deadline; a recurring row's is its kind, which is the fact that
                // makes it legible as something other than a Task. The cadence phrase ("every Tuesday")
                // wants the recurrence reading and lands with the rest of the row work in #383/Half B.
                text = task?.deadlineLabel() ?: kindLabel(item.kind),
                style = MaterialTheme.typography.bodySmall,
                color = brand.inkMuted,
            )
        }
    }
}

// ───────────────────────────────────────────────────────────────────────────────────────────────
// 2. "What's next?" — a decision helper
// ───────────────────────────────────────────────────────────────────────────────────────────────

@Composable
internal fun WhatsNextContent(
    tasks: List<Task>,
    onBack: () -> Unit,
    onStartFocus: (TaskId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val choices = remember(tasks) { tasks.take(3) }
    // Pick WITHIN the three rendered cards, not across the whole plan: `selected` is resolved against
    // `choices`, so a suggestion from further down the list resolves to null and the screen opens with
    // nothing selected, no ✦ chip and a dead primary button. (Latent for `pinned` already; widening the
    // pick to Fire — a marker someone may well set on something below the fold — makes it reachable.)
    // Picking inside the rendered set also keeps this a pick rather than a reorder: the three cards are
    // still the plan's first three, in the order the person arranged them.
    val suggested = remember(choices) { choices.suggestedTask() }
    var selectedId by remember(tasks) { mutableStateOf(suggested?.id) }
    val selected = choices.firstOrNull { it.id == selectedId }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(scheme.surface)
            .padding(WindowInsets.systemBars.only(WindowInsetsSides.Bottom).asPaddingValues())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        TextLink(text = stringResource(Res.string.plan_back_to_today), onClick = onBack)
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(Res.string.plan_whats_next_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(Res.string.plan_whats_next_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))

        choices.forEach { task ->
            ChoiceCard(
                task = task,
                selected = task.id == selectedId,
                isSuggested = task.id == suggested?.id,
                onSelect = { selectedId = task.id },
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            )
        }

        Spacer(Modifier.weight(1f))

        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.plan_rather_not_decide),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.defernoColors.inkMuted,
            )
            Spacer(Modifier.width(4.dp))
            TextLink(text = stringResource(Res.string.plan_pick_for_me), onClick = { selectedId = suggested?.id })
        }

        PrimaryActionButton(
            text = selected?.let { stringResource(Res.string.plan_start_with_title, it.title) }
                ?: stringResource(Res.string.common_start),
            onClick = { selected?.let { onStartFocus(it.id) } },
            enabled = selected != null,
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        )
    }
}

/** A selectable "what's next" choice card with a radio dot, a "why" line, a hint, and a check. */
@Composable
internal fun ChoiceCard(
    task: Task,
    selected: Boolean,
    isSuggested: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val border = if (selected) scheme.primary else scheme.outlineVariant

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) scheme.surfaceContainerLow else scheme.surface)
            .border(if (selected) 2.dp else 1.dp, border, RoundedCornerShape(14.dp))
            .heightIn(min = MinTouchTarget)
            .clickable(
                onClickLabel = stringResource(Res.string.plan_choose_task_click_label, task.title),
                role = Role.RadioButton,
                onClick = onSelect,
            )
            .padding(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Radio selection dot.
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .border(2.dp, if (selected) scheme.primary else scheme.outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) KindDot(color = scheme.primary, size = 10.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            if (isSuggested) {
                val suggestedLabel = stringResource(Res.string.plan_suggested_chip)
                TreeChip(text = suggestedLabel, leadingIcon = DefernoIcons.Sparkle, semanticLabel = suggestedLabel)
                Spacer(Modifier.height(6.dp))
            }
            Text(
                text = task.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = whyLine(task),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            MonoMeta(text = task.deadlineLabel())
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier.size(24.dp).clip(CircleShape).background(scheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(DefernoIcons.Check, contentDescription = null, tint = scheme.onPrimary, modifier = Modifier.size(15.dp))
            }
        }
    }
}

/** The derived "why" line for a choice. */
@Composable
private fun whyLine(task: Task): String = when {
    task.completeBy != null -> stringResource(
        Res.string.common_due,
        formatDeadlineDate(task.completeBy!!, TimeZone.currentSystemDefault()),
    )
    // The urgency bucket the person set themselves (#375) outranks "you pinned it" — it is the more
    // deliberate signal of the two. Undated, so it sits below the hard deadline reading above.
    task.priority == Priority.Fire -> stringResource(Res.string.plan_why_fire)
    task.pinned -> stringResource(Res.string.plan_why_pinned)
    else -> stringResource(Res.string.plan_why_quick_win)
}

// ───────────────────────────────────────────────────────────────────────────────────────────────
// 3. "Focus" — a single-task surface
// ───────────────────────────────────────────────────────────────────────────────────────────────

/**
 * The Focus surface: one task, everything else put away. Gold accent = [primary] (adapts to the dark
 * theme, where it reads as the design's gold). A breathing ring around a clock honours reduced-motion
 * via [reducedMotion] (static when true).
 *
 * ponytail: the design's dimmed step checklist is omitted — the flat PlanState carries no subtask
 * titles, so there's nothing to render. A future tree-aware Focus would list the task's children.
 */
@Composable
internal fun FocusContent(
    task: Task,
    onDone: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    reducedMotion: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val brand = MaterialTheme.defernoColors

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(scheme.surface)
            .padding(WindowInsets.systemBars.only(WindowInsetsSides.Bottom).asPaddingValues())
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Chevron-down feel: exit collapses Focus back to Today.
            TextLink(text = stringResource(Res.string.plan_exit_focus), onClick = onExit)
            // ponytail: no derivable step counter on the flat PlanState — omit the right-hand meta.
        }

        Spacer(Modifier.weight(1f))

        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            FocusRing(reducedMotion = reducedMotion)
        }

        Spacer(Modifier.height(28.dp))

        Text(
            text = task.title,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(Res.string.plan_focus_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = brand.inkMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.weight(1f))

        PrimaryActionButton(
            text = stringResource(Res.string.plan_focus_done),
            icon = DefernoIcons.Check,
            onClick = onDone,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        TextLink(
            text = stringResource(Res.string.plan_focus_pause),
            onClick = onExit,
            color = brand.inkMuted,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        Spacer(Modifier.height(8.dp))
    }
}

/** The breathing focus ring around a clock. Static when [reducedMotion] (design: honour reduced-motion). */
@Composable
private fun FocusRing(reducedMotion: Boolean, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val scale: Float
    val ringAlpha: Float
    if (reducedMotion) {
        scale = 1f
        ringAlpha = 0.5f
    } else {
        val transition = rememberInfiniteTransition(label = "focus-breathe")
        scale = transition.animateFloat(
            initialValue = 0.94f,
            targetValue = 1.06f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2600),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "focus-scale",
        ).value
        ringAlpha = transition.animateFloat(
            initialValue = 0.30f,
            targetValue = 0.65f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2600),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "focus-alpha",
        ).value
    }

    Box(contentAlignment = Alignment.Center, modifier = modifier) {
        // Outer breathing ring.
        Box(
            modifier = Modifier
                .size(180.dp)
                .scale(scale)
                .alpha(ringAlpha)
                .clip(CircleShape)
                .border(3.dp, scheme.primary, CircleShape),
        )
        // Inner static ring + clock glyph.
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .border(2.dp, scheme.primary.copy(alpha = 0.5f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                DefernoIcons.Clock,
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier.size(34.dp),
            )
        }
    }
}
