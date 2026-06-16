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
import com.circuitstitch.deferno.core.data.item.ItemFoldStore
import com.circuitstitch.deferno.core.data.item.ItemRepository
import com.circuitstitch.deferno.core.data.task.TaskDetailRepository
import com.circuitstitch.deferno.core.data.task.TaskRepository
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext
import kotlin.time.Instant

/** Which pane is foregrounded — the Item [Tree] (the primary Tasks pane) or a Task [Detail]. */
enum class TaskPane { Tree, Detail }

/**
 * The Tasks feature root (ADR-0034). The Tasks Destination is the nested, collapsible **Item tree**
 * ([tree]) spanning all four kinds; selecting a row's trailing `›` opens its [detail] alongside (a
 * co-resident slot, ADR-0007). The old flat list + one-level drill pane are **subsumed** — a node's
 * children are now seen inline by expanding the tree, so there is no separate tree slot anymore.
 *
 * A native View reads 1 or 2 panes by window size class: the tree, plus the detail when open. Navigation
 * is intent-driven — the tree emits an `ItemSelected`, which this root turns into detail activation, and
 * the detail's subtask-drill re-keys the same slot. [activePane] is the recency a single-pane View renders
 * and back dismisses. Cross-feature intents (add-to-plan) are re-emitted via this component's [Output].
 */
interface TasksComponent {
    val tree: ItemTreeComponent
    val detail: Value<ChildSlot<*, TaskDetailComponent>>

    /** The most-recently-foregrounded pane (see [TaskPane]); updated as the detail slot activates/dismisses. */
    val activePane: Value<TaskPane>

    sealed interface Output {
        data class AddToPlanRequested(val id: TaskId) : Output
    }
}

class DefaultTasksComponent(
    componentContext: ComponentContext,
    // The cross-kind Item read + device-local fold store the tree pane renders (ADR-0034, #226/#227).
    private val itemRepository: ItemRepository,
    private val foldStore: ItemFoldStore,
    // The Task read seam the detail slot observes/hydrates (still Task-centric; detail is Task-only).
    private val taskRepository: TaskRepository,
    private val output: (TasksComponent.Output) -> Unit = {},
    // The working-state write seam (#73), threaded down into the detail slot so the detail can issue
    // lifecycle Commands. Defaults to a no-op so existing shell/component tests build without it.
    private val workingStateEditor: WorkingStateEditor = WorkingStateEditor.NONE,
    // The detail's online-only comments + attachments source + its "add subtask" create seam, threaded
    // down into the detail slot. Both default to no-ops so existing tests build without supplying them.
    private val taskDetailRepository: TaskDetailRepository = TaskDetailRepository.NONE,
    private val createSubtask: suspend (TaskId, String) -> Unit = { _, _ -> },
    // The detail's editable-PROPERTIES write seams (DUE date + LABELS), threaded down into the detail
    // slot. Both default to no-ops so existing tests/callers build without supplying them.
    private val setDeadline: suspend (TaskId, Instant?) -> Unit = { _, _ -> },
    private val setLabels: suspend (TaskId, List<String>) -> Unit = { _, _ -> },
    // The detail's on-device attachment seam (#211), threaded down into the detail slot. Defaults to the
    // empty NONE so existing tests/callers build without it.
    private val onDeviceAttachments: OnDeviceAttachments = OnDeviceAttachments.NONE,
    private val coroutineContext: CoroutineContext = Dispatchers.Default,
) : TasksComponent, ComponentContext by componentContext {

    // initialTask seeds the detail's first frame from a row the opener already had in memory (no DB read).
    // The tree opens by id only (its rows are the cross-kind Item projection, not full Tasks), so a
    // tree-opened detail has no seed and its title pops in one frame later; a subtask-drill still seeds.
    private data class DetailConfig(val taskId: TaskId, val initialTask: Task? = null)

    private val detailNavigation = SlotNavigation<DetailConfig>()

    private val _activePane = MutableValue(TaskPane.Tree)
    override val activePane: Value<TaskPane> = _activePane

    override val tree: ItemTreeComponent =
        DefaultItemTreeComponent(
            componentContext = childContext(key = "tree"),
            itemRepository = itemRepository,
            foldStore = foldStore,
            output = ::onTreeOutput,
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
                detailRepository = taskDetailRepository,
                createSubtask = createSubtask,
                setDeadline = setDeadline,
                setLabels = setLabels,
                onDeviceAttachments = onDeviceAttachments,
                foldStore = foldStore,
                coroutineContext = coroutineContext,
            )
        }

    private fun onTreeOutput(output: ItemTreeComponent.Output) {
        when (output) {
            is ItemTreeComponent.Output.ItemSelected -> {
                detailNavigation.activate(DetailConfig(output.id))
                _activePane.value = TaskPane.Detail
            }
        }
    }

    private fun onDetailOutput(output: TaskDetailComponent.Output) {
        when (output) {
            TaskDetailComponent.Output.Closed -> {
                detailNavigation.dismiss()
                _activePane.value = TaskPane.Tree
            }
            // Tapping a subtask re-keys the detail slot to that child (inline drill-in). Seed from the
            // detail's in-memory subtask outline (the visible row it just tapped) so the re-keyed title
            // shows now.
            is TaskDetailComponent.Output.SubtaskSelected -> {
                val seed = detail.value.child?.instance?.state?.value
                    ?.subtaskRows?.firstOrNull { it.task.id == output.id }?.task
                detailNavigation.activate(DetailConfig(output.id, seed))
                _activePane.value = TaskPane.Detail
            }
            is TaskDetailComponent.Output.AddToPlanRequested ->
                this.output(TasksComponent.Output.AddToPlanRequested(output.id))
        }
    }
}
