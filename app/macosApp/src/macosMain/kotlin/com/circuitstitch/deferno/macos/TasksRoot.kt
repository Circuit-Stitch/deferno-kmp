package com.circuitstitch.deferno.macos

import com.circuitstitch.deferno.feature.plan.PlanComponent
import com.circuitstitch.deferno.feature.tasks.ItemTreeComponent
import com.circuitstitch.deferno.feature.tasks.TasksComponent
import kotlinx.coroutines.flow.StateFlow

// The Swift-facing Destination roots for the Tasks + Plan tabs. The shell builds them from the real
// components via `ShellBridge.tasksRoot` / `planRoot`. (Extracted from the retired Phase-1 demo harness,
// ADR-0029 Phase 1b / #188 — these are live, the demo wiring was not.)

/**
 * The Swift-facing handle for the Tasks Destination. Exposes the always-present Item [tree] component
 * (the primary pane since #227/ADR-0049) and the co-resident detail slot, flattened to its nullable open
 * child as a SKIE-bridged [activeDetail] `StateFlow` (the component's `Value.asStateFlow` mirror), so
 * SwiftUI never touches the Decompose `Value`/`ChildSlot` generics.
 *
 * [activeDetail] carries a [TasksComponent.DetailChild] rather than a `TaskDetailComponent` since #383:
 * the slot opens a Task's read/write detail **or** a recurring definition's read-only one. Swift cannot
 * take a Kotlin sealed type apart, so the View asks `BridgeKt.taskDetailOrNull`/`definitionDetailOrNull`
 * which arm it got — mirroring how it already reads `MainShellComponent.PlanChild`.
 */
class TasksRoot internal constructor(private val component: TasksComponent) {
    val tree: ItemTreeComponent get() = component.tree
    val activeDetail: StateFlow<TasksComponent.DetailChild?> = component.activeDetail
}

/**
 * The Swift-facing handle for the Plan Destination. [PlanComponent]'s public API is already free of
 * Decompose types (just `StateFlow` state + intents), so the Views consume it directly (the state via
 * its `state` flow, bridged by SKIE).
 */
class PlanRoot internal constructor(val component: PlanComponent)
