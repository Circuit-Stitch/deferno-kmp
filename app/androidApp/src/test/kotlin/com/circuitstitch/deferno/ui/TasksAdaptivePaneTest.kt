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
 * Acceptance test for the adaptive tier-2 Panes (#29, ADR-0007): the Tasks Destination's co-resident
 * list/detail slots render as **two Panes on expanded/regular width and one Pane on compact**, driven
 * purely by the continuous window metric (ADR-0008 G1) — here the Robolectric `@Config(qualifiers)`
 * width, which `currentWindowAdaptiveInfo()` reads. It drives a real [DefaultTasksComponent] over an
 * in-memory repository on [Dispatchers.Unconfined] (state flows resolve synchronously) through the
 * rendered UI, so it exercises the whole `ListDetailPaneScaffold` path, not a hand-rolled breakpoint.
 *
 * The list pane's **"Refresh"** action and the detail pane's **"Add to today's plan"** button are the
 * unambiguous tells: both on screen ⇒ two panes; only the detail's ⇒ a single pane collapsed onto the
 * open task. The breakpoint is pinned at the medium boundary (599dp single / 600dp two), matching
 * `calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth`.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalTestApi::class)
class TasksAdaptivePaneTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun tasksComponent() =
        DefaultTasksComponent(
            componentContext = DefaultComponentContext(LifecycleRegistry()),
            taskRepository = DemoTaskRepository(SampleTasks.list + SampleTasks.children),
            coroutineContext = Dispatchers.Unconfined,
        )

    /** Open the first task, then assert which panes are present. Clicked while only the list row exists. */
    private fun openTaskAndAssertPanes(listRefreshVisible: Boolean) {
        val component = tasksComponent()
        composeRule.setContent { DefernoTheme { TasksScreen(component) } }

        composeRule.onNodeWithText("Plan the spring launch").performClick()

        // The detail pane is always present once a task is open.
        composeRule.onNodeWithText("Add to today's plan").assertIsDisplayed()
        // The list pane survives only in two-pane mode.
        if (listRefreshVisible) {
            composeRule.onNodeWithText("Refresh").assertIsDisplayed()
        } else {
            composeRule.onNodeWithText("Refresh").assertDoesNotExist()
        }
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun expandedWidth_showsListAndDetailTogether() = openTaskAndAssertPanes(listRefreshVisible = true)

    @Test
    @Config(qualifiers = "w600dp-h800dp")
    fun mediumBoundary_isTwoPane() = openTaskAndAssertPanes(listRefreshVisible = true)

    @Test
    @Config(qualifiers = "w599dp-h800dp")
    fun belowMediumBoundary_isSinglePane() = openTaskAndAssertPanes(listRefreshVisible = false)

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun compactWidth_collapsesToOnePane() = openTaskAndAssertPanes(listRefreshVisible = false)
}
