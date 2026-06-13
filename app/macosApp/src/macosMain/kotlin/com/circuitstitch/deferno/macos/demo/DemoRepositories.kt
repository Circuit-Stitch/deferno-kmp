package com.circuitstitch.deferno.macos.demo

import com.circuitstitch.deferno.core.data.plan.PlanRepository
import com.circuitstitch.deferno.core.data.task.TaskRepository
import com.circuitstitch.deferno.core.data.task.TaskSearchQuery
import com.circuitstitch.deferno.core.data.task.TaskSearchResult
import com.circuitstitch.deferno.core.model.HydrationState
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.datetime.LocalDate

/**
 * In-memory [TaskRepository] fake for the **iOS demo harness** (#51) — the SwiftUI counterpart of the
 * Android shell's `demo/DemoTaskRepository`. Honors the offline-first contract shape (reads are local
 * Flows; [hydrate] upgrades a summary row to full — #22) without any network or database: [refresh]
 * is a no-op (the sample data is the source of truth) and [hydrate] fills a sample description.
 * [setWorkingState] backs the demo's working-state editor so the detail chips flip live. The real app
 * uses the DI-provided OfflineTaskRepository (a follow-up, #68/ADR-0014); this stays a fixture.
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

    override suspend fun search(query: TaskSearchQuery): TaskSearchResult {
        val term = query.query.trim().lowercase()
        if (term.length < 2) return TaskSearchResult.Success(emptyList())
        return TaskSearchResult.Success(
            tasks.value.filter { task ->
                val matchesTerm = task.title.lowercase().contains(term) ||
                    task.description?.lowercase()?.contains(term) == true
                val matchesStatus = query.statuses.isEmpty() || task.workingState in query.statuses
                val matchesLabel = query.labels.isEmpty() || task.labels.any { it in query.labels }
                matchesTerm && matchesStatus && matchesLabel
            },
        )
    }

    /** Optimistically flip a Task's working state — the demo stand-in for the offline-first write seam. */
    fun setWorkingState(id: TaskId, target: WorkingState) {
        tasks.update { list ->
            list.map { task -> if (task.id == id) task.copy(workingState = target) else task }
        }
    }

    /** Current snapshot of a Task (used to mirror an add-to-plan into the demo plan). */
    fun snapshot(id: TaskId): Task? = tasks.value.firstOrNull { it.id == id }
}

/**
 * In-memory [PlanRepository] fake for the iOS demo harness (#51) — mirrors the Android
 * `demo/DemoPlanRepository`. [observePlan] ignores `date`/`tz` (a single demo day), [refreshPlan] is
 * a no-op, and [add] lets the Tasks detail's "add to today's plan" intent surface in the Plan tab.
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
