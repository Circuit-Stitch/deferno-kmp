package com.circuitstitch.deferno.feature.tasks

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.childContext
import com.arkivanov.decompose.router.slot.ChildSlot
import com.arkivanov.decompose.router.slot.SlotNavigation
import com.arkivanov.decompose.router.slot.activate
import com.arkivanov.decompose.router.slot.childSlot
import com.arkivanov.decompose.router.slot.dismiss
import com.arkivanov.decompose.value.Value
import com.circuitstitch.deferno.core.data.task.TaskRepository
import com.circuitstitch.deferno.core.model.TaskId
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

/**
 * The Tasks feature root (ADR-0007). It models the list, detail, and tree as **co-resident slots**,
 * not a push/pop stack: [list] is always present, while [detail] and [tree] are independent slots
 * that can each hold a child *alongside* the list (and each other). A native View reads 1 or 2 panes
 * from these by window size class; the navigation model never assumes a single visible screen.
 *
 * Navigation is intent-driven: child components emit `Output`s, which this root turns into slot
 * activation/dismissal (selecting a list row opens the detail slot; the detail's "show tree" opens
 * the tree slot; a tree child opens that child's detail). Cross-feature intents (add-to-plan) are
 * re-emitted via this component's own [Output] for the app host to route.
 */
interface TasksComponent {
    val list: TaskListComponent
    val detail: Value<ChildSlot<*, TaskDetailComponent>>
    val tree: Value<ChildSlot<*, TaskTreeComponent>>

    sealed interface Output {
        data class AddToPlanRequested(val id: TaskId) : Output
    }
}

class DefaultTasksComponent(
    componentContext: ComponentContext,
    private val taskRepository: TaskRepository,
    private val output: (TasksComponent.Output) -> Unit = {},
    private val coroutineContext: CoroutineContext = Dispatchers.Default,
) : TasksComponent, ComponentContext by componentContext {

    // Slot configs are plain data (serializer = null below → no state restoration wired in v1, so no
    // kotlinx.serialization dependency on the feature module). They key which child each slot holds.
    private data class DetailConfig(val taskId: TaskId)
    private data class TreeConfig(val rootId: TaskId)

    private val detailNavigation = SlotNavigation<DetailConfig>()
    private val treeNavigation = SlotNavigation<TreeConfig>()

    override val list: TaskListComponent =
        DefaultTaskListComponent(
            componentContext = childContext(key = "list"),
            taskRepository = taskRepository,
            output = ::onListOutput,
            coroutineContext = coroutineContext,
        )

    override val detail: Value<ChildSlot<*, TaskDetailComponent>> =
        childSlot(
            source = detailNavigation,
            serializer = null,
            key = "detail",
            handleBackButton = false,
        ) { config, childContext ->
            DefaultTaskDetailComponent(
                componentContext = childContext,
                taskId = config.taskId,
                taskRepository = taskRepository,
                output = ::onDetailOutput,
                coroutineContext = coroutineContext,
            )
        }

    override val tree: Value<ChildSlot<*, TaskTreeComponent>> =
        childSlot(
            source = treeNavigation,
            serializer = null,
            key = "tree",
            handleBackButton = false,
        ) { config, childContext ->
            DefaultTaskTreeComponent(
                componentContext = childContext,
                rootId = config.rootId,
                taskRepository = taskRepository,
                output = ::onTreeOutput,
                coroutineContext = coroutineContext,
            )
        }

    private fun onListOutput(output: TaskListComponent.Output) {
        when (output) {
            is TaskListComponent.Output.TaskSelected -> detailNavigation.activate(DetailConfig(output.id))
        }
    }

    private fun onDetailOutput(output: TaskDetailComponent.Output) {
        when (output) {
            TaskDetailComponent.Output.Closed -> detailNavigation.dismiss()
            is TaskDetailComponent.Output.TreeRequested -> treeNavigation.activate(TreeConfig(output.id))
            is TaskDetailComponent.Output.AddToPlanRequested ->
                this.output(TasksComponent.Output.AddToPlanRequested(output.id))
        }
    }

    private fun onTreeOutput(output: TaskTreeComponent.Output) {
        when (output) {
            TaskTreeComponent.Output.Closed -> treeNavigation.dismiss()
            // Drilling into a child opens its detail alongside the list (co-resident), not a new stack.
            is TaskTreeComponent.Output.ChildSelected -> detailNavigation.activate(DetailConfig(output.id))
        }
    }
}
