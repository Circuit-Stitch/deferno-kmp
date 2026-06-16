package com.circuitstitch.deferno.ui

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.assertAll
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isNotFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.circuitstitch.deferno.core.designsystem.theme.DefernoTheme
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
import com.circuitstitch.deferno.feature.plan.PlanState
import com.circuitstitch.deferno.feature.plan.ui.PlanScreen
import com.circuitstitch.deferno.feature.tasks.ItemTreeState
import com.circuitstitch.deferno.feature.tasks.SubtaskNode
import com.circuitstitch.deferno.feature.tasks.TaskDetailState
import com.circuitstitch.deferno.feature.tasks.buildItemTree
import com.circuitstitch.deferno.feature.tasks.ui.TaskDetailScreen
import com.circuitstitch.deferno.feature.tasks.ui.TaskListScreen
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
@OptIn(ExperimentalTestApi::class)
class TaskScreenInteractionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setContent(content: @Composable () -> Unit) {
        composeRule.setContent { DefernoTheme { content() } }
    }

    @Test
    fun itemTree_openAffordance_emitsOpenDetail() {
        val component = FakeItemTreeComponent(ItemTreeState(rows = buildItemTree(SampleTasks.items)))
        setContent { TaskListScreen(component) }

        // The trailing `›` is the only open-detail affordance (ADR-0034 decision 7).
        composeRule.onNodeWithContentDescription("Open Water the plants").performClick()

        assertEquals(listOf("2" to ItemKind.Task), component.opened)
    }

    @Test
    fun itemTree_parentBodyTap_togglesExpand() {
        val component = FakeItemTreeComponent(ItemTreeState(rows = buildItemTree(SampleTasks.items)))
        setContent { TaskListScreen(component) }

        // "Plan the spring launch" is an expanded parent (depth 0); a body tap collapses it (toggle from
        // the row's own fold, not from the WhileSubscribed state).
        composeRule.onNodeWithText("Plan the spring launch").performClick()

        assertEquals(listOf("1" to true), component.toggled)
    }

    @Test
    fun itemTree_refreshTap_pullsThrough() {
        val component = FakeItemTreeComponent(ItemTreeState(rows = buildItemTree(SampleTasks.items)))
        setContent { TaskListScreen(component) }

        composeRule.onNodeWithText("Refresh").performClick()

        assertEquals(1, component.refreshCount)
    }

    @Test
    fun itemTree_empty_showsGentleCopy() {
        val component = FakeItemTreeComponent(ItemTreeState(rows = emptyList()))
        setContent { TaskListScreen(component) }

        composeRule.onNodeWithText("No tasks yet").assertIsDisplayed()
    }

    @Test
    fun taskDetail_actions_emitIntents() {
        val task = sampleTask("1", "Plan the spring launch", description = "Do the thing")
        val component = FakeTaskDetailComponent(TaskDetailState(task = task, isHydrating = false))
        setContent { TaskDetailScreen(component) }

        composeRule.onNodeWithText("Add to today's plan").performClick()
        composeRule.onNodeWithText("Back").performClick()

        assertEquals(1, component.addToPlanCount)
        assertEquals(1, component.closeCount)
    }

    @Test
    fun taskDetail_subtaskToggleAndComment_forwardIntents() {
        val parent = sampleTask("1", "Plan the spring launch")
        val child = sampleTask("1a", "Draft the announcement", parentId = "1")
        val state = TaskDetailState(
            task = parent,
            isHydrating = false,
            subtasks = listOf(SubtaskNode(child, emptyList())),
            subtaskTotal = 1,
        )
        val component = FakeTaskDetailComponent(state)
        setContent { TaskDetailScreen(component) }

        // The inline subtask checkbox forwards a done-toggle for that child.
        composeRule.onNodeWithContentDescription("Mark “Draft the announcement” done").performClick()
        // The Activity composer forwards the posted comment text. With the PROPERTIES section added
        // (#195) the detail is taller, so scroll the composer into view before interacting.
        composeRule.onNodeWithText("Add a comment…").performScrollTo().performTextInput("Looks good")
        composeRule.onNodeWithText("Post").performScrollTo().performClick()

        assertEquals(listOf(TaskId("1a")), component.subtaskToggles)
        assertEquals(listOf("Looks good"), component.commentsPosted)
    }

    @Test
    fun taskDetail_workingStateChip_forwardsTheSetIntent() {
        // The Task is Open, so tapping "In progress" forwards a Start (→ InProgress) edit (#73).
        val task = sampleTask("1", "Plan the spring launch", workingState = WorkingState.Open)
        val component = FakeTaskDetailComponent(TaskDetailState(task = task, isHydrating = false))
        setContent { TaskDetailScreen(component) }

        composeRule.onNodeWithContentDescription("Set to In progress").performClick()
        composeRule.onNodeWithContentDescription("Set to Done").performClick()
        composeRule.onNodeWithContentDescription("Set to Set aside").performClick()

        assertEquals(
            listOf(WorkingState.InProgress, WorkingState.Done, WorkingState.Dropped),
            component.workingStateSets,
        )
    }

    @Test
    fun taskDetail_currentWorkingState_isMarkedSelected() {
        val task = sampleTask("1", workingState = WorkingState.InReview)
        val component = FakeTaskDetailComponent(TaskDetailState(task = task, isHydrating = false))
        setContent { TaskDetailScreen(component) }

        // The current state reads as the selected affordance (not a "Set to" action).
        composeRule.onNodeWithContentDescription("In review, current working state").assertIsDisplayed()
    }

    @Test
    fun taskDetail_onOpen_doesNotFocusATextField() {
        // #239: the M3 ListDetailPaneScaffold programmatically focuses the detail pane on open. In touch
        // mode the only Always-focusable targets are the "Add…" text fields (clickables/chips are
        // SystemDefined → unfocusable when touched), so that focus would land on the first text field and
        // pop the soft keyboard untouched. The fix puts a non-text Always-focusable anchor first in the
        // pane's focus order. Reproduce the scaffold's programmatic pane focus in touch mode and assert it
        // lands on no text field. Without the anchor the first "Add…" field is focused (red).
        val task = sampleTask("1", "Plan the spring launch", description = "Do the thing")
        val component = FakeTaskDetailComponent(TaskDetailState(task = task, isHydrating = false))
        val pane = FocusRequester()
        setContent {
            val inputMode = LocalInputModeManager.current
            Box(Modifier.focusRequester(pane).focusGroup()) {
                TaskDetailScreen(component)
            }
            LaunchedEffect(Unit) {
                inputMode.requestInputMode(InputMode.Touch)
                pane.requestFocus()
            }
        }

        // Every text field on the detail must be unfocused — the anchor absorbed the pane's auto-focus.
        composeRule.onAllNodes(hasSetTextAction()).assertAll(isNotFocused())
    }

    @Test
    fun plan_rowTap_opensTask() {
        val component = FakePlanComponent(PlanState(tasks = SampleTasks.list))
        setContent { PlanScreen(component) }

        // Refresh moved to the shell's single top bar (Cand 1); the isolated Plan pane is just the list.
        composeRule.onNodeWithText("Reply to Sam").performClick()

        assertEquals(listOf(TaskId("3")), component.clicked)
    }

    @Test
    fun plan_empty_showsGentleCopy() {
        val component = FakePlanComponent(PlanState(tasks = emptyList()))
        setContent { PlanScreen(component) }

        composeRule.onNodeWithText("Your plan is clear").assertIsDisplayed()
    }
}
