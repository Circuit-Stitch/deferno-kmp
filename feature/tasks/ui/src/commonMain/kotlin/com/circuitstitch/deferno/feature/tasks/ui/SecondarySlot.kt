package com.circuitstitch.deferno.feature.tasks.ui

/**
 * Which slot fills the **secondary** pane of the Tasks Destination, or [None] when nothing is open. Since
 * ADR-0034 the Item tree is the *primary* pane (the one-level drill pane is subsumed), so the secondary is
 * only ever a Task [Detail] or empty. The single source of the secondary-pane choice shared by the Android
 * `TasksScreen` and the desktop `TasksDesktopScreen`; each maps it to its own leaf composables and its own
 * [None] fallback (a "pick a task" empty state in two panes, the tree in the single-pane fold).
 */
internal enum class SecondarySlot { Detail, None }

/**
 * Resolves the secondary pane's slot: the Task [SecondarySlot.Detail] when a detail is open, else
 * [SecondarySlot.None]. Trivial now that detail is the lone secondary slot — kept as the single shared
 * helper (Android + desktop) so both platforms foreground the same slot, and unit-tested on the JVM fast
 * path (ADR-0006) unlike the `@Composable` renderers that consume it.
 */
internal fun resolveSecondarySlot(hasDetail: Boolean): SecondarySlot =
    if (hasDetail) SecondarySlot.Detail else SecondarySlot.None
