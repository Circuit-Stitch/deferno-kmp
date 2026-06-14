package com.circuitstitch.deferno.feature.tasks

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.childContext
import com.arkivanov.decompose.router.slot.ChildSlot
import com.arkivanov.decompose.router.slot.SlotNavigation
import com.arkivanov.decompose.router.slot.activate
import com.arkivanov.decompose.router.slot.childSlot
import com.arkivanov.decompose.router.slot.dismiss
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.circuitstitch.deferno.core.data.task.TaskRepository
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

/**
 * Which pane was **most recently brought to the foreground**. The slots ([TasksComponent.detail],
 * [TasksComponent.tree]) are co-resident — more than one can be open at once — so this records their
 * activation *recency* as shared navigation state. A single-pane View renders exactly this pane; a
 * two-pane View can ignore it, or use it to decide which co-resident slot fills its second pane. It
 * is owned by the component (not the View) so it survives configuration changes and so back-handling
 * and rendering read one source of truth.
 */
enum class TaskPane { List, Detail, Tree }

/**
 * The Tasks feature root (ADR-0007). It models the list, detail, and tree as **co-resident slots**,
 * not a push/pop stack: [list] is always present, while [detail] and [tree] are independent slots
 * that can each hold a child *alongside* the list (and each other). A native View reads 1 or 2 panes
 * from these by window size class; the navigation model never assumes a single visible screen.
 *
 * Navigation is intent-driven: child components emit `Output`s, which this root turns into slot
 * activation/dismissal (selecting a list row opens the detail slot; the detail's "show tree" opens
 * the tree slot; a tree child opens that child's detail). Each activation also updates [activePane]
 * (the recency a single-pane View renders / back dismisses). Cross-feature intents (add-to-plan) are
 * re-emitted via this component's own [Output] for the app host to route.
 */
interface TasksComponent {
    val list: TaskListComponent
    val detail: Value<ChildSlot<*, TaskDetailComponent>>
    val tree: Value<ChildSlot<*, TaskTreeComponent>>

    /** The most-recently-foregrounded pane (see [TaskPane]); updated as slots activate/dismiss. */
    val activePane: Value<TaskPane>

    sealed interface Output {
        data class AddToPlanRequested(val id: TaskId) : Output
    }
}

class DefaultTasksComponent(
    componentContext: ComponentContext,
    private val taskRepository: TaskRepository,
    private val output: (TasksComponent.Output) -> Unit = {},
    // The working-state write seam (#73), threaded down into the detail slot so the detail can issue
    // lifecycle Commands. Defaults to a no-op so existing shell/component tests build without it.
    private val workingStateEditor: WorkingStateEditor = WorkingStateEditor.NONE,
    private val coroutineContext: CoroutineContext = Dispatchers.Default,
) : TasksComponent, ComponentContext by componentContext {

    // Slot configs are plain data (serializer = null below → no state restoration wired in v1, so no
    // kotlinx.serialization dependency on the feature module). They key which child each slot holds.
    // initialTask seeds the detail's first frame from the row the opener already had in memory (no DB
    // read), so the title/body show immediately instead of popping in. Not persisted (serializer = null).
    private data class DetailConfig(val taskId: TaskId, val initialTask: Task? = null)
    private data class TreeConfig(val rootId: TaskId)

    private val detailNavigation = SlotNavigation<DetailConfig>()
    private val treeNavigation = SlotNavigation<TreeConfig>()

    private val _activePane = MutableValue(TaskPane.List)
    override val activePane: Value<TaskPane> = _activePane

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
                workingStateEditor = workingStateEditor,
                initialTask = config.initialTask,
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
            is TaskListComponent.Output.TaskSelected -> {
                val seed = list.state.value.tasks.firstOrNull { it.id == output.id }
                detailNavigation.activate(DetailConfig(output.id, seed))
                _activePane.value = TaskPane.Detail
            }
        }
    }

    private fun onDetailOutput(output: TaskDetailComponent.Output) {
        when (output) {
            TaskDetailComponent.Output.Closed -> {
                detailNavigation.dismiss()
                // The foreground pane closed — fall back to whatever co-resident slot remains.
                _activePane.value = if (tree.value.child != null) TaskPane.Tree else TaskPane.List
            }
            is TaskDetailComponent.Output.TreeRequested -> {
                treeNavigation.activate(TreeConfig(output.id))
                _activePane.value = TaskPane.Tree
            }
            is TaskDetailComponent.Output.AddToPlanRequested ->
                this.output(TasksComponent.Output.AddToPlanRequested(output.id))
        }
    }

    private fun onTreeOutput(output: TaskTreeComponent.Output) {
        when (output) {
            TaskTreeComponent.Output.Closed -> {
                treeNavigation.dismiss()
                _activePane.value = if (detail.value.child != null) TaskPane.Detail else TaskPane.List
            }
            // Drilling into a child opens its detail alongside the list (co-resident), not a new stack.
            is TaskTreeComponent.Output.ChildSelected -> {
                val seed = tree.value.child?.instance?.state?.value?.children?.firstOrNull { it.id == output.id }
                detailNavigation.activate(DetailConfig(output.id, seed))
                _activePane.value = TaskPane.Detail
            }
        }
    }
}
