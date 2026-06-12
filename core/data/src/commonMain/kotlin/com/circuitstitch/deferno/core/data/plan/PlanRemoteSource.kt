package com.circuitstitch.deferno.core.data.plan

import com.circuitstitch.deferno.core.data.RemoteSnapshot
import com.circuitstitch.deferno.core.model.TaskId
import kotlinx.datetime.LocalDate

/**
 * The network port the plan repository refreshes through (ADR-0001, #22). `/tasks/plan` is a flat,
 * ordered list of Task summaries (CONTRACT-NOTES -> Items); the plan only needs the *ordering*, so
 * this returns the [TaskId]s in plan order. (The summaries themselves are reconciled into the Task
 * cache via the Task repository — the plan stores only the day's ordering.)
 *
 * Offline-first (ADR-0001): a failed call is [RemoteSnapshot.Unavailable] so a failed plan refresh
 * leaves the cached plan untouched, distinct from an [RemoteSnapshot.Available] *empty* day (which the
 * reconcile honours by clearing the day's ordering).
 */
interface PlanRemoteSource {

    /**
     * The ordered [TaskId]s of the plan for `(date, tz)` as [RemoteSnapshot.Available] (possibly empty),
     * or [RemoteSnapshot.Unavailable] on failure (cache untouched).
     */
    suspend fun fetchPlan(date: LocalDate, tz: String): RemoteSnapshot<List<TaskId>>
}
