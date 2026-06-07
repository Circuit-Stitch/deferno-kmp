package com.circuitstitch.deferno.core.data.plan

import com.circuitstitch.deferno.core.data.outbox.OutboxStore
import com.circuitstitch.deferno.core.data.outbox.PlanAdd
import com.circuitstitch.deferno.core.data.outbox.PlanMutation
import com.circuitstitch.deferno.core.data.outbox.PlanRemove
import com.circuitstitch.deferno.core.data.outbox.PlanReorder
import com.circuitstitch.deferno.core.model.TaskId
import kotlinx.datetime.LocalDate
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The offline-first [PlanWriter] (ADR-0001, #23): optimistic local apply + enqueue. Each write reads
 * the current day ordering ([PlanLocalStore.currentPlan]), applies the intent's pure transform, and
 * [PlanLocalStore.replacePlan]s the result — so the plan UI reorders instantly — then queues the
 * idempotent request for replay.
 *
 * The read-modify-replace isn't wrapped in a single transaction (the plan store's atomic unit is
 * `replacePlan` itself); a concurrent replace could make the snapshot stale, but the post-flush
 * reconcile (ADR-0001 LWW) converges the day back on server truth, so a transient divergence is benign.
 */
class OutboxPlanWriter(
    private val planStore: PlanLocalStore,
    private val outbox: OutboxStore,
    private val now: () -> Instant = { Clock.System.now() },
) : PlanWriter {

    override suspend fun add(taskId: TaskId, date: LocalDate, tz: String) = submit(PlanAdd(taskId, date, tz))

    override suspend fun remove(taskId: TaskId, date: LocalDate, tz: String) = submit(PlanRemove(taskId, date, tz))

    override suspend fun reorder(taskIds: List<TaskId>, date: LocalDate, tz: String) =
        submit(PlanReorder(taskIds, date, tz))

    private suspend fun submit(mutation: PlanMutation) {
        val current = planStore.currentPlan(mutation.date, mutation.tz)
        planStore.replacePlan(mutation.date, mutation.tz, mutation.applyTo(current))
        outbox.enqueue(mutation.target, mutation.toRequest(), now())
    }
}
