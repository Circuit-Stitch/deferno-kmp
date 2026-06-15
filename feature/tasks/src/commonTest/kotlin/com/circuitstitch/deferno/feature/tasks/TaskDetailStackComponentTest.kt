package com.circuitstitch.deferno.feature.tasks

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.circuitstitch.deferno.core.model.TaskId
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The detached-window detail stack (#196, ADR-0033): seeded at a root, subtasks push, back pops. */
class TaskDetailStackComponentTest {

    private fun TestScope.stackComponent(rootId: TaskId) =
        DefaultTaskDetailStackComponent(
            componentContext = DefaultComponentContext(LifecycleRegistry()),
            rootId = rootId,
            taskRepository = FakeTaskRepository(
                listOf(task("root"), task("c1", parentId = "root"), task("c2", parentId = "c1")),
            ),
            coroutineContext = StandardTestDispatcher(testScheduler),
        )

    @Test
    fun seededAtRootWithNoBackStack() = runTest {
        val c = stackComponent(TaskId("root"))

        assertEquals(TaskId("root"), c.stack.value.active.instance.taskId)
        assertTrue(c.stack.value.backStack.isEmpty())
        assertFalse(c.onBack(), "no back at the root — the host decides what to do")
    }

    @Test
    fun drillingASubtaskPushesItsDetail() = runTest {
        val c = stackComponent(TaskId("root"))

        c.stack.value.active.instance.onSubtaskClicked(TaskId("c1"))

        assertEquals(TaskId("c1"), c.stack.value.active.instance.taskId)
        assertEquals(TaskId("root"), c.stack.value.backStack.single().instance.taskId)
    }

    @Test
    fun closingADrilledDetailPopsToItsParent() = runTest {
        val c = stackComponent(TaskId("root"))
        c.stack.value.active.instance.onSubtaskClicked(TaskId("c1"))

        c.stack.value.active.instance.onCloseClicked()

        assertEquals(TaskId("root"), c.stack.value.active.instance.taskId)
        assertTrue(c.stack.value.backStack.isEmpty())
    }

    @Test
    fun onBackPopsOneLevelThenReportsRoot() = runTest {
        val c = stackComponent(TaskId("root"))
        c.stack.value.active.instance.onSubtaskClicked(TaskId("c1"))

        assertTrue(c.onBack())
        assertEquals(TaskId("root"), c.stack.value.active.instance.taskId)
        assertFalse(c.onBack())
    }

    @Test
    fun drillsToArbitraryDepthAndWalksBackUp() = runTest {
        val c = stackComponent(TaskId("root"))
        c.stack.value.active.instance.onSubtaskClicked(TaskId("c1"))
        c.stack.value.active.instance.onSubtaskClicked(TaskId("c2"))

        assertEquals(TaskId("c2"), c.stack.value.active.instance.taskId)
        assertEquals(2, c.stack.value.backStack.size)

        c.onBack()
        c.onBack()
        assertEquals(TaskId("root"), c.stack.value.active.instance.taskId)
    }
}
