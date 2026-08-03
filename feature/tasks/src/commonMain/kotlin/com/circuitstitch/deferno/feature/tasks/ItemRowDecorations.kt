package com.circuitstitch.deferno.feature.tasks

/**
 * The per-row decorations the Item tree needs but the cross-kind [com.circuitstitch.deferno.core.model.Item]
 * projection it renders doesn't carry — joined **once** by the shell (the only layer where `today`, the
 * time zone, and the Task + plan + calendar reads all live) and threaded down as a single flow.
 *
 * They travel together because they are decorations of the same row, computed from overlapping sources at
 * the same place. They are deliberately **not the same shape**, and collapsing them into one map would be
 * a bug, not a simplification:
 *  - [menuStates] is **Task-only by construction** — the Pin / plan / status writes it labels are Task-only
 *    (the native write layer is Task-centric), so a Habit/Chore/Event row simply has no entry and the menu
 *    falls back to its cross-kind subset.
 *  - [inTodayIds] is deliberately **kind-neutral and wider** — a Habit/Chore/Event is in today exactly when
 *    it fires today. Narrowing "In today" through the Task-only [menuStates] is precisely the mistake that
 *    would make every Habit/Chore/Event vanish from the segment: a confident "no" to "is my habit on my
 *    plan today?".
 *
 * Both fields default to empty so `ItemRowDecorations()` is the honest resting value for a tree wired
 * without a shell join — under "In today" that shows nothing rather than everything, and every row gets
 * the cross-kind menu rather than a guessed Task one.
 */
data class ItemRowDecorations(
    val menuStates: Map<String, TaskMenuState> = emptyMap(),
    val inTodayIds: Set<String> = emptySet(),
)
