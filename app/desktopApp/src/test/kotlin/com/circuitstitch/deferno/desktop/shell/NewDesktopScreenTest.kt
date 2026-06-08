package com.circuitstitch.deferno.desktop.shell

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.circuitstitch.deferno.core.designsystem.theme.DefernoPalette
import com.circuitstitch.deferno.core.designsystem.theme.DefernoTheme
import com.circuitstitch.deferno.core.domain.command.CommandKind
import com.circuitstitch.deferno.core.domain.command.CommandResult
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.shell.DefaultNewComponent
import com.circuitstitch.deferno.shell.NewComponent
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The desktop New-overlay render test (#87, cf. #39) — a Compose-Multiplatform UI test on the JVM-fast
 * path (no device; the desktop has no Roborazzi/Robolectric harness). It renders [NewDesktopScreen] over
 * a real [DefaultNewComponent] and asserts the explicit kind picker defaults to Task and that selecting
 * Event reveals the Event-only start field — the create rules themselves are unit-tested in app/shell.
 */
@OptIn(ExperimentalTestApi::class)
class NewDesktopScreenTest {

    private fun newComponent(): NewComponent = DefaultNewComponent(
        // The render test never submits, so the create seam stays an offline stub and [launch] does not
        // run the suspending block.
        create = { CommandResult.Offline(CommandKind.CreateItem) },
        onCreated = {},
        launch = { _ -> },
    )

    @Test
    fun kindPicker_defaultsToTask() = runComposeUiTest {
        val component = newComponent()
        setContent { Themed { NewDesktopScreen(component) } }

        // Defaults to Task (ADR-0015): the Task chip is present, the component's selected kind is Task,
        // and the Event-only start field is hidden until Event is chosen.
        onNodeWithText("Task").assertExists()
        assertEquals(ItemKind.Task, component.state.value.selectedKind)
        onNodeWithContentDescription("Event start").assertDoesNotExist()

        // With a blank title, Create is gated off by the shared canSubmit.
        onNodeWithText("Create").assertIsNotEnabled()
    }

    @Test
    fun selectingEvent_revealsStartField() = runComposeUiTest {
        val component = newComponent()
        setContent { Themed { NewDesktopScreen(component) } }

        onNodeWithContentDescription("Event start").assertDoesNotExist()
        onNodeWithText("Event").performClick()
        onNodeWithContentDescription("Event start").assertExists()
    }
}

@Composable
private fun Themed(content: @Composable () -> Unit) {
    DefernoTheme(palette = DefernoPalette.Deferno, darkTheme = false) {
        Surface(modifier = Modifier.fillMaxSize()) { content() }
    }
}
