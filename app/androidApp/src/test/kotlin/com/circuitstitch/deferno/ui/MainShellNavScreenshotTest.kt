package com.circuitstitch.deferno.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.circuitstitch.deferno.core.data.item.InMemoryItemFoldStore
import com.circuitstitch.deferno.core.designsystem.theme.DefernoPalette
import com.circuitstitch.deferno.core.designsystem.theme.DefernoTheme
import com.circuitstitch.deferno.demo.DemoItemRepository
import com.circuitstitch.deferno.demo.DemoPlanRepository
import com.circuitstitch.deferno.demo.DemoTaskRepository
import com.circuitstitch.deferno.demo.SampleData
import com.circuitstitch.deferno.shell.DefaultMainShellComponent
import com.circuitstitch.deferno.shell.Destination
import com.circuitstitch.deferno.shell.MainShell
import com.circuitstitch.deferno.shell.ui.ShellChrome
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Roborazzi screenshot baselines (#175 follow-up) for the Main shell's shared [ShellChrome] reveal
 * drawer: the **closed** state (the slim top bar — menu · voice_chat · add_task — clear of the status
 * bar, over the home content) in light + dark, and the **open** state (the content slid aside to reveal
 * the navigation menu underneath). It drives a real [DefaultMainShellComponent] over the in-memory demo
 * repositories on [Dispatchers.Unconfined] (the same harness the interaction test uses). Record with
 * `:app:androidApp:recordRoborazziDebug`.
 */
@RunWith(RobolectricTestRunner::class)
class MainShellNavScreenshotTest {

    // Stays on the v1 `createComposeRule` (cf. the other UI tests' v2 rule): this baseline drives a real
    // DefaultMainShellComponent whose chrome animates the drawer reveal, so the captured frame depends on
    // the autoAdvance + Unconfined timing these goldens are recorded under (a v2 StandardTestDispatcher
    // would queue those effects and change a re-record).
    @get:Rule
    @Suppress("DEPRECATION")
    val composeRule = createComposeRule()

    private fun shell() = DefaultMainShellComponent(
        componentContext = DefaultComponentContext(LifecycleRegistry()),
        itemRepository = DemoItemRepository(),
        foldStore = InMemoryItemFoldStore(),
        taskRepository = DemoTaskRepository(SampleData.tasks),
        planRepository = DemoPlanRepository(emptyList()),
        authRepository = FakeAuthRepository(),
        settingsRepository = FakeSettingsRepository(),
        settingsEditor = FakeSettingsEditor(),
        account = sampleAccount,
        today = LocalDate(2026, 6, 6),
        timeZone = "UTC",
        coroutineContext = Dispatchers.Unconfined,
    )

    private fun setContent(darkTheme: Boolean = false, content: @Composable () -> Unit) {
        composeRule.setContent {
            DefernoTheme(palette = DefernoPalette.Deferno, darkTheme = darkTheme) {
                Surface(modifier = Modifier.fillMaxSize()) { content() }
            }
        }
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun drawerClosed_light() {
        setContent { MainShell(shell()) }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/shell_chrome_closed_light.png")
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun drawerClosed_dark() {
        setContent(darkTheme = true) { MainShell(shell()) }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/shell_chrome_closed_dark.png")
    }

    // The menu button slides the content aside to reveal the drawer menu underneath (account header +
    // Search + the destination rows). autoAdvance settles the reveal animation before the capture.
    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun drawerOpen_light() {
        setContent { MainShell(shell()) }
        composeRule.onNodeWithContentDescription("Menu").performClick()
        composeRule.onRoot().captureRoboImage("src/test/screenshots/shell_chrome_open_light.png")
    }

    // The Tasks search-as-top-bar (Files pattern): on the Tasks destination the top bar IS a full-width
    // search pill (☰ inside it + trailing magnifier), with the capture FABs moved to bottom-centre. Drives
    // the real MainShell on Tasks so the bar treatment + FAB placement are both baselined.
    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun tasksSearchBar_light() {
        setContent { MainShell(shell().also { it.selectDestination(Destination.Tasks) }) }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/shell_tasks_search_bar_light.png")
    }
}
