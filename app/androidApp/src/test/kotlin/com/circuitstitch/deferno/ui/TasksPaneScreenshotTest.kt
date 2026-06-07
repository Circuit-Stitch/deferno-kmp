package com.circuitstitch.deferno.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.circuitstitch.deferno.core.designsystem.theme.DefernoPalette
import com.circuitstitch.deferno.core.designsystem.theme.DefernoTheme
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.demo.DemoTaskRepository
import com.circuitstitch.deferno.feature.tasks.DefaultTasksComponent
import com.circuitstitch.deferno.feature.tasks.TasksComponent
import com.circuitstitch.deferno.feature.tasks.ui.TasksScreen
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Roborazzi screenshot baselines for the adaptive tier-2 Panes (#29, ADR-0007) at **representative
 * widths** — compact (single Pane) and expanded (two Panes) — in the Deferno palette (light + dark).
 * Each test pins its width via `@Config(qualifiers)`, the same continuous metric the production View
 * reads through `currentWindowAdaptiveInfo()` (ADR-0008 G1).
 *
 * It drives a real [DefaultTasksComponent] over an in-memory repository on [Dispatchers.Unconfined]
 * (state resolves synchronously, so opening a task before capture is enough) — the same harness the
 * interaction tests use. Record with `./gradlew :app:androidApp:recordRoborazziDebug`; CI guards
 * regressions with `verifyRoborazziDebug`. With no Roborazzi mode set, `captureRoboImage` is a no-op,
 * so these also run harmlessly as part of the normal unit-test task.
 */
@RunWith(RobolectricTestRunner::class)
class TasksPaneScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun tasksComponent(): TasksComponent =
        DefaultTasksComponent(
            componentContext = DefaultComponentContext(LifecycleRegistry()),
            taskRepository = DemoTaskRepository(SampleTasks.list + SampleTasks.children),
            coroutineContext = Dispatchers.Unconfined,
        )

    /** Open task "1"'s detail (the slot activates synchronously on the unconfined dispatcher). */
    private fun TasksComponent.openDetail() = list.onTaskClicked(TaskId("1"))

    /** Open task "1"'s breakdown (its detail's "show steps"), leaving the tree foregrounded. */
    private fun TasksComponent.openTree() {
        openDetail()
        detail.value.child!!.instance.onShowTreeClicked()
    }

    private fun capture(name: String, darkTheme: Boolean = false, content: @Composable () -> Unit) {
        composeRule.setContent {
            DefernoTheme(palette = DefernoPalette.Deferno, darkTheme = darkTheme) {
                Surface(modifier = Modifier.fillMaxSize()) { content() }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    // --- Compact: a single Pane ---

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun compact_list_light() = capture("tasks_compact_list_light") { TasksScreen(tasksComponent()) }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun compact_detail_light() = capture("tasks_compact_detail_light") {
        TasksScreen(tasksComponent().also { it.openDetail() })
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun compact_detail_dark() = capture("tasks_compact_detail_dark", darkTheme = true) {
        TasksScreen(tasksComponent().also { it.openDetail() })
    }

    // --- Medium (regular): two Panes already, but the tightest split ---
    // calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth gives two panes from the medium bucket
    // (600-839dp) up; the list pins near its preferred width so the detail is far narrower here than at
    // expanded — the directive's distinctive proportion, baselined so a regression in it is caught.

    @Test
    @Config(qualifiers = "w700dp-h800dp")
    fun medium_listDetail_light() = capture("tasks_medium_list_detail_light") {
        TasksScreen(tasksComponent().also { it.openDetail() })
    }

    // --- Expanded: two Panes (list + secondary) ---

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun expanded_listDetail_light() = capture("tasks_expanded_list_detail_light") {
        TasksScreen(tasksComponent().also { it.openDetail() })
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun expanded_listDetail_dark() = capture("tasks_expanded_list_detail_dark", darkTheme = true) {
        TasksScreen(tasksComponent().also { it.openDetail() })
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun expanded_listTree_light() = capture("tasks_expanded_list_tree_light") {
        TasksScreen(tasksComponent().also { it.openTree() })
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun expanded_empty_light() = capture("tasks_expanded_empty_light") {
        TasksScreen(tasksComponent())
    }
}
