package com.circuitstitch.deferno.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.circuitstitch.deferno.core.data.item.InMemoryItemFoldStore
import com.circuitstitch.deferno.core.designsystem.theme.DefernoTheme
import com.circuitstitch.deferno.core.model.Item
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.demo.DemoItemRepository
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
 * Regression test for the single-pane drill-in bug (#27, review findings #1+#2). The detail slot is
 * re-keyed when the user drills into a child (now via the inline subtask tree's row tap — web's
 * chevron), so the single-pane host must foreground the freshly-opened child detail. A naive
 * precedence could keep the parent on top and the child tap would look like it did nothing.
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
        val items = listOf(
            Item("p", ItemKind.Task, "Parent task"),
            Item("c", ItemKind.Task, "Child step", parentId = "p", sequence = 1),
        )
        val root = DefaultTasksComponent(
            componentContext = DefaultComponentContext(LifecycleRegistry()),
            itemRepository = DemoItemRepository(items),
            foldStore = InMemoryItemFoldStore(),
            taskRepository = DemoTaskRepository(listOf(parent, child)),
            coroutineContext = Dispatchers.Unconfined,
        )

        composeRule.setContent { DefernoTheme { TasksScreen(root) } }

        // Tree → open the parent's detail (trailing `›`) → tap the child subtask row to drill into its
        // detail. In the compact fold the tree is hidden once detail opens, so "Child step" is unambiguous.
        composeRule.onNodeWithContentDescription("Open Parent task").performClick()
        composeRule.onNodeWithText("Child step").performScrollTo().performClick()

        // The child's *detail* is now foregrounded: its title header and the detail-only FAB show. The FAB
        // (contentDescription "Add", opening the add-actions sheet) replaced the omni-present inline "Add to
        // today's plan" button on Android, so it's the detail-only tell now.
        composeRule.onNodeWithText("Child step").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Add").assertIsDisplayed()
    }
}
