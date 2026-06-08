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
import com.circuitstitch.deferno.demo.DemoPlanRepository
import com.circuitstitch.deferno.demo.DemoTaskRepository
import com.circuitstitch.deferno.demo.SampleData
import com.circuitstitch.deferno.shell.DefaultMainShellComponent
import com.circuitstitch.deferno.shell.Destination
import com.circuitstitch.deferno.shell.MainShell
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Roborazzi screenshot baselines (#70, ADR-0015) for the Main shell's **adaptive nav suite** at the
 * three size classes — compact (bottom bar `Plan · Calendar · Tasks · More`), medium (rail), and
 * expanded (drawer, secondaries listed directly, no "More"). Each pins its width via `@Config`, the
 * same metric the production View reads through `currentWindowAdaptiveInfo()` (ADR-0008 G1), and drives
 * a real [DefaultMainShellComponent] over the in-memory demo repositories on [Dispatchers.Unconfined]
 * (the same harness the interaction tests use). Record with `:app:androidApp:recordRoborazziDebug`.
 */
@RunWith(RobolectricTestRunner::class)
class MainShellNavScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun shell() = DefaultMainShellComponent(
        componentContext = DefaultComponentContext(LifecycleRegistry()),
        taskRepository = DemoTaskRepository(SampleData.tasks),
        planRepository = DemoPlanRepository(emptyList()),
        authRepository = FakeAuthRepository(),
        settingsRepository = FakeSettingsRepository(),
        settingsWriter = FakeSettingsWriter(),
        account = sampleAccount,
        today = LocalDate(2026, 6, 6),
        timeZone = "UTC",
        coroutineContext = Dispatchers.Unconfined,
    )

    private fun capture(name: String, darkTheme: Boolean = false, content: @Composable () -> Unit) {
        composeRule.setContent {
            DefernoTheme(palette = DefernoPalette.Deferno, darkTheme = darkTheme) {
                Surface(modifier = Modifier.fillMaxSize()) { content() }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun compact_bottomBar_light() = capture("shell_nav_compact_light") { MainShell(shell()) }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun compact_bottomBar_dark() = capture("shell_nav_compact_dark", darkTheme = true) { MainShell(shell()) }

    // A secondary Destination (Profile) foreground on compact: "More" reads as selected, the secondary
    // is NOT a direct bar item, and the Profile screen renders as content (ADR-0015).
    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun compact_moreSelected_light() = capture("shell_nav_compact_more_selected_light") {
        MainShell(shell().also { it.selectDestination(Destination.Profile) })
    }

    @Test
    @Config(qualifiers = "w700dp-h800dp")
    fun medium_rail_light() = capture("shell_nav_medium_light") { MainShell(shell()) }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun expanded_drawer_light() = capture("shell_nav_expanded_light") { MainShell(shell()) }
}
