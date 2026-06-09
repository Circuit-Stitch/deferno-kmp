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
import com.circuitstitch.deferno.feature.plan.PlanState
import com.circuitstitch.deferno.feature.plan.ui.PlanScreen
import com.circuitstitch.deferno.feature.tasks.TaskDetailState
import com.circuitstitch.deferno.feature.tasks.TaskListState
import com.circuitstitch.deferno.feature.tasks.TaskTreeState
import com.circuitstitch.deferno.feature.tasks.ui.TaskDetailScreen
import com.circuitstitch.deferno.feature.tasks.ui.TaskListScreen
import com.circuitstitch.deferno.feature.tasks.ui.TaskTreeScreen
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

    @Test
    fun taskList_populated_light() = capture("task_list_populated_light") {
        TaskListScreen(FakeTaskListComponent(TaskListState(tasks = SampleTasks.list)))
    }

    @Test
    fun taskList_populated_dark() = capture("task_list_populated_dark", darkTheme = true) {
        TaskListScreen(FakeTaskListComponent(TaskListState(tasks = SampleTasks.list)))
    }

    @Test
    fun taskList_empty_light() = capture("task_list_empty_light") {
        TaskListScreen(FakeTaskListComponent(TaskListState(tasks = emptyList())))
    }

    @Test
    fun taskDetail_hydrated_light() = capture("task_detail_hydrated_light") {
        TaskDetailScreen(FakeTaskDetailComponent(TaskDetailState(task = hydratedTask, isHydrating = false)))
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
    fun taskTree_light() = capture("task_tree_light") {
        TaskTreeScreen(
            FakeTaskTreeComponent(
                TaskTreeState(root = sampleTask("1", "Plan the spring launch"), children = SampleTasks.children),
            ),
        )
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
