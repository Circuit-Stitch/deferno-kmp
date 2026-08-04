package com.circuitstitch.deferno.core.data.plan

import com.circuitstitch.deferno.core.model.PlanRow
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDate

/**
 * The daily-plan repository the UI/feature layer depends on (ADR-0001, #22, #385). Mirrors
 * [com.circuitstitch.deferno.core.data.task.TaskRepository] but simpler — a plan row has no
 * hydration concept, only ordering. Reads are local DB `Flow`s only; [refreshPlan] pulls the
 * per-day snapshot and reconciles the ordering. See [OfflinePlanRepository].
 */
interface PlanRepository {

    /**
     * The plan's rows for `(date, tz)` in plan order, resolved from the four per-kind caches — a
     * plan holds items of **any** kind, not just Tasks (#385). A plan entry whose row is not (yet)
     * cached is skipped, so the stream renders the rows that exist in order rather than stalling on
     * a not-yet-hydrated reference (#22).
     */
    fun observePlan(date: LocalDate, tz: String): Flow<List<PlanRow>>

    /** Pulls the per-day plan snapshot and reconciles the ordering as a full replace (ADR-0001). */
    suspend fun refreshPlan(date: LocalDate, tz: String)
}
