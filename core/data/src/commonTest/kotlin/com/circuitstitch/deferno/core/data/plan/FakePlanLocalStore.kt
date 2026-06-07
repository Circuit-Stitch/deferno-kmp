package com.circuitstitch.deferno.core.data.plan

import com.circuitstitch.deferno.core.model.TaskId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate

/**
 * In-memory [PlanLocalStore] for the plan reconcile tests (#22). Keyed by `(date, tz)` -> ordered
 * [TaskId]s, backed by a [MutableStateFlow] so [observePlan] is a real re-emitting `Flow`. The
 * per-day replace is a single map write, mirroring the SQLDelight impl's atomic
 * delete-then-reinsert (which fires its query listeners once, at commit).
 */
class FakePlanLocalStore(
    initial: Map<PlanKey, List<TaskId>> = emptyMap(),
) : PlanLocalStore {

    data class PlanKey(val date: LocalDate, val tz: String)

    private val plans = MutableStateFlow(initial)

    /** Direct read of the backing map for assertions. */
    val all: Map<PlanKey, List<TaskId>> get() = plans.value

    override fun observePlan(date: LocalDate, tz: String): Flow<List<TaskId>> =
        plans.map { it[PlanKey(date, tz)] ?: emptyList() }

    override suspend fun currentPlan(date: LocalDate, tz: String): List<TaskId> =
        plans.value[PlanKey(date, tz)] ?: emptyList()

    override suspend fun replacePlan(date: LocalDate, tz: String, taskIds: List<TaskId>) {
        plans.value = plans.value + (PlanKey(date, tz) to taskIds)
    }
}
