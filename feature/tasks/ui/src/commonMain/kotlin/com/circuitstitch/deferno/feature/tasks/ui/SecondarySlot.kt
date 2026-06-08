package com.circuitstitch.deferno.feature.tasks.ui

import com.circuitstitch.deferno.feature.tasks.TaskPane

/**
 * Which co-resident slot fills the secondary pane of the Tasks Destination (ADR-0007 tier-2), or
 * [None] when nothing is open. The single source of the secondary-pane precedence shared by the
 * Android `TasksScreen` and the desktop `TasksDesktopScreen`; each maps these to its own leaf
 * composables and its own [None] fallback (a "pick a task" empty state in two panes, the list in
 * the desktop single-pane fold).
 */
internal enum class SecondarySlot { Detail, Tree, None }

/**
 * Resolves the secondary pane's slot: the most-recently-foregrounded slot ([activePane]) when that
 * slot is actually open, else whichever slot remains, else [SecondarySlot.None]. Because detail and
 * tree are co-resident (both can be open at once), recency only wins when its own slot is present —
 * so a tree→child drill-in keeps the tree foregrounded rather than snapping to the detail.
 *
 * Pure and platform-neutral so it is unit-tested on the JVM fast path (ADR-0006), unlike the
 * `@Composable` renderers that consume it (#67).
 */
internal fun resolveSecondarySlot(activePane: TaskPane, hasDetail: Boolean, hasTree: Boolean): SecondarySlot =
    when {
        activePane == TaskPane.Tree && hasTree -> SecondarySlot.Tree
        activePane == TaskPane.Detail && hasDetail -> SecondarySlot.Detail
        hasTree -> SecondarySlot.Tree
        hasDetail -> SecondarySlot.Detail
        else -> SecondarySlot.None
    }
