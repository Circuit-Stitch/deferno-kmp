package com.circuitstitch.deferno.feature.tasks

import app.cash.turbine.test
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.circuitstitch.deferno.core.model.HydrationState
import com.circuitstitch.deferno.core.model.TaskId
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

private fun TestScope.taskDetailComponent(
    id: TaskId,
    repo: FakeTaskRepository,
    output: (TaskDetailComponent.Output) -> Unit = {},
) = DefaultTaskDetailComponent(
    componentContext = DefaultComponentContext(LifecycleRegistry()),
    taskId = id,
    taskRepository = repo,
    output = output,
    coroutineContext = StandardTestDispatcher(testScheduler),
)

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
            assertEquals("the details", item.task?.description)
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(listOf(TaskId("a")), repo.hydrateCalls)
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
}
