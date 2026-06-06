package com.circuitstitch.deferno.feature.tasks

import com.circuitstitch.deferno.core.data.task.TaskRepository
import com.circuitstitch.deferno.core.model.HydrationState
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.datetime.Instant

/**
 * In-memory [TaskRepository] for component tests. Reads are a [MutableStateFlow] the tests mutate;
 * `refresh()`/`hydrate()` record their calls and write through, mirroring the offline-first contract
 * (reads are local Flows; network pulls write through — ADR-0001).
 */
class FakeTaskRepository(initial: List<Task> = emptyList()) : TaskRepository {
    val tasks = MutableStateFlow(initial)

    var refreshCount = 0
        private set
    val hydrateCalls = mutableListOf<TaskId>()

    /** Snapshot applied on the next `refresh()` (a network pull writing through). */
    var refreshSnapshot: List<Task>? = null

    /** Full rows returned by `hydrate()`, keyed by id (summary → full upgrade, #22). */
    var hydrateResults: Map<TaskId, Task> = emptyMap()

    override fun observeTasks(): Flow<List<Task>> = tasks

    override fun observeTask(id: TaskId): Flow<Task?> =
        tasks.map { list -> list.firstOrNull { it.id == id } }

    override suspend fun refresh() {
        refreshCount++
        refreshSnapshot?.let { snapshot -> tasks.value = snapshot }
    }

    override suspend fun hydrate(id: TaskId) {
        hydrateCalls += id
        hydrateResults[id]?.let { full ->
            tasks.update { current ->
                if (current.any { it.id == id }) current.map { if (it.id == id) full else it } else current + full
            }
        }
    }
}

private val FIXED_CREATED = Instant.parse("2026-06-01T00:00:00Z")

/** Concise [Task] fixture for component tests. */
internal fun task(
    id: String,
    title: String = "Task $id",
    parentId: String? = null,
    sequence: Long? = null,
    workingState: WorkingState = WorkingState.Open,
    hydration: HydrationState = HydrationState.Summary,
    description: String? = null,
): Task = Task(
    id = TaskId(id),
    orgSlug = "u-test",
    title = title,
    workingState = workingState,
    parentId = parentId?.let(::TaskId),
    sequence = sequence,
    dateCreated = FIXED_CREATED,
    hydration = hydration,
    description = description,
)
