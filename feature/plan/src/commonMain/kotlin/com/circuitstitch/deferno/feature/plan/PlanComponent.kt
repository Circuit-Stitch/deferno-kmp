package com.circuitstitch.deferno.feature.plan

import com.arkivanov.decompose.ComponentContext
import com.circuitstitch.deferno.core.common.componentScope
import com.circuitstitch.deferno.core.data.plan.PlanRepository
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlin.coroutines.CoroutineContext

/** Observable state for the daily Plan: today's ordered Tasks (design-principles.md: open into the Plan). */
data class PlanState(
    val tasks: List<Task> = emptyList(),
    val isRefreshing: Boolean = false,
)

/**
 * The daily Plan component (#25). Exposes the ordered Tasks for [date]/[tz] from [PlanRepository] as
 * observable [state], and emits an [Output.OpenTask] navigation intent when an entry is tapped — the
 * Task detail lives in the Tasks feature, so opening one is a cross-feature intent the host routes,
 * never platform navigation baked in here (ADR-0007). [onRefresh] pulls the plan for the day.
 */
interface PlanComponent {
    val state: StateFlow<PlanState>

    fun onTaskClicked(id: TaskId)
    fun onRefresh()

    sealed interface Output {
        data class OpenTask(val id: TaskId) : Output
    }
}

class DefaultPlanComponent(
    componentContext: ComponentContext,
    private val planRepository: PlanRepository,
    private val date: LocalDate,
    private val tz: String,
    private val output: (PlanComponent.Output) -> Unit,
    coroutineContext: CoroutineContext = Dispatchers.Default,
) : PlanComponent, ComponentContext by componentContext {

    private val scope: CoroutineScope = componentScope(coroutineContext)

    private val refreshing = MutableStateFlow(false)

    override val state: StateFlow<PlanState> =
        combine(planRepository.observePlan(date, tz), refreshing) { tasks, isRefreshing ->
            PlanState(tasks = tasks, isRefreshing = isRefreshing)
        }.stateIn(scope, SharingStarted.WhileSubscribed(5_000L), PlanState())

    override fun onTaskClicked(id: TaskId) {
        output(PlanComponent.Output.OpenTask(id))
    }

    override fun onRefresh() {
        scope.launch {
            refreshing.value = true
            try {
                planRepository.refreshPlan(date, tz)
            } finally {
                refreshing.value = false
            }
        }
    }
}
