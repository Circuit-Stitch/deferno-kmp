package com.circuitstitch.deferno.feature.plan

import com.circuitstitch.deferno.core.data.plan.PlanRepository
import com.circuitstitch.deferno.core.model.HydrationState
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

/**
 * In-memory [PlanRepository] for component tests: a [MutableStateFlow] of the day's ordered Tasks the
 * tests mutate, plus a recorded `refreshPlan()` call that can write a snapshot through.
 */
class FakePlanRepository(initial: List<Task> = emptyList()) : PlanRepository {
    val plan = MutableStateFlow(initial)

    var refreshCount = 0
        private set
    val refreshArgs = mutableListOf<Pair<LocalDate, String>>()

    /** Snapshot applied on the next `refreshPlan()` (a network pull writing through). */
    var refreshSnapshot: List<Task>? = null

    override fun observePlan(date: LocalDate, tz: String): Flow<List<Task>> = plan

    override suspend fun refreshPlan(date: LocalDate, tz: String) {
        refreshCount++
        refreshArgs += date to tz
        refreshSnapshot?.let { plan.value = it }
    }
}

private val FIXED_CREATED = Instant.parse("2026-06-01T00:00:00Z")

internal fun task(id: String, title: String = "Task $id"): Task = Task(
    id = TaskId(id),
    orgSlug = "u-test",
    title = title,
    workingState = WorkingState.Open,
    dateCreated = FIXED_CREATED,
    hydration = HydrationState.Summary,
)
