package com.circuitstitch.deferno.core.data.plan

import com.circuitstitch.deferno.core.model.TaskId
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDate

/**
 * The local source-of-truth port for daily-plan *ordering* (ADR-0001, #22). A plan row only holds
 * the `(date, tz, position) -> taskId` ordering (#21); the referenced Tasks live in the Task cache
 * and are reconciled independently. So this store deals purely in ordered [TaskId] lists — the
 * [PlanRepository] joins them back to domain Tasks.
 *
 * Like [com.circuitstitch.deferno.core.data.task.TaskLocalStore], the port keeps the plan reconcile
 * (a per-day full-snapshot replace) unit-testable against an in-memory fake while the SQLDelight
 * impl proves the real SQL path.
 */
interface PlanLocalStore {

    /** The ordered [TaskId]s for `(date, tz)`, observed as a DB `Flow`; re-emits on a replace. */
    fun observePlan(date: LocalDate, tz: String): Flow<List<TaskId>>

    /**
     * A one-shot snapshot of the ordered [TaskId]s for `(date, tz)` — the non-`Flow` read the offline
     * write path needs (#23): an optimistic plan mutation (add/remove/reorder) reads the current
     * order, transforms it, and [replacePlan]s the result. Distinct from [observePlan] (the reactive
     * UI stream) the way [com.circuitstitch.deferno.core.data.task.TaskLocalStore.get] is distinct
     * from `observe`. A point-in-time read: a concurrent [replacePlan] may make the snapshot stale,
     * but the post-flush reconcile (ADR-0001 LWW) corrects any divergence.
     */
    suspend fun currentPlan(date: LocalDate, tz: String): List<TaskId>

    /**
     * Replaces the whole day's plan with [taskIds] in order — the per-day full-snapshot reconcile
     * (ADR-0001): delete the day's existing entries then re-insert the fresh ordered set, atomically,
     * so the day never reads as half-reconciled.
     */
    suspend fun replacePlan(date: LocalDate, tz: String, taskIds: List<TaskId>)
}
