package com.circuitstitch.deferno.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.circuitstitch.deferno.core.data.item.InMemoryItemFoldStore
import com.circuitstitch.deferno.core.designsystem.format.LocalToday
import com.circuitstitch.deferno.core.designsystem.theme.DefernoPalette
import com.circuitstitch.deferno.core.designsystem.theme.DefernoTheme
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.demo.DemoItemRepository
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
@OptIn(ExperimentalTestApi::class)
class TasksPaneScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun tasksComponent(): TasksComponent =
        DefaultTasksComponent(
            componentContext = DefaultComponentContext(LifecycleRegistry()),
            itemRepository = DemoItemRepository(SampleTasks.items),
            foldStore = InMemoryItemFoldStore(),
            taskRepository = DemoTaskRepository(SampleTasks.list + SampleTasks.children),
            coroutineContext = Dispatchers.Unconfined,
        )

    /** Open item "1"'s detail via the tree's trailing `›` (the slot activates synchronously, Unconfined). */
    private fun TasksComponent.openDetail() = tree.onOpenDetail("1", ItemKind.Task)

    /** Lift child "1a" into modal move mode (#228): highlighted row, calmed rest, the ↑↓‹› + Done bar. */
    private fun TasksComponent.enterMoveMode() = tree.onEnterMoveMode("1a")

    private fun capture(name: String, darkTheme: Boolean = false, content: @Composable () -> Unit) {
        composeRule.setContent {
            DefernoTheme(palette = DefernoPalette.Deferno, darkTheme = darkTheme) {
                // Pin "today" so the detail's WHEN row ("in N days") doesn't drift with the wall clock.
                CompositionLocalProvider(LocalToday provides SCREENSHOT_TODAY) {
                    Surface(modifier = Modifier.fillMaxSize()) { content() }
                }
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
    fun compact_moveMode_light() = capture("tasks_compact_move_mode_light") {
        // The lifted child "1a" is highlighted, the rest calmed, and the ↑↓‹› + Done bar shows with the
        // illegal directions (up / indent — it is the first child) greyed (ADR-0034 decision 6, #228).
        TasksScreen(tasksComponent().also { it.enterMoveMode() })
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
    fun expanded_empty_light() = capture("tasks_expanded_empty_light") {
        TasksScreen(tasksComponent())
    }
}
