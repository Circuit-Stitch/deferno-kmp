package com.circuitstitch.deferno.feature.tasks

import com.arkivanov.decompose.ComponentContext
import com.circuitstitch.deferno.core.common.componentScope
import com.circuitstitch.deferno.core.data.task.TaskRepository
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/** Observable state for the Task list pane. The View renders this and holds no business logic. */
data class TaskListState(
    val tasks: List<Task> = emptyList(),
    val isRefreshing: Boolean = false,
)

/**
 * The Task list component (ADR-0007: the co-resident list pane). Exposes the live Task list from the
 * repository as observable [state], and emits an [Output.TaskSelected] navigation intent when a row
 * is tapped — it never performs platform navigation itself; the parent decides what "selected" means
 * (open the co-resident detail slot). [onRefresh] triggers an explicit network pull (offline-first:
 * reads are local, writes through on refresh).
 */
interface TaskListComponent {
    val state: StateFlow<TaskListState>

    fun onTaskClicked(id: TaskId)
    fun onRefresh()

    /** Navigation intents emitted upward (ADR-0007: intents, not baked-in platform nav). */
    sealed interface Output {
        data class TaskSelected(val id: TaskId) : Output
    }
}

class DefaultTaskListComponent(
    componentContext: ComponentContext,
    private val taskRepository: TaskRepository,
    private val output: (TaskListComponent.Output) -> Unit,
    coroutineContext: CoroutineContext = Dispatchers.Default,
) : TaskListComponent, ComponentContext by componentContext {

    private val scope = componentScope(coroutineContext)
    private val refreshing = MutableStateFlow(false)

    override val state: StateFlow<TaskListState> =
        combine(taskRepository.observeTasks(), refreshing) { tasks, isRefreshing ->
            TaskListState(tasks = tasks, isRefreshing = isRefreshing)
        }.stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TaskListState())

    override fun onTaskClicked(id: TaskId) {
        output(TaskListComponent.Output.TaskSelected(id))
    }

    override fun onRefresh() {
        scope.launch {
            refreshing.value = true
            try {
                taskRepository.refresh()
            } finally {
                refreshing.value = false
            }
        }
    }
}

// Keep the upstream alive briefly across config changes / brief detachment, then stop to save work.
internal const val STOP_TIMEOUT_MS = 5_000L
