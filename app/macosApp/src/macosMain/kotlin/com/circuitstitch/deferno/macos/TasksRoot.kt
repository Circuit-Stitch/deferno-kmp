package com.circuitstitch.deferno.macos

import com.circuitstitch.deferno.feature.plan.PlanComponent
import com.circuitstitch.deferno.feature.tasks.ItemTreeComponent
import com.circuitstitch.deferno.feature.tasks.TaskPane
import com.circuitstitch.deferno.feature.tasks.TasksComponent
import com.circuitstitch.deferno.macos.bridge.DetailSlot
import com.circuitstitch.deferno.macos.bridge.ValueBridge

// The Swift-facing Destination roots for the Tasks + Plan tabs. They flatten the Decompose
// `Value`/`ChildSlot` generics through the SKIE-free bridge so SwiftUI never touches them. The shell
// builds them from the real components via `ShellBridge.tasksRoot` / `planRoot`. (Extracted from the
// retired Phase-1 demo harness, ADR-0029 Phase 1b / #188 — these are live, the demo wiring was not.)

/**
 * The Swift-facing handle for the Tasks Destination. Exposes the always-present Item [tree] component
 * (the primary pane since #227/ADR-0034), the co-resident [detail] slot, and [activePane] recency
 * (ADR-0007). The detail slot is flattened through the SKIE-free bridge so SwiftUI never touches the
 * Decompose `Value`/`ChildSlot` generics; the tree is a plain component, its `state` observed via
 * `itemTreeStateBridge`.
 */
class TasksRoot internal constructor(private val component: TasksComponent) {
    val tree: ItemTreeComponent get() = component.tree
    val detail: DetailSlot = DetailSlot(component.detail)
    val activePane: ValueBridge<TaskPane> = ValueBridge(component.activePane)
}

/**
 * The Swift-facing handle for the Plan Destination. [PlanComponent]'s public API is already free of
 * Decompose types (just `StateFlow` state + intents), so the Views consume it directly (the state via
 * `planStateBridge`).
 */
class PlanRoot internal constructor(val component: PlanComponent)
