package com.circuitstitch.deferno.feature.tasks

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.circuitstitch.deferno.core.data.item.InMemoryItemFoldStore
import com.circuitstitch.deferno.core.model.Item
import com.circuitstitch.deferno.core.model.ItemKind
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
    items: FakeItemRepository,
    taskRepo: FakeTaskRepository,
    output: (TasksComponent.Output) -> Unit = {},
) = DefaultTasksComponent(
    componentContext = DefaultComponentContext(LifecycleRegistry()),
    itemRepository = items,
    foldStore = InMemoryItemFoldStore(),
    taskRepository = taskRepo,
    output = output,
    coroutineContext = StandardTestDispatcher(testScheduler),
)

/**
 * The Tasks root after ADR-0034: the Item [tree] is the primary pane; a row's trailing `›` opens the
 * co-resident [detail] slot (intent-driven, never a push/pop stack), and the detail's subtask drill
 * re-keys that same slot. The old flat-list + one-level drill pane are gone.
 */
@OptIn(ExperimentalCoroutinesApi::class) // advanceUntilIdle() — let the detail build its subtask tree
class TasksComponentTest {

    // The Item tree (cross-kind) and the Task detail read from two seams that agree on ids: the tree
    // surfaces Items; opening a Task row's detail observes that TaskId in the Task store.
    private fun items() = FakeItemRepository(
        listOf(
            Item(id = "root", kind = ItemKind.Task, title = "root", sequence = 0),
            Item(id = "c1", kind = ItemKind.Task, title = "c1", parentId = "root", sequence = 1),
            Item(id = "b", kind = ItemKind.Task, title = "b", sequence = 2),
            Item(id = "h", kind = ItemKind.Habit, title = "h", sequence = 3),
        ),
    )

    private fun tasks() = FakeTaskRepository(
        listOf(task("root"), task("c1", parentId = "root", sequence = 1), task("b")),
    )

    @Test
    fun openingATaskRowOpensTheCoResidentDetailSlot() = runTest {
        val component = tasksComponent(items(), tasks())

        assertNull(component.detail.value.child, "no detail before a selection")

        component.tree.onOpenDetail("root", ItemKind.Task)

        // detail is now active *alongside* the always-present tree — co-resident, not a replacement.
        assertEquals(TaskId("root"), component.detail.value.child?.instance?.taskId)
        assertNotNull(component.tree)

        // opening another row re-points the same slot
        component.tree.onOpenDetail("b", ItemKind.Task)
        assertEquals(TaskId("b"), component.detail.value.child?.instance?.taskId)
    }

    @Test
    fun openingANonTaskRowDoesNotOpenDetail() = runTest {
        val component = tasksComponent(items(), tasks())

        component.tree.onOpenDetail("h", ItemKind.Habit)

        assertNull(component.detail.value.child, "no detail surface for a Habit yet (ADR-0034 fast-follow)")
    }

    @Test
    fun drillingIntoAnInlineSubtaskSeedsTheReKeyedDetail() = runTest {
        val component = tasksComponent(items(), tasks())
        component.tree.onOpenDetail("root", ItemKind.Task)
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
    fun activePaneTracksTheForegroundedPane() = runTest {
        val component = tasksComponent(items(), tasks())
        assertEquals(TaskPane.Tree, component.activePane.value, "starts on the tree")

        component.tree.onOpenDetail("root", ItemKind.Task)
        assertEquals(TaskPane.Detail, component.activePane.value)

        // Closing the detail falls back to the tree (the only other pane now).
        component.detail.value.child?.instance?.onCloseClicked()
        assertEquals(TaskPane.Tree, component.activePane.value)
        assertNull(component.detail.value.child)
    }

    @Test
    fun addToPlanIntentBubblesUpToTheHost() = runTest {
        val outputs = mutableListOf<TasksComponent.Output>()
        val component = tasksComponent(items(), tasks(), outputs::add)
        component.tree.onOpenDetail("root", ItemKind.Task)

        component.detail.value.child?.instance?.onAddToPlanClicked()

        assertEquals(listOf<TasksComponent.Output>(TasksComponent.Output.AddToPlanRequested(TaskId("root"))), outputs)
    }
}
