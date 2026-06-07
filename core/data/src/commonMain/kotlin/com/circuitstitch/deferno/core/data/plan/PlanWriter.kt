package com.circuitstitch.deferno.core.data.plan

import com.circuitstitch.deferno.core.model.TaskId
import kotlinx.datetime.LocalDate

/**
 * The daily-plan **write** seam the UI/feature layer drives (ADR-0001, #23) — the plan counterpart to
 * [com.circuitstitch.deferno.core.data.task.TaskWriter]. Each call applies optimistically to the
 * cached day ordering and enqueues an intent-based, idempotent plan mutation for FIFO replay when
 * online. See [OutboxPlanWriter] and the `PlanAdd`/`PlanRemove`/`PlanReorder` intents.
 */
interface PlanWriter {

    /** Add [taskId] to the `(date, tz)` plan (`POST tasks/plan/add`); a no-op locally if already present. */
    suspend fun add(taskId: TaskId, date: LocalDate, tz: String)

    /** Remove [taskId] from the `(date, tz)` plan (`POST tasks/plan/remove`). */
    suspend fun remove(taskId: TaskId, date: LocalDate, tz: String)

    /** Set the `(date, tz)` plan to exactly [taskIds] in order (`POST tasks/plan/reorder`). */
    suspend fun reorder(taskIds: List<TaskId>, date: LocalDate, tz: String)
}
