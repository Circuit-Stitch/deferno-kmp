package com.circuitstitch.deferno.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.circuitstitch.deferno.core.designsystem.theme.DefernoTheme
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.feature.plan.PlanState
import com.circuitstitch.deferno.feature.plan.ui.PlanScreen
import com.circuitstitch.deferno.feature.tasks.TaskDetailState
import com.circuitstitch.deferno.feature.tasks.TaskListState
import com.circuitstitch.deferno.feature.tasks.TaskTreeState
import com.circuitstitch.deferno.feature.tasks.ui.TaskDetailScreen
import com.circuitstitch.deferno.feature.tasks.ui.TaskListScreen
import com.circuitstitch.deferno.feature.tasks.ui.TaskTreeScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Compose UI interaction tests (#27) for the feature Views, run on the JVM via Robolectric. They
 * assert the thin Views forward the right intents to their components and render the gentle empty
 * copy (design-principles.md) — the logic itself is tested in each slice's commonTest (#25).
 */
@RunWith(RobolectricTestRunner::class)
class TaskScreenInteractionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setContent(content: @Composable () -> Unit) {
        composeRule.setContent { DefernoTheme { content() } }
    }

    @Test
    fun taskList_rowTap_emitsSelection() {
        val component = FakeTaskListComponent(TaskListState(tasks = SampleTasks.list))
        setContent { TaskListScreen(component) }

        composeRule.onNodeWithText("Water the plants").performClick()

        assertEquals(listOf(TaskId("2")), component.clicked)
    }

    @Test
    fun taskList_refreshTap_pullsThrough() {
        val component = FakeTaskListComponent(TaskListState(tasks = SampleTasks.list))
        setContent { TaskListScreen(component) }

        composeRule.onNodeWithText("Refresh").performClick()

        assertEquals(1, component.refreshCount)
    }

    @Test
    fun taskList_empty_showsGentleCopy() {
        val component = FakeTaskListComponent(TaskListState(tasks = emptyList()))
        setContent { TaskListScreen(component) }

        composeRule.onNodeWithText("No tasks yet").assertIsDisplayed()
    }

    @Test
    fun taskDetail_actions_emitIntents() {
        val task = sampleTask("1", "Plan the spring launch", children = listOf("1a", "1b"), description = "Do the thing")
        val component = FakeTaskDetailComponent(TaskDetailState(task = task, isHydrating = false))
        setContent { TaskDetailScreen(component) }

        composeRule.onNodeWithText("Add to today's plan").performClick()
        composeRule.onNodeWithText("Show its 2 steps").performClick()
        composeRule.onNodeWithText("Back").performClick()

        assertEquals(1, component.addToPlanCount)
        assertEquals(1, component.showTreeCount)
        assertEquals(1, component.closeCount)
    }

    @Test
    fun taskTree_childTap_drillsIn() {
        val component = FakeTaskTreeComponent(
            TaskTreeState(root = sampleTask("1", "Plan the spring launch"), children = SampleTasks.children),
        )
        setContent { TaskTreeScreen(component) }

        composeRule.onNodeWithText("Draft the announcement").performClick()

        assertEquals(listOf(TaskId("1a")), component.childClicked)
    }

    @Test
    fun plan_rowTap_opensTask_andRefreshPulls() {
        val component = FakePlanComponent(PlanState(tasks = SampleTasks.list))
        setContent { PlanScreen(component) }

        composeRule.onNodeWithText("Reply to Sam").performClick()
        composeRule.onNodeWithText("Refresh").performClick()

        assertEquals(listOf(TaskId("3")), component.clicked)
        assertEquals(1, component.refreshCount)
    }

    @Test
    fun plan_empty_showsGentleCopy() {
        val component = FakePlanComponent(PlanState(tasks = emptyList()))
        setContent { PlanScreen(component) }

        composeRule.onNodeWithText("Your plan is clear").assertIsDisplayed()
    }
}
