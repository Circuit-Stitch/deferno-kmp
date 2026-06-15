package com.circuitstitch.deferno.feature.tasks

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.circuitstitch.deferno.core.model.TaskId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private fun TestScope.tasksComponent(
    repo: FakeTaskRepository,
    output: (TasksComponent.Output) -> Unit = {},
) = DefaultTasksComponent(
    componentContext = DefaultComponentContext(LifecycleRegistry()),
    taskRepository = repo,
    output = output,
    coroutineContext = StandardTestDispatcher(testScheduler),
)

/** The co-resident slot model (ADR-0007): list is always present; detail and tree are slots that can
 *  hold a child alongside it (and each other), driven by intents — never a push/pop stack. */
@OptIn(ExperimentalCoroutinesApi::class) // advanceUntilIdle() — let the detail build its subtask tree
class TasksComponentTest {

    private fun repo() = FakeTaskRepository(
        listOf(
            task("root"),
            task("c1", parentId = "root", sequence = 1),
            task("b"),
        ),
    )

    @Test
    fun listSelectionOpensTheCoResidentDetailSlot() = runTest {
        val component = tasksComponent(repo())

        assertNull(component.detail.value.child, "no detail before a selection")

        component.list.onTaskClicked(TaskId("root"))

        // detail is now active *alongside* the always-present list — co-resident, not a replacement.
        assertEquals(TaskId("root"), component.detail.value.child?.instance?.taskId)
        assertNotNull(component.list)

        // selecting another task re-points the same slot
        component.list.onTaskClicked(TaskId("b"))
        assertEquals(TaskId("b"), component.detail.value.child?.instance?.taskId)
    }

    @Test
    fun detailOpensTheTreeSlotCoResidentlyThenBothDismiss() = runTest {
        val component = tasksComponent(repo())
        component.list.onTaskClicked(TaskId("root"))
        val detail = assertNotNull(component.detail.value.child?.instance)

        detail.onShowTreeClicked()

        // list + detail + tree are all co-resident now.
        assertEquals(TaskId("root"), component.tree.value.child?.instance?.rootId)
        assertNotNull(component.detail.value.child, "detail stays open alongside the tree")

        component.tree.value.child?.instance?.onCloseClicked()
        assertNull(component.tree.value.child)

        detail.onCloseClicked()
        assertNull(component.detail.value.child)
    }

    @Test
    fun selectingATreeChildOpensThatChildsDetail() = runTest {
        val component = tasksComponent(repo())
        component.list.onTaskClicked(TaskId("root"))
        component.detail.value.child?.instance?.onShowTreeClicked()

        component.tree.value.child?.instance?.onChildClicked(TaskId("c1"))

        assertEquals(TaskId("c1"), component.detail.value.child?.instance?.taskId)
    }

    @Test
    fun drillingIntoAnInlineSubtaskSeedsTheReKeyedDetail() = runTest {
        val component = tasksComponent(repo())
        component.list.onTaskClicked(TaskId("root"))
        val parent = assertNotNull(component.detail.value.child?.instance)
        // The parent detail is on screen (subscribed) when its subtask is tapped, so its subtask tree is
        // built — mirror that here so the seed has a row to hand over (WhileSubscribed needs a collector).
        backgroundScope.launch { parent.state.collect {} }
        advanceUntilIdle()

        parent.onSubtaskClicked(TaskId("c1"))

        val child = assertNotNull(component.detail.value.child?.instance)
        assertEquals(TaskId("c1"), child.taskId)
        // Seeded from the parent detail's in-memory subtask tree → the row (and its title) is on the
        // first frame, before any collection of the re-keyed detail's flow.
        assertEquals(TaskId("c1"), child.state.value.task?.id)
    }

    @Test
    fun activePaneTracksTheMostRecentlyForegroundedSlotAcrossADrillIn() = runTest {
        val component = tasksComponent(repo())
        assertEquals(TaskPane.List, component.activePane.value, "starts on the list")

        component.list.onTaskClicked(TaskId("root"))
        assertEquals(TaskPane.Detail, component.activePane.value)

        component.detail.value.child?.instance?.onShowTreeClicked()
        assertEquals(TaskPane.Tree, component.activePane.value)

        // Drilling into a tree child re-foregrounds detail (the #27 single-pane drill-in invariant),
        // even though the tree slot stays co-resident behind it.
        component.tree.value.child?.instance?.onChildClicked(TaskId("c1"))
        assertEquals(TaskPane.Detail, component.activePane.value)
        assertNotNull(component.tree.value.child, "tree stays open beneath the child's detail")

        // Closing the foreground (child) detail reveals the still-open tree — not the list.
        component.detail.value.child?.instance?.onCloseClicked()
        assertEquals(TaskPane.Tree, component.activePane.value)

        // Closing the tree (nothing else open) falls back to the list.
        component.tree.value.child?.instance?.onCloseClicked()
        assertEquals(TaskPane.List, component.activePane.value)
    }

    @Test
    fun addToPlanIntentBubblesUpToTheHost() = runTest {
        val outputs = mutableListOf<TasksComponent.Output>()
        val component = tasksComponent(repo(), outputs::add)
        component.list.onTaskClicked(TaskId("root"))

        component.detail.value.child?.instance?.onAddToPlanClicked()

        assertEquals(listOf<TasksComponent.Output>(TasksComponent.Output.AddToPlanRequested(TaskId("root"))), outputs)
    }
}
