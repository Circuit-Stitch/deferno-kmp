package com.circuitstitch.deferno.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.circuitstitch.deferno.core.designsystem.theme.DefernoTheme
import com.circuitstitch.deferno.core.domain.command.CommandKind
import com.circuitstitch.deferno.core.domain.command.CommandResult
import com.circuitstitch.deferno.core.domain.command.CreateItem
import com.circuitstitch.deferno.core.data.item.InMemoryItemFoldStore
import com.circuitstitch.deferno.demo.DemoItemRepository
import com.circuitstitch.deferno.demo.DemoPlanRepository
import com.circuitstitch.deferno.demo.DemoTaskRepository
import com.circuitstitch.deferno.demo.SampleData
import com.circuitstitch.deferno.shell.DefaultMainShellComponent
import com.circuitstitch.deferno.shell.MainShell
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose UI interaction test (#71, ADR-0015/0016) for the **New** create overlay, on the JVM via
 * Robolectric. Proves the View wiring: the shell FAB opens New; the kind picker is an **explicit**
 * segmented control defaulting to **Task** (not field-inference, design-principle #5); filling the
 * form + tapping **Create** dispatches the online-only create command; and the offline path renders
 * the gentle "reconnect to save" (with nothing enqueued — ADR-0016).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h800dp")
@OptIn(ExperimentalTestApi::class)
class NewOverlayInteractionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun shell(
        create: suspend (CreateItem.Payload) -> CommandResult,
    ): DefaultMainShellComponent {
        val settingsRepo = FakeSettingsRepository()
        return DefaultMainShellComponent(
            componentContext = DefaultComponentContext(LifecycleRegistry()),
            itemRepository = DemoItemRepository(),
            foldStore = InMemoryItemFoldStore(),
            taskRepository = DemoTaskRepository(SampleData.tasks),
            planRepository = DemoPlanRepository(emptyList()),
            authRepository = FakeAuthRepository(),
            settingsRepository = settingsRepo,
            settingsEditor = FakeSettingsEditor(settingsRepo),
            account = sampleAccount,
            today = LocalDate(2026, 6, 6),
            timeZone = "UTC",
            coroutineContext = Dispatchers.Unconfined,
            create = create,
        )
    }

    @Test
    fun fab_opensNew_pickerDefaultsToTask_andCreateDispatchesTheCommand() {
        val created = mutableListOf<CreateItem.Payload>()
        val shell = shell(create = { payload ->
            created += payload
            CommandResult.Accepted(CommandKind.CreateItem)
        })
        composeRule.setContent { DefernoTheme { MainShell(shell) } }

        // The shell-level FAB opens the New overlay.
        composeRule.onNodeWithContentDescription("New").performClick()

        // The explicit kind picker is shown and defaults to Task (the form's Title field is present).
        composeRule.onNodeWithText("Task").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Title").performTextInput("buy milk")
        composeRule.onNodeWithText("Create task").performClick()

        // The online-only create command was dispatched with a Task payload; Accepted dismisses the overlay.
        assertEquals(1, created.size)
        assertTrue(created[0] is CreateItem.Payload.Task)
    }

    @Test
    fun offlineCreate_showsReconnectToSave() {
        val shell = shell(create = { CommandResult.Offline(CommandKind.CreateItem) })
        composeRule.setContent { DefernoTheme { MainShell(shell) } }

        composeRule.onNodeWithContentDescription("New").performClick()
        composeRule.onNodeWithContentDescription("Title").performTextInput("buy milk")
        composeRule.onNodeWithText("Create task").performClick()

        // The gentle "reconnect to save" is shown; the overlay stays open (nothing was enqueued).
        composeRule.onNodeWithContentDescription("Reconnect to save").assertIsDisplayed()
    }

    @Test
    fun picker_switchesTheKindExplicitly() {
        val created = mutableListOf<CreateItem.Payload>()
        val shell = shell(create = { payload ->
            created += payload
            CommandResult.Accepted(CommandKind.CreateItem)
        })
        composeRule.setContent { DefernoTheme { MainShell(shell) } }

        composeRule.onNodeWithContentDescription("New").performClick()
        // Explicitly pick Habit (segmented control, not inferred), then create.
        composeRule.onNodeWithText("Habit").performClick()
        composeRule.onNodeWithContentDescription("Title").performTextInput("stretch")
        composeRule.onNodeWithText("Create task").performClick()

        assertTrue(created[0] is CreateItem.Payload.Habit)
    }
}
