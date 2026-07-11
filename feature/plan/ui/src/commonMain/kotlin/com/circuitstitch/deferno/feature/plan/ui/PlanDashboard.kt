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
import com.circuitstitch.deferno.core.designsystem.format.currentToday
import com.circuitstitch.deferno.core.designsystem.resources.Res
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
import com.circuitstitch.deferno.core.designsystem.resources.plan_why_pinned
import com.circuitstitch.deferno.core.designsystem.resources.plan_why_quick_win
import com.circuitstitch.deferno.core.designsystem.resources.plan_your_day_section_caps
import com.circuitstitch.deferno.core.designsystem.theme.defernoColors
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
 * [PlanComponent]: observes today's ordered Tasks and forwards taps (open the Task).
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
            tasks = state.tasks,
            isRefreshing = state.isRefreshing,
            onTaskClick = component::onTaskClicked,
            today = today,
            onStartFocus = { mode = PlanMode.Focus(it) },
            onWhatsNext = { mode = PlanMode.WhatsNext },
            modifier = modifier,
        )

        PlanMode.WhatsNext -> WhatsNextContent(
            tasks = state.tasks,
            onBack = { mode = PlanMode.Today },
            onStartFocus = { mode = PlanMode.Focus(it) },
            modifier = modifier,
        )

        is PlanMode.Focus -> {
            val task = state.tasks.firstOrNull { it.id == m.taskId }
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

/** The task we gently suggest starting with: the first pinned one, else the first in the plan. */
private fun List<Task>.suggested(): Task? = firstOrNull { it.pinned } ?: firstOrNull()

// ───────────────────────────────────────────────────────────────────────────────────────────────
// 1. "Today" — the hero
// ───────────────────────────────────────────────────────────────────────────────────────────────

/** Stateless Today body — rendered directly by screenshot/UI tests with fixed inputs. */
@Composable
internal fun PlanContent(
    tasks: List<Task>,
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
    val suggested = tasks.suggested()

    Column(modifier = modifier.fillMaxSize().background(scheme.surface)) {
        if (isRefreshing) {
            LoadingStrip(label = stringResource(Res.string.plan_refreshing))
        }
        if (tasks.isEmpty() && !isRefreshing) {
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
                        text = pluralStringResource(Res.plurals.plan_today_subtitle, tasks.size, tasks.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }

            // Suggestion banner.
            if (suggested != null) {
                item(key = "banner") {
                    SuggestionBanner(
                        task = suggested,
                        onStart = { onStartFocus(suggested.id) },
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
            itemsIndexed(tasks) { index, task ->
                val isSuggested = task.id == suggested?.id
                DayRow(
                    task = task,
                    highlighted = isSuggested,
                    onClick = { onTaskClick(task.id) },
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
                        text = attentionLabel(tasks, today),
                        style = MaterialTheme.typography.bodySmall,
                        color = brand.inkMuted,
                    )
                }
            }
        }
    }
}

/** "Nothing's overdue" or "{n} need attention" — gentle, never alarming. */
@Composable
private fun attentionLabel(tasks: List<Task>, today: LocalDate): String {
    val nowStart = today.atStartOfDayInstant()
    val overdue = tasks.count { t -> t.completeBy?.let { it < nowStart } == true && !t.workingState.isTerminal }
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
 * ponytail: the CheckDot toggles a LOCAL remembered state only — PlanComponent has no completion
 * intent. Real persistence needs a new `PlanComponent.onToggleDone(id)` intent + a repository write
 * (follow-up). For now the tick is an optimistic visual that resets on recompose-from-scratch.
 */
@Composable
private fun DayRow(task: Task, highlighted: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val brand = MaterialTheme.defernoColors
    // ponytail: optimistic local "done" — no onToggleDone intent yet (see KDoc).
    var done by remember(task.id) { mutableStateOf(task.workingState.isTerminal) }

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
            .clickable(onClickLabel = stringResource(Res.string.common_open_named_cd, task.title), onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CheckDot(
            checked = done,
            onCheckedChange = { done = it },
            contentDescription = if (done) {
                stringResource(Res.string.common_mark_not_done_cd, task.title)
            } else {
                stringResource(Res.string.common_mark_done_cd, task.title)
            },
        )
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
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.titleMedium,
                    // A blocked row mutes like a done one but WITHOUT the strike — "blocked, not finished"
                    // (mirrors the tree's ItemTreeRow, #290/#292). Manually-added blocked items stay on the plan.
                    color = if (done || task.blocked) brand.inkMuted else scheme.onSurface,
                    textDecoration = if (done) TextDecoration.LineThrough else null,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (task.blocked) {
                    Spacer(Modifier.width(6.dp))
                    BlockedChip()
                }
            }
            Text(
                text = task.deadlineLabel(),
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
    val suggested = remember(tasks) { tasks.suggested() }
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
