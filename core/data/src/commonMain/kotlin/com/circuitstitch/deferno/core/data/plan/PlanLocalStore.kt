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
     * Replaces the whole day's plan with [taskIds] in order — the per-day full-snapshot reconcile
     * (ADR-0001): delete the day's existing entries then re-insert the fresh ordered set, atomically,
     * so the day never reads as half-reconciled.
     */
    suspend fun replacePlan(date: LocalDate, tz: String, taskIds: List<TaskId>)
}
