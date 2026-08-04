package com.circuitstitch.deferno.core.designsystem.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.circuitstitch.deferno.core.designsystem.resources.Res
import com.circuitstitch.deferno.core.designsystem.resources.tasks_kind_a11y_chore
import com.circuitstitch.deferno.core.designsystem.resources.tasks_kind_a11y_event
import com.circuitstitch.deferno.core.designsystem.resources.tasks_kind_a11y_habit
import com.circuitstitch.deferno.core.designsystem.resources.tasks_kind_a11y_task
import com.circuitstitch.deferno.core.designsystem.resources.tasks_kind_label_chore
import com.circuitstitch.deferno.core.designsystem.resources.tasks_kind_label_event
import com.circuitstitch.deferno.core.designsystem.resources.tasks_kind_label_habit
import com.circuitstitch.deferno.core.designsystem.resources.tasks_kind_label_task
import com.circuitstitch.deferno.core.designsystem.theme.defernoColors
import com.circuitstitch.deferno.core.model.ItemKind
import org.jetbrains.compose.resources.stringResource

/**
 * How an [ItemKind] presents itself — its colour, its chip label and its spoken name.
 *
 * These lived in `feature/tasks/ui`'s tree atoms while the Item tree was the only surface that
 * rendered all four kinds. The daily Plan became the second one (#385), and a feature slice may not
 * depend on another feature slice — so rather than duplicate a kind vocabulary that must stay
 * identical everywhere, it moves down to the design system, which is where a cross-slice visual
 * vocabulary belongs (ADR-0004). The string resources were already shared from this module; only the
 * three composables reading them were in the wrong place.
 */

/** The four equal Item kinds (ADR-0049) each carry a calm colour; reinforcement, never the sole signal. */
@Composable
fun kindColor(kind: ItemKind): Color = when (kind) {
    ItemKind.Task -> MaterialTheme.colorScheme.primary
    ItemKind.Habit -> MaterialTheme.defernoColors.success
    ItemKind.Event -> MaterialTheme.colorScheme.secondary
    ItemKind.Chore -> MaterialTheme.colorScheme.tertiary
}

/** The plain, upper-case label for a kind, e.g. "TASK" — used as a TreeChip / plan-row marker. */
@Composable
fun kindLabel(kind: ItemKind): String = stringResource(
    when (kind) {
        ItemKind.Task -> Res.string.tasks_kind_label_task
        ItemKind.Habit -> Res.string.tasks_kind_label_habit
        ItemKind.Event -> Res.string.tasks_kind_label_event
        ItemKind.Chore -> Res.string.tasks_kind_label_chore
    },
)

/** The lowercase kind name for a KindDot's TalkBack label, e.g. "task" (search result rows). */
@Composable
fun kindA11yLabel(kind: ItemKind): String = stringResource(
    when (kind) {
        ItemKind.Task -> Res.string.tasks_kind_a11y_task
        ItemKind.Habit -> Res.string.tasks_kind_a11y_habit
        ItemKind.Event -> Res.string.tasks_kind_a11y_event
        ItemKind.Chore -> Res.string.tasks_kind_a11y_chore
    },
)
