package com.circuitstitch.deferno.feature.tasks

import com.circuitstitch.deferno.core.model.WorkingState

/**
 * The per-row state the kind-aware long-press command menu needs but the cross-kind [Item] projection the
 * tree renders doesn't carry (ADR-0034 decision 7, #231): a Task's [workingState] (to swap the status
 * block + hide the verb it's already in), whether it is [pinned] (Pin ↔ Unpin), and whether it is in
 * today's plan ([inPlan]) (Add ↔ Remove). Present only for **Task** rows — the native write layer is
 * Task-centric (`MoveItem` is the lone cross-kind write), so a non-Task row has no menu state and the menu
 * offers only its cross-kind subset (Add subtask · Move). Non-Task status verbs are a follow-up (#299).
 *
 * Joined off the Task list + today's plan by the shell (where `today`/`tz`/the plan repository live) and
 * surfaced on [ItemTreeState.menuStates] keyed by item id; the View reads it to render the menu, and the
 * row-derived current values flow back as the menu intents' arguments (the "args from the row" rule).
 */
data class TaskMenuState(
    val workingState: WorkingState,
    val pinned: Boolean,
    val inPlan: Boolean,
)
