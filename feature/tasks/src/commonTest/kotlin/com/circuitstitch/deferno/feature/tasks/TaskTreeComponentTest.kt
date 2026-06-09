package com.circuitstitch.deferno.feature.tasks

import app.cash.turbine.test
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.circuitstitch.deferno.core.model.TaskId
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

private fun TestScope.taskTreeComponent(
    rootId: TaskId,
    repo: FakeTaskRepository,
    output: (TaskTreeComponent.Output) -> Unit = {},
) = DefaultTaskTreeComponent(
    componentContext = DefaultComponentContext(LifecycleRegistry()),
    rootId = rootId,
    taskRepository = repo,
    output = output,
    coroutineContext = StandardTestDispatcher(testScheduler),
)

class TaskTreeComponentTest {

    @Test
    fun resolvesDirectChildrenOrderedBySequence() = runTest {
        val repo = FakeTaskRepository(
            listOf(
                task("root"),
                task("c2", parentId = "root", sequence = 2),
                task("c1", parentId = "root", sequence = 1),
                task("elsewhere", parentId = "other"),
            ),
        )
        val component = taskTreeComponent(TaskId("root"), repo)

        component.state.test {
            var item = awaitItem()
            while (item.root == null) item = awaitItem()
            assertEquals(TaskId("root"), item.root.id)
            assertEquals(listOf(TaskId("c1"), TaskId("c2")), item.children.map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun childAndCloseEmitIntents() = runTest {
        val outputs = mutableListOf<TaskTreeComponent.Output>()
        val component = taskTreeComponent(TaskId("root"), FakeTaskRepository(listOf(task("root"))), outputs::add)

        component.onChildClicked(TaskId("c1"))
        component.onCloseClicked()

        assertEquals(
            listOf(
                TaskTreeComponent.Output.ChildSelected(TaskId("c1")),
                TaskTreeComponent.Output.Closed,
            ),
            outputs,
        )
    }
}
