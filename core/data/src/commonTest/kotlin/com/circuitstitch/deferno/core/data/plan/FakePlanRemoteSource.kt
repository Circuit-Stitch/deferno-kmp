package com.circuitstitch.deferno.core.data.plan

import com.circuitstitch.deferno.core.model.TaskId
import kotlinx.datetime.LocalDate

/**
 * Scriptable [PlanRemoteSource] for the plan repository tests (#22). A test sets [plan] to the
 * ordered ids the next refresh sees; [failNext] simulates the offline-first failure path (a refresh
 * that can't reach the server returns `null`, leaving the cached plan intact).
 */
class FakePlanRemoteSource(
    var plan: List<TaskId> = emptyList(),
    var failNext: Boolean = false,
) : PlanRemoteSource {

    override suspend fun fetchPlan(date: LocalDate, tz: String): List<TaskId>? {
        if (failNext) return null
        return plan
    }
}
