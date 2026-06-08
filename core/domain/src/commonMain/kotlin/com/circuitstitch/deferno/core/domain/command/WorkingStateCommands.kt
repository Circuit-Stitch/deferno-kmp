package com.circuitstitch.deferno.core.domain.command

import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState

/**
 * The single mapping from a target [WorkingState] to the lifecycle [TaskCommand] that reaches it
 * (ADR-0007). The five status verbs ([StartTask] / [SendTaskToReview] / [CompleteTask] / [ReopenTask]
 * / [DropTask]) cover the whole [WorkingState] lattice 1:1; an interactive surface (the Tasks detail,
 * #73) offers an affordance per state and converts the chosen state into a command **here**, so the
 * transition matrix lives once in `core:domain` and the UI layer never re-derives it.
 *
 * Pure data in, pure data out — no side effects (the executor dispatches the returned command).
 */
fun taskCommandFor(taskId: TaskId, target: WorkingState): TaskCommand = when (target) {
    WorkingState.Open -> ReopenTask(taskId)
    WorkingState.InProgress -> StartTask(taskId)
    WorkingState.InReview -> SendTaskToReview(taskId)
    WorkingState.Done -> CompleteTask(taskId)
    WorkingState.Dropped -> DropTask(taskId)
}
