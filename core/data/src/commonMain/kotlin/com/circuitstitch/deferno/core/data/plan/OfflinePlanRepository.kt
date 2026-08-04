package com.circuitstitch.deferno.core.data.plan

import com.circuitstitch.deferno.core.data.RemoteSnapshot
import com.circuitstitch.deferno.core.data.chore.ChoreLocalStore
import com.circuitstitch.deferno.core.data.event.EventLocalStore
import com.circuitstitch.deferno.core.data.habit.HabitLocalStore
import com.circuitstitch.deferno.core.data.item.toItem
import com.circuitstitch.deferno.core.data.task.TaskLocalStore
import com.circuitstitch.deferno.core.model.Item
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.PlanRow
import com.circuitstitch.deferno.core.model.Task
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.datetime.LocalDate

/**
 * The offline-first [PlanRepository] (ADR-0001, #22, #385).
 *
 * **Reconcile ([refreshPlan]).** `/items/plan` is an ordered full snapshot for a day
 * (CONTRACT-NOTES -> Items), so a refresh pulls the ordered kind-tagged refs and the local store does
 * a full per-day replace (delete the day, re-insert the fresh ordered set, atomically). An
 * [RemoteSnapshot.Unavailable] pull skips the replace, leaving the cached plan intact; an
 * [RemoteSnapshot.Available] (possibly empty) one replaces — an empty day clears the ordering.
 *
 * **Resolve ([observePlan]).** The plan store holds only the ordering; the items themselves live in
 * the four per-kind caches and are reconciled independently by the `/items` cold sync. So the
 * repository [combine]s the ordered refs with all four stores' live rows and resolves each ref to its
 * cached item *in plan order*.
 *
 * That "all four" is the whole of defect 2 in #385. This resolve used to join against the Task cache
 * alone, so a Habit or Chore the server had seeded into the day matched nothing and was dropped with
 * no diagnostic — a plan of two recurring rows rendered as an empty screen. Each ref now carries its
 * kind and is looked up in the one store that could hold it.
 *
 * A ref that still resolves to nothing is skipped, as before: the id is real but its row has not been
 * pulled yet (a brand-new plan entry the `/items` refresh has not caught up with), and skipping lets
 * the rest of the day render rather than stalling on it. A ref whose *kind* is unknown is skipped for
 * a different reason — the token did not decode, so there is no store to ask, and guessing Task is
 * what caused this bug in the first place.
 */
class OfflinePlanRepository(
    private val planStore: PlanLocalStore,
    private val remoteSource: PlanRemoteSource,
    private val taskStore: TaskLocalStore,
    private val habitStore: HabitLocalStore,
    private val choreStore: ChoreLocalStore,
    private val eventStore: EventLocalStore,
) : PlanRepository {

    override fun observePlan(date: LocalDate, tz: String): Flow<List<PlanRow>> =
        combine(
            planStore.observePlan(date),
            taskStore.observeActive(),
            habitStore.observeActive(),
            choreStore.observeActive(),
            eventStore.observeActive(),
        ) { refs, tasks, habits, chores, events ->
            val tasksById: Map<String, Task> = tasks.associateBy { it.id.value }
            val itemsById: Map<ItemKind, Map<String, Item>> = mapOf(
                ItemKind.Task to tasks.associate { it.id.value to it.toItem() },
                ItemKind.Habit to habits.associate { it.id.value to it.toItem() },
                ItemKind.Chore to chores.associate { it.id.value to it.toItem() },
                ItemKind.Event to events.associate { it.id.value to it.toItem() },
            )
            refs.mapNotNull { ref ->
                val kind = ref.kind ?: return@mapNotNull null
                val item = itemsById[kind]?.get(ref.id) ?: return@mapNotNull null
                // `task` is populated for exactly the Task arm — the concrete row the four shipped
                // Task-only affordances read (see [PlanRow]). Null for a recurring row is the honest
                // answer there, not a missing value.
                PlanRow(item = item, task = tasksById[ref.id].takeIf { kind == ItemKind.Task })
            }
        }

    override suspend fun refreshPlan(date: LocalDate, tz: String) {
        val ordered = when (val result = remoteSource.fetchPlan(date, tz)) {
            is RemoteSnapshot.Available -> result.value
            RemoteSnapshot.Unavailable -> return
        }
        planStore.replacePlan(date, tz, ordered)
    }
}
