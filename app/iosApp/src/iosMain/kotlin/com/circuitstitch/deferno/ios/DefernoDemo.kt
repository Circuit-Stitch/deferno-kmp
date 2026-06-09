package com.circuitstitch.deferno.ios

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.decompose.childContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import com.circuitstitch.deferno.feature.plan.DefaultPlanComponent
import com.circuitstitch.deferno.feature.plan.PlanComponent
import com.circuitstitch.deferno.feature.tasks.DefaultTasksComponent
import com.circuitstitch.deferno.feature.tasks.TaskListComponent
import com.circuitstitch.deferno.feature.tasks.TaskPane
import com.circuitstitch.deferno.feature.tasks.TasksComponent
import com.circuitstitch.deferno.feature.tasks.WorkingStateEditor
import com.circuitstitch.deferno.ios.bridge.DetailSlot
import com.circuitstitch.deferno.ios.bridge.TreeSlot
import com.circuitstitch.deferno.ios.bridge.ValueBridge
import com.circuitstitch.deferno.ios.demo.DemoPlanRepository
import com.circuitstitch.deferno.ios.demo.DemoTaskRepository
import com.circuitstitch.deferno.ios.demo.SampleData
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.LocalDate

/**
 * The iOS **demo harness** (#51): it constructs the *real* shared Decompose components
 * ([DefaultTasksComponent] / [DefaultPlanComponent]) over in-memory [DemoTaskRepository] /
 * [DemoPlanRepository] fakes and a single retained Essenty lifecycle, then hands the SwiftUI Views
 * [tasks] / [plan] roots to render. This is the iOS analogue of the Android shell's demo wiring
 * (#27/#55): it stands in for the not-yet-built iOS app shell + DI (a follow-up, #68/ADR-0014) so the
 * Views are runnable on the simulator today. The Views themselves are genuine renderers of the shared
 * components — only the data source is a fixture.
 *
 * Tasks and Plan share the same repositories, so the detail pane's "add to today's plan" intent
 * surfaces live in the Plan tab. State is produced on [Dispatchers.Main] so observation hops straight
 * to the SwiftUI main thread.
 */
class DefernoDemo {

    private val taskRepository = DemoTaskRepository(SampleData.tasks)
    private val planRepository = DemoPlanRepository(SampleData.planTasks)

    // The working-state write seam (#73): the demo editor flips the in-memory row optimistically,
    // mirroring the offline-first apply the real CommandExecutor performs (ADR-0001/0007).
    private val workingStateEditor = WorkingStateEditor { id, target, _ ->
        taskRepository.setWorkingState(id, target)
    }

    private val lifecycle = LifecycleRegistry()
    private val root = DefaultComponentContext(lifecycle = lifecycle)

    private val tasksComponent: TasksComponent =
        DefaultTasksComponent(
            componentContext = root.childContext(key = "tasks"),
            taskRepository = taskRepository,
            output = { output ->
                when (output) {
                    // Cross-feature intent (ADR-0007): mirror the Task into the shared demo plan so it
                    // appears in the Plan tab. The real app routes this through the shell to a Command.
                    is TasksComponent.Output.AddToPlanRequested ->
                        planRepository.add(taskRepository.snapshot(output.id))
                }
            },
            workingStateEditor = workingStateEditor,
            coroutineContext = Dispatchers.Main,
        )

    private val planComponent: PlanComponent =
        DefaultPlanComponent(
            componentContext = root.childContext(key = "plan"),
            planRepository = planRepository,
            // The demo repository ignores date/tz (a single demo day), so any stable value works.
            date = LocalDate(2026, 6, 9),
            tz = "UTC",
            output = { /* OpenTask — no cross-tab routing in the demo */ },
            coroutineContext = Dispatchers.Main,
        )

    /** The Tasks Destination root the SwiftUI `TasksScreen` renders (list + co-resident detail/tree). */
    val tasks: TasksRoot = TasksRoot(tasksComponent)

    /** The Plan Destination root the SwiftUI `PlanView` renders (today's ordered Tasks). */
    val plan: PlanRoot = PlanRoot(planComponent)

    init {
        lifecycle.resume()
    }

    /** Tears down the retained component tree. Called when the SwiftUI app scene goes away. */
    fun destroy() {
        lifecycle.destroy()
    }
}

/**
 * The Swift-facing handle for the Tasks Destination. Exposes the always-present [list] component and
 * the co-resident [detail] / [tree] slots + [activePane] recency (ADR-0007), each flattened through
 * the SKIE-free bridge so SwiftUI never touches the Decompose `Value`/`ChildSlot` generics.
 */
class TasksRoot internal constructor(private val component: TasksComponent) {
    val list: TaskListComponent get() = component.list
    val detail: DetailSlot = DetailSlot(component.detail)
    val tree: TreeSlot = TreeSlot(component.tree)
    val activePane: ValueBridge<TaskPane> = ValueBridge(component.activePane)
}

/**
 * The Swift-facing handle for the Plan Destination. [PlanComponent]'s public API is already free of
 * Decompose types (just `StateFlow` state + intents), so the Views consume it directly (the state via
 * `planStateBridge`).
 */
class PlanRoot internal constructor(val component: PlanComponent)
