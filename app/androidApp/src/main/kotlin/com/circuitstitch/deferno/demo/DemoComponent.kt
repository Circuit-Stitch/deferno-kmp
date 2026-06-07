package com.circuitstitch.deferno.demo

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.childContext
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.circuitstitch.deferno.feature.plan.DefaultPlanComponent
import com.circuitstitch.deferno.feature.plan.PlanComponent
import com.circuitstitch.deferno.feature.tasks.DefaultTasksComponent
import com.circuitstitch.deferno.feature.tasks.TaskPane
import com.circuitstitch.deferno.feature.tasks.TasksComponent
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

/** The two demo destinations. The app opens into [Plan] (design-principles.md: "open into the Plan"). */
enum class DemoTab { Plan, Tasks }

/**
 * TEMPORARY (#27) demo root. It owns a [TasksComponent] and a [PlanComponent] over in-memory
 * repositories and performs the small amount of cross-feature routing the real app's scene graph
 * will own once auth + DI land (ADR-0008): a Plan tap opens that Task in the Tasks pane, and a Tasks
 * "add to plan" mirrors the Task into the demo plan. Keeping this routing in the component (not the
 * Composable) lets the Views stay thin (ADR-0007).
 *
 * TODO(auth): replace this and the demo repositories with the DI-provided scene component graph.
 */
class DemoComponent(
    componentContext: ComponentContext,
) : ComponentContext by componentContext {

    private val taskRepository = DemoTaskRepository(SampleData.tasks)
    private val planRepository = DemoPlanRepository(
        SampleData.planTaskIds.mapNotNull { id -> SampleData.tasks.firstOrNull { it.id == id } },
    )

    private val timeZone = TimeZone.currentSystemDefault()
    private val today = Clock.System.todayIn(timeZone)

    private val _selectedTab = MutableValue(DemoTab.Plan)
    val selectedTab: Value<DemoTab> = _selectedTab

    val tasks: TasksComponent = DefaultTasksComponent(
        componentContext = childContext(key = "tasks"),
        taskRepository = taskRepository,
        output = ::onTasksOutput,
    )

    val plan: PlanComponent = DefaultPlanComponent(
        componentContext = childContext(key = "plan"),
        planRepository = planRepository,
        date = today,
        tz = timeZone.id,
        output = ::onPlanOutput,
    )

    fun onTabSelected(tab: DemoTab) {
        _selectedTab.value = tab
    }

    private fun onPlanOutput(output: PlanComponent.Output) {
        when (output) {
            is PlanComponent.Output.OpenTask -> {
                _selectedTab.value = DemoTab.Tasks
                // Goes through the list's public selection intent, which the Tasks root turns into
                // an open detail slot — the same path a real list tap takes.
                tasks.list.onTaskClicked(output.id)
            }
        }
    }

    private fun onTasksOutput(output: TasksComponent.Output) {
        when (output) {
            is TasksComponent.Output.AddToPlanRequested ->
                planRepository.add(taskRepository.snapshot(output.id))
        }
    }

    /**
     * Android system-back handling for the single-pane demo. It dismisses the **foregrounded** Tasks
     * pane first (the one the host actually shows, [TasksComponent.activePane]) so back always matches
     * what's on screen and reveals the co-resident slot beneath it — rather than a static tree>detail
     * order that could dismiss a hidden slot and make the press look dead. Falls back to any remaining
     * open slot, then from the Tasks tab to the Plan home. Returns whether back was consumed (false →
     * let the platform handle it, e.g. exit). Uses only public component intents.
     */
    fun handleBack(): Boolean {
        when (tasks.activePane.value) {
            TaskPane.Tree -> tasks.tree.value.child?.instance?.let { it.onCloseClicked(); return true }
            TaskPane.Detail -> tasks.detail.value.child?.instance?.let { it.onCloseClicked(); return true }
            TaskPane.List -> Unit
        }
        // Foreground is the list (or its slot was already gone): dismiss any other open slot, else go home.
        tasks.tree.value.child?.instance?.let { it.onCloseClicked(); return true }
        tasks.detail.value.child?.instance?.let { it.onCloseClicked(); return true }
        if (_selectedTab.value != DemoTab.Plan) {
            _selectedTab.value = DemoTab.Plan
            return true
        }
        return false
    }
}
