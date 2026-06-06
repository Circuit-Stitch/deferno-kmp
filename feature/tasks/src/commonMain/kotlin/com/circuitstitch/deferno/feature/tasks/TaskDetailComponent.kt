package com.circuitstitch.deferno.feature.tasks

import com.arkivanov.decompose.ComponentContext
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

/** Observable state for the Task detail pane. [task] is null until the local row is observed. */
data class TaskDetailState(
    val task: Task? = null,
    val isHydrating: Boolean = false,
)

/**
 * The Task detail component (ADR-0007: the co-resident detail pane). Observes one Task from the
 * repository and, on creation, requests a [TaskRepository.hydrate] to upgrade the summary row to the
 * full detail (description, ownerOrgId, nextTaskId — #22). All actions are emitted as [Output]
 * navigation intents; the parent owns the co-resident slots and any cross-feature routing.
 */
interface TaskDetailComponent {
    val taskId: TaskId
    val state: StateFlow<TaskDetailState>

    fun onCloseClicked()
    fun onShowTreeClicked()
    fun onAddToPlanClicked()

    sealed interface Output {
        data object Closed : Output
        data class TreeRequested(val id: TaskId) : Output
        data class AddToPlanRequested(val id: TaskId) : Output
    }
}

class DefaultTaskDetailComponent(
    componentContext: ComponentContext,
    override val taskId: TaskId,
    private val taskRepository: TaskRepository,
    private val output: (TaskDetailComponent.Output) -> Unit,
    coroutineContext: CoroutineContext = Dispatchers.Default,
) : TaskDetailComponent, ComponentContext by componentContext {

    private val scope = componentScope(coroutineContext)
    private val hydrating = MutableStateFlow(true)

    override val state: StateFlow<TaskDetailState> =
        combine(taskRepository.observeTask(taskId), hydrating) { task, isHydrating ->
            TaskDetailState(task = task, isHydrating = isHydrating)
        }.stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TaskDetailState(isHydrating = true))

    init {
        scope.launch {
            try {
                taskRepository.hydrate(taskId)
            } finally {
                hydrating.value = false
            }
        }
    }

    override fun onCloseClicked() {
        output(TaskDetailComponent.Output.Closed)
    }

    override fun onShowTreeClicked() {
        output(TaskDetailComponent.Output.TreeRequested(taskId))
    }

    override fun onAddToPlanClicked() {
        output(TaskDetailComponent.Output.AddToPlanRequested(taskId))
    }
}
