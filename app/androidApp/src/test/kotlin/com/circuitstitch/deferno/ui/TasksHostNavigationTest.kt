package com.circuitstitch.deferno.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.circuitstitch.deferno.core.designsystem.theme.DefernoTheme
import com.circuitstitch.deferno.demo.DemoTaskRepository
import com.circuitstitch.deferno.feature.tasks.DefaultTasksComponent
import com.circuitstitch.deferno.feature.tasks.ui.TasksScreen
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression test for the single-pane drill-in bug (#27, review findings #1+#2). The detail and tree
 * slots are **co-resident** (both can be open at once, ready for a 2-pane View), so the single-pane
 * host must foreground the *most-recently-opened* pane rather than use a static tree>detail order.
 * Drilling from the tree into a child opens the child's detail while the tree slot stays open; a naive
 * tree-wins precedence would keep the tree on top and the child tap would look like it did nothing.
 *
 * Pinned to a **compact** width (`@Config`) so the adaptive `TasksScreen` (#29) folds to a single Pane
 * — the regression this guards lives in that fold; the two-pane behaviour is covered by
 * [TasksAdaptivePaneTest]. It drives a real [DefaultTasksComponent] over an in-memory repository on
 * [Dispatchers.Unconfined] (so the state flows resolve synchronously) through the rendered UI.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h800dp")
@OptIn(ExperimentalTestApi::class)
class TasksHostNavigationTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun drillingIntoTreeChild_foregroundsThatChildsDetail() {
        val parent = sampleTask("p", "Parent task", children = listOf("c"))
        val child = sampleTask("c", "Child step", parentId = "p", sequence = 1)
        val root = DefaultTasksComponent(
            componentContext = DefaultComponentContext(LifecycleRegistry()),
            taskRepository = DemoTaskRepository(listOf(parent, child)),
            coroutineContext = Dispatchers.Unconfined,
        )

        composeRule.setContent { DefernoTheme { TasksScreen(root) } }

        // List → open the parent's detail → open its breakdown (tree) → drill into the child.
        composeRule.onNodeWithText("Parent task").performClick()
        composeRule.onNodeWithText("Show its 1 step").performClick()
        composeRule.onNodeWithText("Child step").performClick()

        // The child's *detail* is now foregrounded (not the still-open tree): its title header and the
        // detail-only action are shown. Pre-fix, the tree stayed on top and neither appeared.
        composeRule.onNodeWithText("Child step").assertIsDisplayed()
        composeRule.onNodeWithText("Add to today's plan").assertIsDisplayed()
    }
}
