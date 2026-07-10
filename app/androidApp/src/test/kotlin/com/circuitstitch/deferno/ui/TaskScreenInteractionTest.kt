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
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import com.circuitstitch.deferno.core.designsystem.theme.DefernoTheme
import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.Item
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
import com.circuitstitch.deferno.feature.plan.PlanState
import com.circuitstitch.deferno.feature.plan.ui.PlanScreen
import com.circuitstitch.deferno.feature.tasks.ItemTreeState
import com.circuitstitch.deferno.feature.tasks.SubtaskRow
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
    fun itemTree_showBlockedChip_forwardsTheReadinessToggle() {
        // #290: the "Show blocked" chip (resting off — ready-only) forwards a flip to the component.
        val component = FakeItemTreeComponent(ItemTreeState(rows = buildItemTree(SampleTasks.items)))
        setContent { TaskListScreen(component) }

        composeRule.onNodeWithText("Show blocked").performClick()

        assertEquals(listOf(true), component.showBlockedSet)
    }

    @Test
    fun itemTree_nonTaskRow_archiveForwardsDefinitionStateIntent() {
        // #299: a recurring (non-Task) row's long-press menu shows the kind-aware status block. An active
        // Habit shows "Archive" (not the Task working-state verbs) → forwards SetDefinitionState(Archived).
        val habit = Item("h1", ItemKind.Habit, "Stretch daily", sequence = 0)
        val component = FakeItemTreeComponent(ItemTreeState(rows = buildItemTree(listOf(habit))))
        setContent { TaskListScreen(component) }

        composeRule.onNodeWithText("Stretch daily").performTouchInput { longClick() }
        composeRule.onNodeWithText("Archive").performClick()

        assertEquals(listOf("h1" to DefinitionState.Archived), component.definitionStatesSet)
    }

    @Test
    fun itemTree_archivedNonTaskRow_activateForwardsDefinitionStateIntent() {
        // The verb swaps by the row's archived bit (item.isTerminal): an archived Habit shows "Activate".
        val archived = Item("h1", ItemKind.Habit, "Stretch daily", sequence = 0, isTerminal = true)
        val component = FakeItemTreeComponent(ItemTreeState(rows = buildItemTree(listOf(archived))))
        setContent { TaskListScreen(component) }

        composeRule.onNodeWithText("Stretch daily").performTouchInput { longClick() }
        composeRule.onNodeWithText("Activate").performClick()

        assertEquals(listOf("h1" to DefinitionState.Active), component.definitionStatesSet)
    }

    @Test
    fun itemTree_empty_showsGentleCopy() {
        val component = FakeItemTreeComponent(ItemTreeState(rows = emptyList()))
        setContent { TaskListScreen(component) }

        composeRule.onNodeWithText("No trees yet").assertIsDisplayed()
    }

    @Test
    fun taskDetail_addToPlan_emitsIntent() {
        // The omni-present inline "Add to today's plan" button is gone on Android: the FAB (contentDescription
        // "Add") opens a ModalBottomSheet whose three rows are the add-actions. Add-to-plan is now the third
        // sheet row — open the FAB, then tap it, and the detail forwards the plan intent.
        val task = sampleTask("1", "Plan the spring launch", description = "Do the thing")
        val component = FakeTaskDetailComponent(TaskDetailState(task = task, isHydrating = false))
        setContent { TaskDetailScreen(component) }

        composeRule.onNodeWithContentDescription("Add").performClick()
        composeRule.onNodeWithText("Add to today's plan").performClick()

        assertEquals(1, component.addToPlanCount)
    }

    @Test
    fun taskDetail_addSheetComment_emitsIntent() {
        // The FAB sheet's "Add comment" row forwards onAddCommentRequested() (which reveals the comment composer).
        val task = sampleTask("1", "Plan the spring launch", description = "Do the thing")
        val component = FakeTaskDetailComponent(TaskDetailState(task = task, isHydrating = false))
        setContent { TaskDetailScreen(component) }

        composeRule.onNodeWithContentDescription("Add").performClick()
        composeRule.onNodeWithText("Add comment").performClick()

        assertEquals(1, component.addCommentRequestedCount)
    }

    @Test
    fun taskDetail_addSheetSubtask_emitsIntent() {
        // The FAB sheet's "Add subtask" row forwards onAddSubtaskRequested().
        val task = sampleTask("1", "Plan the spring launch", description = "Do the thing")
        val component = FakeTaskDetailComponent(TaskDetailState(task = task, isHydrating = false))
        setContent { TaskDetailScreen(component) }

        composeRule.onNodeWithContentDescription("Add").performClick()
        composeRule.onNodeWithText("Add subtask").performClick()

        assertEquals(1, component.addSubtaskRequestedCount)
    }

    @Test
    fun taskDetail_addSheetChangeStatus_emitsIntent() {
        // The FAB sheet's "Change status" row forwards onChangeStatusRequested() (which opens the status picker).
        val task = sampleTask("1", "Plan the spring launch", description = "Do the thing")
        val component = FakeTaskDetailComponent(TaskDetailState(task = task, isHydrating = false))
        setContent { TaskDetailScreen(component) }

        composeRule.onNodeWithContentDescription("Add").performClick()
        composeRule.onNodeWithText("Change status").performClick()

        assertEquals(1, component.changeStatusRequestedCount)
    }

    @Test
    fun taskDetail_subtaskToggleAndComment_forwardIntents() {
        val parent = sampleTask("1", "Plan the spring launch")
        val child = sampleTask("1a", "Draft the announcement", parentId = "1")
        val state = TaskDetailState(
            task = parent,
            isHydrating = false,
            subtaskRows = listOf(SubtaskRow(child, depth = 0, hasChildren = false, isExpanded = false)),
            subtaskTotal = 1,
        )
        val component = FakeTaskDetailComponent(state)
        setContent { TaskDetailScreen(component) }

        // The inline subtask checkbox (Info tab, the default) forwards a done-toggle for that child. The Info
        // tab is taller (title block + properties + subtask tree), so scroll the row into view before tapping.
        composeRule.onNodeWithContentDescription("Mark “Draft the announcement” done").performScrollTo().performClick()
        // ADR-0044 split the feed into tabs: the comment composer now lives under the Comments tab, so switch
        // to it before posting. Then the composer forwards the posted comment text.
        composeRule.onNodeWithText("Comments").performClick()
        composeRule.onNodeWithText("Add a comment…").performScrollTo().performTextInput("Looks good")
        composeRule.onNodeWithText("Post").performScrollTo().performClick()

        assertEquals(listOf(TaskId("1a")), component.subtaskToggles)
        assertEquals(listOf("Looks good"), component.commentsPosted)
    }

    @Test
    fun taskDetail_statusRow_opensPickerAndForwardsTheSelectedState() {
        // ADR-0044: the inline working-state chips are gone. STATUS is a read-only journey row (a11y
        // "Status: <label>. Tap to change.") whose tap opens the status picker sheet; picking a state there
        // forwards the one lifecycle edit (#73). The Task is Open → the row reads TO-DO; picking In progress
        // forwards InProgress.
        val task = sampleTask("1", "Plan the spring launch", workingState = WorkingState.Open)
        val component = FakeTaskDetailComponent(TaskDetailState(task = task, isHydrating = false))
        setContent { TaskDetailScreen(component) }

        composeRule.onNodeWithContentDescription("Status: TO-DO. Tap to change.").performScrollTo().performClick()
        composeRule.onNodeWithText("In progress").performClick()

        assertEquals(listOf(WorkingState.InProgress), component.workingStateSets)
    }

    @Test
    fun taskDetail_statusPicker_reachesSetAside() {
        // ADR-0044 removed the kebab's "Set aside" — Dropped ("Set aside") is now reachable ONLY through the
        // status picker sheet. Proving the shelve path still exists guards that removal.
        val task = sampleTask("1", "Plan the spring launch", workingState = WorkingState.Open)
        val component = FakeTaskDetailComponent(TaskDetailState(task = task, isHydrating = false))
        setContent { TaskDetailScreen(component) }

        composeRule.onNodeWithContentDescription("Status: TO-DO. Tap to change.").performScrollTo().performClick()
        composeRule.onNodeWithText("Set aside").performClick()

        assertEquals(listOf(WorkingState.Dropped), component.workingStateSets)
    }

    @Test
    fun taskDetail_currentWorkingState_readsAsTheJourneyLabel() {
        // The read-only STATUS row surfaces the current working state as its journey label (ADR-0044): an
        // InReview Task reads IN-REVIEW in the row's self-describing a11y (colour is never the sole signal).
        val task = sampleTask("1", workingState = WorkingState.InReview)
        val component = FakeTaskDetailComponent(TaskDetailState(task = task, isHydrating = false))
        setContent { TaskDetailScreen(component) }

        composeRule.onNodeWithContentDescription("Status: IN-REVIEW. Tap to change.")
            .performScrollTo()
            .assertIsDisplayed()
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
