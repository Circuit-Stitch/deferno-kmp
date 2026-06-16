package com.circuitstitch.deferno.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.circuitstitch.deferno.core.data.item.InMemoryItemFoldStore
import com.circuitstitch.deferno.core.designsystem.theme.DefernoTheme
import com.circuitstitch.deferno.demo.DemoItemRepository
import com.circuitstitch.deferno.demo.DemoPlanRepository
import com.circuitstitch.deferno.demo.DemoTaskRepository
import com.circuitstitch.deferno.demo.SampleData
import com.circuitstitch.deferno.shell.DefaultMainShellComponent
import com.circuitstitch.deferno.shell.Destination
import com.circuitstitch.deferno.shell.MainShell
import com.circuitstitch.deferno.shell.MainShellComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose UI interaction test (#175 follow-up) for the shared [ShellChrome] reveal drawer, run on the
 * JVM via Robolectric. It proves the View wiring the [MainShellComponentTest] logic can't: the top-bar
 * **menu** button opens the drawer, picking a destination row switches the foreground Destination
 * (via the same `selectDestination` path), and the **Search** row opens the Search overlay.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h800dp")
@OptIn(ExperimentalTestApi::class)
class MainShellNavInteractionTest {

    @get:Rule
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

    @Test
    fun menuButton_opensDrawer_andSelectingADestinationSwitches() {
        val shell = shell()
        composeRule.setContent { DefernoTheme { MainShell(shell) } }

        // The menu button slides the content aside to reveal the drawer; picking Profile switches to it.
        composeRule.onNodeWithContentDescription("Menu").performClick()
        composeRule.onNodeWithText("Profile").performClick()

        assertEquals(Destination.Profile, shell.stack.value.active.instance.destination)
    }

    @Test
    fun drawerSearchRow_opensTheSearchOverlay() {
        val shell = shell()
        composeRule.setContent { DefernoTheme { MainShell(shell) } }
        assertNull(shell.overlay.value.child)

        composeRule.onNodeWithContentDescription("Menu").performClick()
        composeRule.onNodeWithText("Search").performClick()

        assertTrue(shell.overlay.value.child?.instance is MainShellComponent.OverlayChild.Search)
    }
}
