package com.circuitstitch.deferno.core.data.plan

import com.circuitstitch.deferno.core.data.RemoteSnapshot
import com.circuitstitch.deferno.core.data.task.TaskLocalStore
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.datetime.LocalDate

/**
 * The offline-first [PlanRepository] (ADR-0001, #22). It deliberately mirrors
 * [com.circuitstitch.deferno.core.data.task.OfflineTaskRepository] but is *simpler* — a plan row has
 * no hydration concept, only ordering:
 *
 * **Reconcile ([refreshPlan]).** `/tasks/plan` is an ordered full snapshot per `(date, tz)`
 * (CONTRACT-NOTES -> Items), so a refresh pulls the ordered ids and the local store does a full
 * per-day replace (delete the day, re-insert the fresh ordered set, atomically). An
 * [RemoteSnapshot.Unavailable] pull skips the replace, leaving the cached plan intact; an
 * [RemoteSnapshot.Available] (possibly empty) one replaces — an empty day clears the ordering.
 *
 * **Resolve ([observePlan]).** The plan store holds only the ordering; the Tasks live in the Task
 * cache and are reconciled independently. So the repository [combine]s the ordered ids with the
 * Task store's live rows and resolves each id to its cached [Task] *in plan order*, skipping an id
 * whose Task isn't cached yet (e.g. a brand-new plan entry the Task refresh hasn't pulled). The
 * result re-emits whenever either the ordering or the underlying Tasks change.
 */
class OfflinePlanRepository(
    private val planStore: PlanLocalStore,
    private val remoteSource: PlanRemoteSource,
    private val taskStore: TaskLocalStore,
) : PlanRepository {

    override fun observePlan(date: LocalDate, tz: String): Flow<List<Task>> =
        planStore.observePlan(date, tz).combine(taskStore.observeActive()) { orderedIds, tasks ->
            val byId: Map<TaskId, Task> = tasks.associateBy { it.id }
            // Resolve in plan order, dropping ids with no cached Task (not yet pulled/hydrated).
            orderedIds.mapNotNull { byId[it] }
        }

    override suspend fun refreshPlan(date: LocalDate, tz: String) {
        val ordered = when (val result = remoteSource.fetchPlan(date, tz)) {
            is RemoteSnapshot.Available -> result.value
            RemoteSnapshot.Unavailable -> return
        }
        planStore.replacePlan(date, tz, ordered)
    }
}
