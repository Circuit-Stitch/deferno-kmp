package com.circuitstitch.deferno.feature.tasks

import com.arkivanov.decompose.ComponentContext
import com.circuitstitch.deferno.core.common.componentScope
import com.circuitstitch.deferno.core.data.task.TaskRepository
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
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

    /**
     * Move this Task to [target] (#73) — one of the five [WorkingState]s, issued as a Command through
     * the injected [WorkingStateEditor]: optimistic local apply + outbox enqueue (ADR-0001), gated on
     * the current row so a no-op transition writes nothing (ADR-0007). The local DB Flow then re-emits
     * the new state into [state], so the badge flips optimistically.
     */
    fun onSetWorkingState(target: WorkingState)

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
    // The working-state write seam (#73). Defaults to a no-op so the many existing tests that exercise
    // only the read/navigation paths construct the component without supplying it (ADR-0001/0007).
    private val workingStateEditor: WorkingStateEditor = WorkingStateEditor.NONE,
    // The in-memory summary the opener (list row / tree child) already had on screen. It seeds the
    // first state so the title + body render the instant the pane appears, instead of flashing a "Task"
    // placeholder until `observeTask` first emits a cycle later (the title "pop-in"). Null when the
    // opener has no row to hand over (e.g. a Plan-overlay tap) — then it falls back to the empty start.
    initialTask: Task? = null,
    coroutineContext: CoroutineContext = Dispatchers.Default,
) : TaskDetailComponent, ComponentContext by componentContext {

    private val scope = componentScope(coroutineContext)
    private val hydrating = MutableStateFlow(true)

    override val state: StateFlow<TaskDetailState> =
        combine(taskRepository.observeTask(taskId), hydrating) { task, isHydrating ->
            TaskDetailState(task = task, isHydrating = isHydrating)
        }.stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TaskDetailState(task = initialTask, isHydrating = true))

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

    override fun onSetWorkingState(target: WorkingState) {
        // Pass the currently-observed row as `current` so the executor's pre-flight gate can reject a
        // stale transition (the state the Task is already in) before any write — ADR-0007.
        val current = state.value.task
        scope.launch { workingStateEditor.setWorkingState(taskId, target, current) }
    }
}
