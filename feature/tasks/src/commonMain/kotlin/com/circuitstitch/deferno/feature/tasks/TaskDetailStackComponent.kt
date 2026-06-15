package com.circuitstitch.deferno.feature.tasks

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.navigate
import com.arkivanov.decompose.router.stack.pop
import com.arkivanov.decompose.value.Value
import com.circuitstitch.deferno.core.data.task.TaskDetailRepository
import com.circuitstitch.deferno.core.data.task.TaskRepository
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext
import kotlin.time.Instant

/**
 * A self-contained **stack of Task detail pages** rooted at one [TaskId] (#196, ADR-0033) — the
 * navigation model for a detached macOS detail window. It is the Plan tier-3 detail stack
 * ([com.circuitstitch.deferno.shell] `MainShellComponent`) minus the Dashboard base: the root is a
 * [TaskDetailComponent] for the seeded Task, drilling a subtask **pushes** that child's detail, and
 * [onBack] **pops** back up. Each leaf reuses the enriched [DefaultTaskDetailComponent] (#195) built
 * over the same per-Account seams the shell uses, so a window edit and the main shell observe the one
 * driver's Flow and stay in sync (ADR-0033 — the live-session reuse).
 */
interface TaskDetailStackComponent {
    val stack: Value<ChildStack<*, TaskDetailComponent>>

    /** Pop one drilled level; returns false at the root (depth 1) so the window host can decide. */
    fun onBack(): Boolean
}

class DefaultTaskDetailStackComponent(
    componentContext: ComponentContext,
    rootId: TaskId,
    private val taskRepository: TaskRepository,
    // The in-memory summary the opener had on screen — seeds the root leaf's first frame so the title
    // shows immediately instead of popping in (mirrors DefaultTaskDetailComponent.initialTask).
    private val initialTask: Task? = null,
    private val workingStateEditor: WorkingStateEditor = WorkingStateEditor.NONE,
    private val detailRepository: TaskDetailRepository = TaskDetailRepository.NONE,
    private val createSubtask: suspend (TaskId, String) -> Unit = { _, _ -> },
    private val setDeadline: suspend (TaskId, Instant?) -> Unit = { _, _ -> },
    private val setLabels: suspend (TaskId, List<String>) -> Unit = { _, _ -> },
    private val coroutineContext: CoroutineContext = Dispatchers.Default,
) : TaskDetailStackComponent, ComponentContext by componentContext {

    // Plain-data config keying each detail page; initialTask only seeds the root (serializer = null →
    // no state restoration in v1, matching the Plan stack and the Tasks slots).
    private data class DetailConfig(val taskId: TaskId, val initialTask: Task? = null)

    private val navigation = StackNavigation<DetailConfig>()

    override val stack: Value<ChildStack<*, TaskDetailComponent>> =
        childStack(
            source = navigation,
            serializer = null,
            initialConfiguration = DetailConfig(rootId, initialTask),
            key = "TaskDetailStack",
            handleBackButton = false, // back is routed via onBack(), like the shell's Plan stack
            childFactory = { config, ctx ->
                DefaultTaskDetailComponent(
                    componentContext = ctx,
                    taskId = config.taskId,
                    taskRepository = taskRepository,
                    output = ::onDetailOutput,
                    workingStateEditor = workingStateEditor,
                    initialTask = config.initialTask,
                    detailRepository = detailRepository,
                    createSubtask = createSubtask,
                    setDeadline = setDeadline,
                    setLabels = setLabels,
                    coroutineContext = coroutineContext,
                )
            },
        )

    override fun onBack(): Boolean =
        if (stack.value.backStack.isNotEmpty()) {
            navigation.pop()
            true
        } else {
            false
        }

    private fun onDetailOutput(output: TaskDetailComponent.Output) {
        when (output) {
            // Drilling into a subtask pushes one level deeper (the window's only forward nav).
            is TaskDetailComponent.Output.SubtaskSelected ->
                navigation.navigate { it + DetailConfig(output.id) }
            // The detail's Close == this window's Back: pop a drilled level (a no-op at the root, where
            // the window's own chrome closes it).
            TaskDetailComponent.Output.Closed -> navigation.pop()
            // No tree screen or add-to-plan affordance inside the window — ignored.
            // ponytail: forward AddToPlanRequested through an output seam if the window ever exposes it.
            is TaskDetailComponent.Output.TreeRequested,
            is TaskDetailComponent.Output.AddToPlanRequested -> Unit
        }
    }
}
