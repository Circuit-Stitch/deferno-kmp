package com.circuitstitch.deferno.feature.tasks

import app.cash.turbine.test
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.circuitstitch.deferno.core.model.TaskId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

private fun TestScope.taskListComponent(
    repo: FakeTaskRepository,
    output: (TaskListComponent.Output) -> Unit = {},
) = DefaultTaskListComponent(
    componentContext = DefaultComponentContext(LifecycleRegistry()),
    taskRepository = repo,
    output = output,
    coroutineContext = StandardTestDispatcher(testScheduler),
)

@OptIn(ExperimentalCoroutinesApi::class) // advanceUntilIdle() — drives the scheduler past the init fetch.
class TaskListComponentTest {

    @Test
    fun stateReflectsTheRepositoryTaskList() = runTest {
        val repo = FakeTaskRepository()
        val component = taskListComponent(repo)

        component.state.test {
            assertEquals(emptyList(), awaitItem().tasks) // WhileSubscribed initial value
            repo.tasks.value = listOf(task("a"), task("b"))
            assertEquals(listOf(TaskId("a"), TaskId("b")), awaitItem().tasks.map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun refreshPullsThroughToTheLocalList() = runTest {
        val repo = FakeTaskRepository().apply { refreshSnapshot = listOf(task("x")) }
        val component = taskListComponent(repo)

        component.onRefresh()
        advanceUntilIdle()

        assertEquals(1, repo.refreshCount)
        assertEquals(listOf(TaskId("x")), repo.tasks.value.map { it.id })
    }

    @Test
    fun tappingARowEmitsTaskSelectedIntent() = runTest {
        val outputs = mutableListOf<TaskListComponent.Output>()
        val component = taskListComponent(FakeTaskRepository(), outputs::add)

        component.onTaskClicked(TaskId("a"))

        assertEquals(listOf<TaskListComponent.Output>(TaskListComponent.Output.TaskSelected(TaskId("a"))), outputs)
    }
}
