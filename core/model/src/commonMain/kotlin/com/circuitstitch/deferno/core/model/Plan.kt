package com.circuitstitch.deferno.core.model

import kotlinx.datetime.LocalDate

/**
 * One ordered slot in a daily plan (ADR-0001, #21). The daily plan (`GET /tasks/plan`) is an
 * ordered, date+timezone-scoped list referencing Tasks by id, so a plan row is keyed by
 * `(planDate, tz, position)` and points at a [taskId]. Persisted as the `plan_entry` table and
 * reconciled as a full snapshot per `(planDate, tz)` (#22).
 *
 * The referenced Task lives in the Task table; the plan only holds the ordering, so an entry can
 * exist before its Task has been hydrated and a Task can be reconciled independently of the plan.
 */
data class PlanEntry(
    val planDate: LocalDate,
    val tz: String,
    val position: Int,
    val taskId: TaskId,
)

/**
 * A whole daily plan for one `(date, tz)`: the [taskIds] in plan order. The convenience aggregate
 * the plan repository exposes (built from the ordered [PlanEntry] rows); pairs with the Task
 * repository so the UI can render the plan's Tasks from their cached [Task] rows.
 */
data class DailyPlan(
    val date: LocalDate,
    val tz: String,
    val taskIds: List<TaskId> = emptyList(),
)
