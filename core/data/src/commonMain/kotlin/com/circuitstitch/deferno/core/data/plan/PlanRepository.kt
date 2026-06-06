package com.circuitstitch.deferno.core.data.plan

import com.circuitstitch.deferno.core.model.Task
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDate

/**
 * The daily-plan repository the UI/feature layer depends on (ADR-0001, #22). Mirrors
 * [com.circuitstitch.deferno.core.data.task.TaskRepository] but simpler — a plan row has no
 * hydration concept, only ordering. Reads are local DB `Flow`s only; [refreshPlan] pulls the
 * per-day snapshot and reconciles the ordering. See [OfflinePlanRepository].
 */
interface PlanRepository {

    /**
     * The plan's Tasks for `(date, tz)` in plan order, resolved from the Task cache. A plan entry
     * whose Task is not (yet) cached is skipped, so the stream renders the Tasks that exist in order
     * rather than stalling on a not-yet-hydrated reference (#22).
     */
    fun observePlan(date: LocalDate, tz: String): Flow<List<Task>>

    /** Pulls the per-day plan snapshot and reconciles the ordering as a full replace (ADR-0001). */
    suspend fun refreshPlan(date: LocalDate, tz: String)
}
