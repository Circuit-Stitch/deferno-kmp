package com.circuitstitch.deferno.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.circuitstitch.deferno.core.designsystem.theme.DefernoTheme
import com.circuitstitch.deferno.core.model.ThemeFamily
import com.circuitstitch.deferno.feature.settings.DefaultSettingsComponent
import com.circuitstitch.deferno.feature.settings.ui.SettingsScreen
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Compose UI interaction tests (#72) for the Settings tier-3 drill-down, run on the JVM via
 * Robolectric. They guard the View wiring the component test can't: tapping a category row drills
 * into its detail (and the back arrow returns to the list), an Appearance theme choice forwards the
 * intent to the writer, and the drag-and-drop toggle forwards its intent. Driven over the real
 * [DefaultSettingsComponent] on top of the in-memory settings fakes (the same harness pattern as
 * the Profile interaction tests).
 */
@RunWith(RobolectricTestRunner::class)
class SettingsScreenInteractionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun component(
        repo: FakeSettingsRepository = FakeSettingsRepository(),
        writer: FakeSettingsWriter = FakeSettingsWriter(repo),
    ) = DefaultSettingsComponent(
        componentContext = DefaultComponentContext(LifecycleRegistry()),
        settingsRepository = repo,
        settingsWriter = writer,
        coroutineContext = Dispatchers.Unconfined,
    )

    private fun setContent(content: @Composable () -> Unit) {
        composeRule.setContent { DefernoTheme { content() } }
    }

    @Test
    fun listShowsEveryCategory_includingTheComingSoonStubs() {
        setContent { SettingsScreen(component()) }

        composeRule.onNodeWithText("Appearance").assertIsDisplayed()
        composeRule.onNodeWithText("Task behavior").assertIsDisplayed()
        composeRule.onNodeWithText("Data & Privacy").assertIsDisplayed()
        composeRule.onNodeWithText("Security & 2FA").assertIsDisplayed()
        composeRule.onNodeWithText("Integrations").assertIsDisplayed()
    }

    @Test
    fun tappingACategoryRow_drillsIntoTheDetail_andBackReturnsToTheList() {
        setContent { SettingsScreen(component()) }

        composeRule.onNodeWithText("Task behavior").performClick()
        // The detail screen shows the drag-and-drop toggle.
        composeRule.onNodeWithText("Drag and drop (experimental)").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Back").performClick()
        // Back at the list — another category row is visible again.
        composeRule.onNodeWithText("Appearance").assertIsDisplayed()
    }

    @Test
    fun appearance_selectingMono_forwardsTheThemeIntent() {
        val repo = FakeSettingsRepository()
        val writer = FakeSettingsWriter(repo)
        setContent { SettingsScreen(component(repo, writer)) }

        composeRule.onNodeWithText("Appearance").performClick()
        composeRule.onNodeWithText("Mono").performClick()

        assertTrue("a theme write was forwarded", writer.themeChanges.isNotEmpty())
        assertEquals(ThemeFamily.Mono, writer.themeChanges.last().first)
    }

    @Test
    fun taskBehavior_togglingDragAndDrop_forwardsTheIntent() {
        val repo = FakeSettingsRepository()
        val writer = FakeSettingsWriter(repo)
        setContent { SettingsScreen(component(repo, writer)) }

        composeRule.onNodeWithText("Task behavior").performClick()
        composeRule.onNodeWithText("Drag and drop (experimental)").performClick()

        assertEquals(listOf(true), writer.dragAndDropChanges)
    }

    @Test
    fun comingSoonStub_rendersGently_withNoDeadTap() {
        setContent { SettingsScreen(component()) }

        composeRule.onNodeWithText("Integrations").performClick()
        // It opens a real detail explaining the coming-soon state (not a dead tap).
        composeRule.onNodeWithText("Coming soon").assertIsDisplayed()
    }
}
