package com.circuitstitch.deferno.feature.tasks

import com.arkivanov.decompose.ComponentContext
import com.circuitstitch.deferno.core.common.componentScope
import com.circuitstitch.deferno.core.data.task.TaskRepository
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlin.coroutines.CoroutineContext

/**
 * Observable state for the Task tree pane: the root Task and its direct children, resolved live from
 * the repository. Deeper levels are reached by selecting a child (which opens that node's own detail
 * /tree) — the "decompose to defeat paralysis" model (design-principles.md), one level at a time.
 */
data class TaskTreeState(
    val root: Task? = null,
    val children: List<Task> = emptyList(),
)

/**
 * The Task tree component (ADR-0007: a co-resident pane showing a Task's breakdown). Resolves the
 * root's children from the live Task list by `parentId`, ordered by `sequence`. Selecting a child is
 * an [Output.ChildSelected] intent the parent turns into navigation (open the child's detail slot).
 */
interface TaskTreeComponent {
    val rootId: TaskId
    val state: StateFlow<TaskTreeState>

    fun onChildClicked(id: TaskId)
    fun onCloseClicked()

    sealed interface Output {
        data object Closed : Output
        data class ChildSelected(val id: TaskId) : Output
    }
}

class DefaultTaskTreeComponent(
    componentContext: ComponentContext,
    override val rootId: TaskId,
    private val taskRepository: TaskRepository,
    private val output: (TaskTreeComponent.Output) -> Unit,
    coroutineContext: CoroutineContext = Dispatchers.Default,
) : TaskTreeComponent, ComponentContext by componentContext {

    private val scope = componentScope(coroutineContext)

    override val state: StateFlow<TaskTreeState> =
        taskRepository.observeTasks().map { all ->
            TaskTreeState(
                root = all.firstOrNull { it.id == rootId },
                children = all.filter { it.parentId == rootId }.sortedBy { it.sequence },
            )
        }.stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TaskTreeState())

    override fun onChildClicked(id: TaskId) {
        output(TaskTreeComponent.Output.ChildSelected(id))
    }

    override fun onCloseClicked() {
        output(TaskTreeComponent.Output.Closed)
    }
}
