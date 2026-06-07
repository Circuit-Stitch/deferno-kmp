package com.circuitstitch.deferno.demo

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.circuitstitch.deferno.core.model.TaskId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The demo host's cross-feature routing (#27) — the one bit of custom logic in the throwaway host.
 * Decompose slot/tab state updates synchronously, so these assertions need no coroutine plumbing.
 */
class DemoComponentTest {

    private fun root() = DemoComponent(DefaultComponentContext(LifecycleRegistry()))

    @Test
    fun opensIntoThePlan() {
        assertEquals(DemoTab.Plan, root().selectedTab.value)
    }

    @Test
    fun planTapOpensThatTaskInTheTasksPane() {
        val root = root()

        root.plan.onTaskClicked(TaskId("t-1"))

        assertEquals(DemoTab.Tasks, root.selectedTab.value)
        assertEquals(TaskId("t-1"), root.tasks.detail.value.child?.instance?.taskId)
    }

    @Test
    fun backDismissesTheActiveSlotThenFallsBackToThePlan() {
        val root = root()
        root.plan.onTaskClicked(TaskId("t-1")) // Tasks tab, detail slot open

        assertTrue("dismisses the open detail", root.handleBack())
        assertNull(root.tasks.detail.value.child)
        assertEquals(DemoTab.Tasks, root.selectedTab.value)

        assertTrue("no slot left → return to the Plan home", root.handleBack())
        assertEquals(DemoTab.Plan, root.selectedTab.value)

        assertFalse("on the Plan home with nothing to dismiss → not consumed", root.handleBack())
    }

    @Test
    fun backDismissesTheForegroundedPaneAfterDrillingIntoATreeChild() {
        val root = root()
        root.plan.onTaskClicked(TaskId("t-1")) // Tasks tab, detail 't-1' (a task with children)
        root.tasks.detail.value.child?.instance?.onShowTreeClicked() // open its tree
        root.tasks.tree.value.child?.instance?.onChildClicked(TaskId("t-1a")) // drill into a child

        // Foreground is the child's detail, with the tree still co-resident behind it.
        assertEquals(TaskId("t-1a"), root.tasks.detail.value.child?.instance?.taskId)
        assertNotNull("tree stays open beneath the child detail", root.tasks.tree.value.child)

        // First back closes the *foregrounded* child detail and reveals the tree — not a dead press
        // dismissing the hidden tree (the static-precedence bug this guards against).
        assertTrue("back closes the foreground child detail", root.handleBack())
        assertNull("child detail dismissed", root.tasks.detail.value.child)
        assertNotNull("tree now revealed", root.tasks.tree.value.child)

        // Second back closes the tree, leaving the list on the Tasks tab.
        assertTrue("back closes the tree", root.handleBack())
        assertNull(root.tasks.tree.value.child)
        assertEquals(DemoTab.Tasks, root.selectedTab.value)
    }
}
