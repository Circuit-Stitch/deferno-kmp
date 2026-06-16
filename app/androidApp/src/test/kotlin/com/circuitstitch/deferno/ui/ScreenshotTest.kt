package com.circuitstitch.deferno.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.circuitstitch.deferno.core.designsystem.theme.DefernoPalette
import com.circuitstitch.deferno.core.designsystem.theme.DefernoTheme
import com.circuitstitch.deferno.core.model.Attachment
import com.circuitstitch.deferno.core.model.Comment
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.UserId
import com.circuitstitch.deferno.core.model.WorkingState
import com.circuitstitch.deferno.feature.plan.PlanState
import com.circuitstitch.deferno.feature.plan.ui.PlanScreen
import com.circuitstitch.deferno.feature.tasks.ItemTreeState
import com.circuitstitch.deferno.feature.tasks.SubtaskRow
import com.circuitstitch.deferno.feature.tasks.TaskDetailState
import com.circuitstitch.deferno.feature.tasks.buildItemTree
import kotlin.time.Instant
import com.circuitstitch.deferno.feature.tasks.ui.TaskDetailScreen
import com.circuitstitch.deferno.feature.tasks.ui.TaskListScreen
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Roborazzi screenshot baseline (#27) for the feature Views across key states in the Deferno palette
 * (light + dark) — exercising the design system end-to-end on the JVM-fast path (ADR-0006). Record
 * with `./gradlew :app:androidApp:recordRoborazziDebug` (PNGs committed under src/test/screenshots);
 * CI guards regressions with `verifyRoborazziDebug`. With no Roborazzi mode set, `captureRoboImage`
 * is a no-op, so these run harmlessly as part of the normal unit-test task too.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
class ScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun capture(name: String, darkTheme: Boolean = false, content: @Composable () -> Unit) {
        composeRule.setContent {
            DefernoTheme(palette = DefernoPalette.Deferno, darkTheme = darkTheme) {
                Surface(modifier = Modifier.fillMaxSize()) { content() }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    private val hydratedTask = sampleTask(
        id = "1",
        title = "Plan the spring launch",
        workingState = com.circuitstitch.deferno.core.model.WorkingState.InProgress,
        pinned = true,
        children = listOf("1a", "1b"),
        description = "A calm, plain description of the work — hydrated on open (#22).",
        hydration = com.circuitstitch.deferno.core.model.HydrationState.Full,
    )

    /** A full detail state showing the web-parity sections: a recursive subtask tree, comments, attachments. */
    private val populatedDetail = TaskDetailState(
        task = hydratedTask,
        isHydrating = false,
        // The subtree flattened with the shared fold mechanism (ADR-0034): "1a" parents "1ai", both shallow
        // so they auto-expand; "1a" shows a fold chevron.
        subtaskRows = listOf(
            SubtaskRow(
                sampleTask("1a", "Draft the announcement", workingState = WorkingState.Done, parentId = "1"),
                depth = 0, hasChildren = true, isExpanded = true,
            ),
            SubtaskRow(
                sampleTask("1ai", "Outline the key points", parentId = "1a"),
                depth = 1, hasChildren = false, isExpanded = false,
            ),
            SubtaskRow(
                sampleTask("1b", "Schedule the post", parentId = "1"),
                depth = 0, hasChildren = false, isExpanded = false,
            ),
        ),
        subtaskDone = 1,
        subtaskTotal = 3,
        comments = listOf(
            Comment(
                id = "c1", taskId = TaskId("1"), body = "Kicking this off — aiming for end of week.",
                createdBy = UserId("me"), createdAt = Instant.parse("2026-04-17T10:00:00Z"),
            ),
            Comment(
                id = "c2", taskId = TaskId("1"), body = "Sounds good, I'll cover the social copy.",
                createdBy = UserId("other"), createdAt = Instant.parse("2026-04-17T11:30:00Z"),
            ),
        ),
        currentUserId = UserId("me"),
        attachments = listOf(
            Attachment(
                id = "a1", filename = "launch-brief.pdf", mime = "application/pdf", size = 248_000L,
                url = "https://example.test/a1", createdBy = UserId("me"),
                createdAt = Instant.parse("2026-04-17T09:00:00Z"),
            ),
        ),
    )

    // The Item tree (#227): a few nested expanded rows, a collapsed parent with a done/total badge, and a
    // terminal (dimmed) row — the row states ADR-0034 decision 7 calls out. Rows are rendered verbatim
    // (the View doesn't re-flatten), so they're built explicitly to pin each state.
    private val treeStatesRows = listOf(
        itemRow("p1", "Plan the spring launch", hasChildren = true, isExpanded = true),
        itemRow("c1", "Draft the announcement", depth = 1),
        itemRow(
            "c2", "Review copy with the team", depth = 1, hasChildren = true, isExpanded = false,
            descendantDone = 0, descendantTotal = 3,
        ),
        itemRow("c3", "Schedule the post", depth = 1, isTerminal = true),
        itemRow("p2", "Water the plants"),
        itemRow("p3", "Old idea worth revisiting", isTerminal = true),
    )

    @Test
    fun itemTree_populated_light() = capture("task_list_populated_light") {
        TaskListScreen(FakeItemTreeComponent(ItemTreeState(rows = buildItemTree(SampleTasks.items))))
    }

    @Test
    fun itemTree_states_light() = capture("item_tree_states_light") {
        TaskListScreen(FakeItemTreeComponent(ItemTreeState(rows = treeStatesRows)))
    }

    @Test
    fun itemTree_states_dark() = capture("item_tree_states_dark", darkTheme = true) {
        TaskListScreen(FakeItemTreeComponent(ItemTreeState(rows = treeStatesRows)))
    }

    @Test
    fun itemTree_empty_light() = capture("task_list_empty_light") {
        TaskListScreen(FakeItemTreeComponent(ItemTreeState(rows = emptyList())))
    }

    @Test
    fun taskDetail_hydrated_light() = capture("task_detail_hydrated_light") {
        TaskDetailScreen(FakeTaskDetailComponent(populatedDetail))
    }

    @Test
    fun taskDetail_hydrated_dark() = capture("task_detail_hydrated_dark", darkTheme = true) {
        TaskDetailScreen(FakeTaskDetailComponent(TaskDetailState(task = hydratedTask, isHydrating = false)))
    }

    @Test
    fun taskDetail_hydrating_light() = capture("task_detail_hydrating_light") {
        val summary = sampleTask("2", "Water the plants")
        TaskDetailScreen(FakeTaskDetailComponent(TaskDetailState(task = summary, isHydrating = true)))
    }

    @Test
    fun plan_populated_light() = capture("plan_populated_light") {
        PlanScreen(FakePlanComponent(PlanState(tasks = SampleTasks.list)))
    }

    @Test
    fun plan_populated_dark() = capture("plan_populated_dark", darkTheme = true) {
        PlanScreen(FakePlanComponent(PlanState(tasks = SampleTasks.list)))
    }

    @Test
    fun plan_empty_light() = capture("plan_empty_light") {
        PlanScreen(FakePlanComponent(PlanState(tasks = emptyList())))
    }
}
