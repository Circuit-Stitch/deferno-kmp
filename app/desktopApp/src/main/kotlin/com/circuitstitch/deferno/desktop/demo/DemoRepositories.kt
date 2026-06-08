package com.circuitstitch.deferno.desktop.demo

import com.circuitstitch.deferno.core.data.plan.PlanRepository
import com.circuitstitch.deferno.core.data.task.TaskRepository
import com.circuitstitch.deferno.core.data.task.TaskSearchQuery
import com.circuitstitch.deferno.core.model.HydrationState
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.datetime.LocalDate

/**
 * TEMPORARY in-memory [TaskRepository] backing the desktop navigation shell. Honors the offline-first
 * contract shape (reads are local Flows; [hydrate] upgrades a summary row to full — #22) without any
 * network or database: [refresh] is a no-op because the data is already local, and [hydrate] fills in
 * a sample description so opening a Task visibly demonstrates the summary → full upgrade. Duplicated
 * from app/androidApp; replaced by the DI-provided process-global repository when DI lands (ADR-0008).
 */
internal class DemoTaskRepository(initial: List<Task>) : TaskRepository {
    private val tasks = MutableStateFlow(initial)

    override fun observeTasks(): Flow<List<Task>> = tasks

    override fun observeTask(id: TaskId): Flow<Task?> =
        tasks.map { list -> list.firstOrNull { it.id == id } }

    override suspend fun refresh() {
        // Offline demo: nothing to pull — the sample data is the source of truth.
    }

    override suspend fun hydrate(id: TaskId) {
        tasks.update { list ->
            list.map { task ->
                if (task.id == id && task.hydration != HydrationState.Full) {
                    task.copy(
                        hydration = HydrationState.Full,
                        description = task.description ?: SampleData.descriptionFor(task),
                    )
                } else {
                    task
                }
            }
        }
    }

    override suspend fun search(query: TaskSearchQuery): List<Task> {
        // Offline demo: filter the in-memory list by the term + status/label filters (a stand-in for
        // the online `GET /tasks/search`, so the desktop shell's Search overlay returns rows, #73).
        val term = query.query.trim().lowercase()
        if (term.length < 2) return emptyList()
        return tasks.value.filter { task ->
            val matchesTerm = task.title.lowercase().contains(term) ||
                task.description?.lowercase()?.contains(term) == true
            val matchesStatus = query.statuses.isEmpty() || task.workingState in query.statuses
            val matchesLabel = query.labels.isEmpty() || task.labels.any { it in query.labels }
            matchesTerm && matchesStatus && matchesLabel
        }
    }

    /** Current snapshot of a Task (used to mirror an add-to-plan into the demo plan). */
    fun snapshot(id: TaskId): Task? = tasks.value.firstOrNull { it.id == id }
}

/**
 * TEMPORARY in-memory [PlanRepository] backing the desktop navigation shell. [observePlan] ignores
 * `date`/`tz` (a single demo day) and [refreshPlan] is a no-op; [add] lets the shell mirror an "add to
 * plan" intent so the Plan Destination updates live. Replaced by the DI-provided repository (ADR-0008).
 */
internal class DemoPlanRepository(initial: List<Task>) : PlanRepository {
    private val plan = MutableStateFlow(initial)

    override fun observePlan(date: LocalDate, tz: String): Flow<List<Task>> = plan

    override suspend fun refreshPlan(date: LocalDate, tz: String) {
        // Offline demo: nothing to pull.
    }

    fun add(task: Task?) {
        if (task == null) return
        plan.update { current -> if (current.any { it.id == task.id }) current else current + task }
    }
}
