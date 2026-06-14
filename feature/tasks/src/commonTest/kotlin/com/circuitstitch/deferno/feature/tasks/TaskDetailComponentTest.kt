package com.circuitstitch.deferno.feature.tasks

import app.cash.turbine.test
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.circuitstitch.deferno.core.model.HydrationState
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun TestScope.taskDetailComponent(
    id: TaskId,
    repo: FakeTaskRepository,
    output: (TaskDetailComponent.Output) -> Unit = {},
    editor: WorkingStateEditor = WorkingStateEditor.NONE,
    initialTask: Task? = null,
) = DefaultTaskDetailComponent(
    componentContext = DefaultComponentContext(LifecycleRegistry()),
    taskId = id,
    taskRepository = repo,
    output = output,
    workingStateEditor = editor,
    initialTask = initialTask,
    coroutineContext = StandardTestDispatcher(testScheduler),
)

/** Records the working-state edits the detail issues and applies them to [repo]'s flow (optimistic). */
private class RecordingEditor(private val repo: FakeTaskRepository) : WorkingStateEditor {
    val calls = mutableListOf<Triple<TaskId, WorkingState, Task?>>()
    override suspend fun setWorkingState(id: TaskId, target: WorkingState, current: Task?) {
        calls += Triple(id, target, current)
        // Mirror the executor's pre-flight gate: a no-op transition writes nothing (ADR-0007).
        if (current?.workingState == target) return
        repo.tasks.update { list -> list.map { if (it.id == id) it.copy(workingState = target) else it } }
    }
}

@OptIn(ExperimentalCoroutinesApi::class) // advanceUntilIdle() — drives the scheduler past the init fetch.
class TaskDetailComponentTest {

    @Test
    fun hydratesOnCreationAndExposesTheFullTask() = runTest {
        val summary = task("a", title = "A")
        val full = task("a", title = "A", hydration = HydrationState.Full, description = "the details")
        val repo = FakeTaskRepository(listOf(summary)).apply { hydrateResults = mapOf(TaskId("a") to full) }
        val component = taskDetailComponent(TaskId("a"), repo)

        component.state.test {
            // Drain to the settled state: hydrate has upgraded the row and finished.
            var item = awaitItem()
            while (item.isHydrating || item.task?.hydration != HydrationState.Full) item = awaitItem()
            assertEquals("the details", item.task.description)
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(listOf(TaskId("a")), repo.hydrateCalls)
    }

    @Test
    fun seedsTheInitialTaskSoTheTitleIsAvailableBeforeTheRowIsObserved() = runTest {
        // The opener hands over the summary it already had on screen: state exposes it on the very first
        // value (before any collection), so the pane's title/body render immediately — no async pop-in.
        val seed = task("a", title = "Seeded")
        val component = taskDetailComponent(TaskId("a"), FakeTaskRepository(listOf(seed)), initialTask = seed)

        assertEquals("Seeded", component.state.value.task?.title)
        assertTrue(component.state.value.isHydrating) // still enriches summary → full in the background
    }

    @Test
    fun closeShowTreeAndAddToPlanEmitIntents() = runTest {
        val outputs = mutableListOf<TaskDetailComponent.Output>()
        val component = taskDetailComponent(TaskId("a"), FakeTaskRepository(listOf(task("a"))), outputs::add)
        advanceUntilIdle() // let the init hydrate settle so it doesn't interleave

        component.onShowTreeClicked()
        component.onAddToPlanClicked()
        component.onCloseClicked()

        assertEquals(
            listOf(
                TaskDetailComponent.Output.TreeRequested(TaskId("a")),
                TaskDetailComponent.Output.AddToPlanRequested(TaskId("a")),
                TaskDetailComponent.Output.Closed,
            ),
            outputs,
        )
    }

    @Test
    fun setWorkingStateIssuesTheEditWithTheCurrentRowAndUpdatesOptimistically() = runTest {
        val repo = FakeTaskRepository(listOf(task("a", workingState = WorkingState.Open)))
        val editor = RecordingEditor(repo)
        val component = taskDetailComponent(TaskId("a"), repo, editor = editor)
        advanceUntilIdle() // settle hydrate + the initial state emission

        component.state.test {
            // Drain to the observed Open row.
            var item = awaitItem()
            while (item.task?.workingState != WorkingState.Open) item = awaitItem()

            component.onSetWorkingState(WorkingState.InProgress)
            advanceUntilIdle()

            // The edit carried the currently-observed row as `current` for the pre-flight gate.
            assertEquals(1, editor.calls.size)
            assertEquals(TaskId("a"), editor.calls.single().first)
            assertEquals(WorkingState.InProgress, editor.calls.single().second)
            assertEquals(WorkingState.Open, editor.calls.single().third?.workingState)

            // The optimistic local update propagates back through the repository Flow.
            var updated = awaitItem()
            while (updated.task?.workingState != WorkingState.InProgress) updated = awaitItem()
            assertEquals(WorkingState.InProgress, updated.task.workingState)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun aStaleTransitionWritesNothing() = runTest {
        val repo = FakeTaskRepository(listOf(task("a", workingState = WorkingState.InProgress)))
        val editor = RecordingEditor(repo)
        val component = taskDetailComponent(TaskId("a"), repo, editor = editor)
        advanceUntilIdle()

        // Setting the state the Task is already in: the editor is asked, but the gate makes it a no-op.
        component.onSetWorkingState(WorkingState.InProgress)
        advanceUntilIdle()

        assertEquals(1, editor.calls.size)
        assertEquals(WorkingState.InProgress, repo.tasks.value.single().workingState) // unchanged
    }
}
