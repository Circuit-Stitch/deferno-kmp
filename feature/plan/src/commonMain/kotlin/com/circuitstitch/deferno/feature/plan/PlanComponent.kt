package com.circuitstitch.deferno.feature.plan

import com.arkivanov.decompose.ComponentContext
import com.circuitstitch.deferno.core.common.componentScope
import com.circuitstitch.deferno.core.data.plan.PlanRepository
import com.circuitstitch.deferno.core.model.PlanRow
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

/** Observable state for the daily Plan: today's ordered rows (design-principles.md: open into the Plan). */
data class PlanState(
    val rows: List<PlanRow> = emptyList(),
    val isRefreshing: Boolean = false,
)

/**
 * The daily Plan component (#25, #385). Exposes the ordered rows for [date]/[tz] from [PlanRepository]
 * as observable [state], and emits an [Output.OpenTask] navigation intent when a **Task** entry is
 * tapped — the Task detail lives in the Tasks feature, so opening one is a cross-feature intent the
 * host routes, never platform navigation baked in here (ADR-0007). [onRefresh] pulls the plan for the
 * day.
 *
 * **Only a Task row opens.** Since #385 a plan row may be a Habit, Chore or Event, and none of those
 * has a detail surface on any platform yet (#383) — so [onTaskClicked] stays [TaskId]-typed rather
 * than widening to an intent the shell has nowhere to route. The View is what enforces this: a
 * recurring row renders without the open affordance instead of offering a tap that would go nowhere.
 * When #383 lands, this widens to carry the kind and the gate moves here.
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
        combine(planRepository.observePlan(date, tz), refreshing) { rows, isRefreshing ->
            PlanState(rows = rows, isRefreshing = isRefreshing)
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
