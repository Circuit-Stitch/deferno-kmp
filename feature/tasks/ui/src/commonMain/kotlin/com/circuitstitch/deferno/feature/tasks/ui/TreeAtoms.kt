package com.circuitstitch.deferno.feature.tasks.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.circuitstitch.deferno.core.designsystem.theme.defernoColors
import com.circuitstitch.deferno.core.model.ItemKind

// Tasks-specific design helpers for the "See the trees" restyle. The reusable atoms (SectionLabel,
// TreeChip, KindDot, ProgressBarThin, CheckDot, SearchBarDisplay, SegmentedFilter, DashedAddButton,
// MonoMeta, Eyebrow, DefernoIcons, …) now live in the shared design system —
// com.circuitstitch.deferno.core.designsystem.component — so the Tasks Views import them from there.
// Only the kind→colour/label mapping is feature-local (it knows the Item kinds), so it stays here.

/** The four equal Item kinds (ADR-0034) each carry a calm colour; reinforcement, never the sole signal. */
@Composable
internal fun kindColor(kind: ItemKind): Color = when (kind) {
    ItemKind.Task -> MaterialTheme.colorScheme.primary
    ItemKind.Habit -> MaterialTheme.defernoColors.success
    ItemKind.Event -> MaterialTheme.colorScheme.secondary
    ItemKind.Chore -> MaterialTheme.colorScheme.tertiary
}

/** The plain, upper-case label for a kind, e.g. "TASK" — used as a TreeChip marker. */
internal fun kindLabel(kind: ItemKind): String = kind.name.uppercase()
